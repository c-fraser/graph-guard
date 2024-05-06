/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.github.cfraser.graphguard

import io.github.cfraser.graphguard.Bolt.ID
import io.github.cfraser.graphguard.Bolt.toMessage
import io.github.cfraser.graphguard.Bolt.toStructure
import io.github.cfraser.graphguard.PackStream.unpack
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.util.network.hostname
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.use
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.MDC.MDCCloseable
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import io.ktor.network.sockets.InetSocketAddress as KInetSocketAddress
import io.ktor.network.sockets.SocketAddress as KSocketAddress

/**
 * [Server] proxies [Bolt](https://neo4j.com/docs/bolt/current/bolt/) data to a
 * [Neo4j](https://neo4j.com/) (5+ compatible) database and performs dynamic message transformation
 * through the [plugin].
 *
 * @param plugin the [Server.Plugin] to use to intercept proxied messages and observe server events
 * @property graph the [URI] of the graph database to proxy data to/from
 * @property address the [InetSocketAddress] to bind the [Server] to
 * @property parallelism the number of parallel coroutines used by the [Server]
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalContracts::class)
class Server
@JvmOverloads
constructor(
    val graph: URI,
    plugin: Plugin = Plugin.DSL.plugin {},
    val address: InetSocketAddress = InetSocketAddress("localhost", 8787),
    private val parallelism: Int? = null
) : Runnable {

  init {
    check("+s" !in graph.scheme) { "${Server::class.simpleName} doesn't support TLS" }
  }

  /**
   * The [Server.Plugin] used by the [Server].
   *
   * [plugin] delegates to the given [Server.Plugin] implementation, but prevents exceptions from
   * being propagated, to avoid proxy [Server] instability.
   */
  private val plugin =
      Plugin.DSL.plugin {
        intercept { message ->
          plugin
              .runCatching { intercept(message) }
              .onFailure { LOGGER.error("Failed to intercept '{}'", message, it) }
              .getOrDefault(message)
        }
        observe { event ->
          plugin
              .runCatching { observe(event) }
              .onFailure { LOGGER.error("Failed to observe '{}'", event, it) }
        }
      }

  /**
   * Start the proxy server on the [address], connecting to the [graph].
   *
   * [Server.run] blocks indefinitely. To stop the server, [java.lang.Thread.interrupt] the blocked
   * thread. [InterruptedException] is **not** thrown after the server is stopped.
   */
  @Suppress("TooGenericExceptionCaught")
  override fun run() {
    try {
      runBlocking(
          when (val parallelism = parallelism) {
            null -> Dispatchers.IO
            else -> Dispatchers.IO.limitedParallelism(parallelism)
          }) {
            bind { selector, serverSocket ->
              while (isActive) {
                try {
                  accept(this, serverSocket) { clientConnection, clientReader, clientWriter ->
                    connect(selector) { graphConnection, graphReader, graphWriter ->
                      proxy(
                          clientConnection,
                          clientReader,
                          clientWriter,
                          graphConnection,
                          graphReader,
                          graphWriter)
                    }
                  }
                } catch (thrown: Throwable) {
                  when (thrown) {
                    is CancellationException -> LOGGER.debug("Proxy connection closed", thrown)
                    else -> LOGGER.error("Proxy connection failure", thrown)
                  }
                }
              }
            }
          }
    } catch (_: InterruptedException) {}
  }

  /** Bind the proxy [ServerSocket] to the [address] then run the [block]. */
  private suspend fun bind(block: suspend CoroutineScope.(SelectorManager, ServerSocket) -> Unit) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    withLoggingContext("graph-guard.server" to "$address", "graph-guard.graph" to "$graph") {
      try {
        SelectorManager(coroutineContext).use { selector ->
          val socket =
              aSocket(selector).tcp().bind(KInetSocketAddress(address.hostname, address.port))
          LOGGER.info("Started proxy server on '{}'", socket.localAddress)
          plugin.observe(Started)
          socket.use { server -> coroutineScope { block(selector, server) } }
        }
      } finally {
        LOGGER.info("Stopped proxy server")
        plugin.observe(Stopped)
      }
    }
  }

  /**
   * Accept a client connection from the [serverSocket] then [launch] a coroutine to run the [block]
   * with the [Socket] channels.
   */
  private suspend fun accept(
      coroutineScope: CoroutineScope,
      serverSocket: ServerSocket,
      block: suspend (Connection, ByteReadChannel, ByteWriteChannel) -> Unit
  ) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val socket = serverSocket.accept()
    val clientConnection = Connection.Client(socket.remoteAddress.toInetSocketAddress())
    coroutineScope.launch {
      try {
        socket.withChannels { reader, writer ->
          LOGGER.debug("Accepted connection from '{}'", clientConnection)
          plugin.observe(Connected(clientConnection))
          withLoggingContext("graph-guard.client" to "$clientConnection") {
            block(clientConnection, reader, writer)
          }
        }
      } finally {
        LOGGER.debug("Closed connection from '{}'", clientConnection)
        plugin.observe(Disconnected(clientConnection))
      }
    }
  }

  /** Connect to the [graph] then run the [block] with the [Socket] channels. */
  private suspend fun connect(
      selector: SelectorManager,
      block: suspend (Connection, ByteReadChannel, ByteWriteChannel) -> Unit
  ) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val socket = aSocket(selector).tcp().connect(KInetSocketAddress(graph.host, graph.port))
    val graphConnection = Connection.Graph(socket.remoteAddress.toInetSocketAddress())
    try {
      socket.withChannels { reader, writer ->
        LOGGER.debug("Connected to '{}'", graphConnection)
        plugin.observe(Connected(graphConnection))
        block(graphConnection, reader, writer)
      }
    } finally {
      LOGGER.debug("Closed connection to '{}'", graphConnection)
      plugin.observe(Disconnected(graphConnection))
    }
  }

  /**
   * Manage a [Bolt (proxy) session](https://neo4j.com/docs/bolt/current/bolt/message/#session)
   * between the *client* and *graph*.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun proxy(
      clientConnection: Connection,
      clientReader: ByteReadChannel,
      clientWriter: ByteWriteChannel,
      graphConnection: Connection,
      graphReader: ByteReadChannel,
      graphWriter: ByteWriteChannel,
  ): Unit = coroutineScope {
    val handshake = clientReader.verifyHandshake()
    LOGGER.debug("Read handshake from {} '{}'", clientConnection, handshake)
    graphWriter.writeFully(handshake)
    LOGGER.debug("Wrote handshake to {}", graphConnection)
    val version = graphReader.readVersion()
    LOGGER.debug("Read version from {} '{}'", graphConnection, version)
    clientWriter.writeInt(version.bytes())
    LOGGER.debug("Wrote version to {}", clientConnection)
    val requestWriter = graphConnection to graphWriter
    val responseWriter = clientConnection to clientWriter
    try {
      val incoming =
          proxy(clientConnection, clientReader) { message ->
            if (message is Bolt.Request) requestWriter else responseWriter
          }
      val outgoing =
          proxy(graphConnection, graphReader) { message ->
            if (message is Bolt.Response) responseWriter else requestWriter
          }
      select {
        incoming.onJoin { outgoing.cancel() }
        outgoing.onJoin { incoming.cancel() }
      }
    } catch (thrown: Throwable) {
      when (thrown) {
        is CancellationException -> LOGGER.debug("Proxy session closed", thrown)
        else -> LOGGER.error("Proxy session failure", thrown)
      }
    }
  }

  /**
   * Proxy the *intercepted*
   * [Bolt messages](https://neo4j.com/docs/bolt/current/bolt/message/#message-exchange) from the
   * [source] to the *resolved destination*.
   * > Intercept [Bolt.Goodbye] and [cancel] the [CoroutineScope] to end the session.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun CoroutineScope.proxy(
      source: Connection,
      reader: ByteReadChannel,
      resolver: (Bolt.Message) -> Pair<Connection, ByteWriteChannel>,
  ): Job = launch {
    while (isActive) {
      val message =
          try {
            reader.readMessage()
          } catch (_: Throwable) {
            break
          }
      LOGGER.debug("Read '{}' from {}", message, source)
      val intercepted = plugin.intercept(message)
      val (destination, writer) = resolver(intercepted)
      try {
        writer.writeMessage(intercepted)
      } catch (thrown: Throwable) {
        LOGGER.error("Failed to write '{}' to {}", intercepted, destination, thrown)
        break
      }
      LOGGER.debug("Wrote '{}' to {}", intercepted, destination)
      plugin.observe(Proxied(source, message, destination, intercepted))
      if (intercepted == Bolt.Goodbye) cancel("${Bolt.Goodbye}")
    }
  }

  /**
   * [Server.Plugin] enables [Server] functionality to be augmented and/or observed.
   *
   * [Server.Plugin] function execution is non-blocking, but occurs synchronously within [Server]
   * proxy operations. Therefore, implement [Server.Plugin] functions judiciously, considering that
   * long suspension time will impact [Server] throughput.
   *
   * [Server.Plugin] function invocation is **not** thread-safe. Thus, perform any necessary
   * synchronization in the [Server.Plugin] implementation.
   */
  interface Plugin {

    /**
     * Intercept, and optionally transform, the [message].
     *
     * If the returned [Bolt.Message] is a [Bolt.Request] then it's sent to the graph server,
     * otherwise the [Bolt.Response] is sent to the proxy client.
     *
     * @param message the intercepted
     *   [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages)
     * @return the [Bolt.Message] to send
     */
    suspend fun intercept(message: Bolt.Message): Bolt.Message

    /**
     * Observe the [event].
     *
     * @param event the [Server.Event] that occurred
     */
    suspend fun observe(event: Event)

    /**
     * Run `this` [Server.Plugin] then [that].
     *
     * @param that the [Server.Plugin] to chain with `this`
     * @return a [Server.Plugin] that invokes `this` then [that]
     */
    infix fun then(that: Plugin): Plugin {
      @Suppress("VariableNaming") val `this` = this
      return DSL.plugin {
        intercept { message -> that.intercept(`this`.intercept(message)) }
        observe { event ->
          `this`.observe(event)
          that.observe(event)
        }
      }
    }

    /** [Server.Plugin.Async] is an asynchronous [Server.Plugin] intended for use by *Java* code. */
    abstract class Async : Plugin {

      /**
       * Asynchronously [intercept] the [message].
       *
       * @param message the intercepted
       *   [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages)
       * @return a [CompletableFuture] with the [Bolt.Message] to send
       */
      abstract fun interceptAsync(message: Bolt.Message): CompletableFuture<Bolt.Message>

      /**
       * Asynchronously [observe] the [event].
       *
       * @param event the [Server.Event] that occurred
       * @return a [CompletableFuture] of [Void]
       */
      abstract fun observeAsync(event: Event): CompletableFuture<Void>

      /** [interceptAsync] then [await] for the [CompletableFuture] to complete. */
      final override suspend fun intercept(message: Bolt.Message): Bolt.Message {
        return interceptAsync(message).await()
      }

      /** [observeAsync] then [await] for the [CompletableFuture] to complete. */
      final override suspend fun observe(event: Event) {
        observeAsync(event).await()
      }
    }

    /** [Server.Plugin.DSL] to build a [Server.Plugin] with the [Server.Plugin.Builder]. */
    object DSL {

      /**
       * Build a [Server.Plugin] with the [builder] function.
       *
       * ```kotlin
       * val printer = plugin {
       *  intercept { message -> message.also(::println) }
       *  observe { event -> println(event) }
       * }
       * ```
       *
       * @param builder the function that builds the [Server.Plugin]
       * @return the built [Server.Plugin]
       */
      fun plugin(builder: Builder.() -> Unit): Plugin {
        return Builder().apply(builder).build()
      }
    }

    /**
     * [Server.Plugin.Builder] builds a [Server.Plugin].
     *
     * @property interceptor the [Server.Plugin.intercept] function
     * @property observer the [Server.Plugin.observe] function
     */
    class Builder internal constructor() {

      private var interceptor: (suspend (Bolt.Message) -> Bolt.Message)? = null
      private var observer: (suspend (Event) -> Unit)? = null

      /**
       * Set the [Server.Plugin.intercept] implementation.
       *
       * @param interceptor the [Server.Plugin.intercept] function to use
       * @throws IllegalStateException if [Server.Plugin.intercept] has already been set
       */
      fun intercept(interceptor: suspend (Bolt.Message) -> Bolt.Message) {
        check(this.interceptor == null)
        this.interceptor = interceptor
      }

      /**
       * Set the [Server.Plugin.observe] implementation.
       *
       * @param observer the [Server.Plugin.observe] function to use
       * @throws IllegalStateException if [Server.Plugin.observe] has already been set
       */
      fun observe(observer: suspend (Event) -> Unit) {
        check(this.observer == null)
        this.observer = observer
      }

      /** Build the [Server.Plugin] with [interceptor] and [observer]. */
      internal fun build(): Plugin {
        val interceptor = interceptor ?: { it }
        val observer = observer ?: {}
        return object : Plugin {

          /** Intercept the [message] with the [interceptor]. */
          override suspend fun intercept(message: Bolt.Message) = interceptor(message)

          /** Observe the [event] with the [observer]. */
          override suspend fun observe(event: Event) = observer(event)
        }
      }
    }
  }

  /** A [Server] event. */
  sealed interface Event

  /** The [Server] has started. */
  data object Started : Event

  /**
   * The [Server] established a [connection].
   *
   * @property connection the [Server.Connection] metadata
   */
  @JvmRecord data class Connected(val connection: Connection) : Event

  /**
   * The [Server] closed the [connection].
   *
   * @property connection the [Server.Connection] metadata
   */
  @JvmRecord data class Disconnected(val connection: Connection) : Event

  /**
   * A proxy connection.
   *
   * @property address the [InetSocketAddress] of the proxy source/destination
   */
  sealed interface Connection {

    val address: InetSocketAddress

    /**
     * A client the [Server] accepted a connection from.
     *
     * @property address the [InetSocketAddress] of the client
     */
    @JvmRecord data class Client(override val address: InetSocketAddress) : Connection

    /**
     * The graph database the [Server] connected to.
     *
     * @property address the [InetSocketAddress] of the graph database
     */
    @JvmRecord data class Graph(override val address: InetSocketAddress) : Connection
  }

  /**
   * The [Server] proxied the *intercepted* [Bolt.Message] from the [source] to the [destination].
   *
   * @property source the [Connection] that sent the [received] [Bolt.Message]
   * @property received the [Bolt.Message] received from the [source]
   * @property destination the [Connection] that received the [sent] [Bolt.Message]
   * @property sent the [Bolt.Message] sent to the [destination]
   */
  @JvmRecord
  data class Proxied(
      val source: Connection,
      val received: Bolt.Message,
      val destination: Connection,
      val sent: Bolt.Message
  ) : Event

  /** The [Server] has stopped. */
  data object Stopped : Event

  @VisibleForTesting
  internal companion object {

    private val LOGGER = LoggerFactory.getLogger(Server::class.java)!!

    /** Run the [block] with the [context] in the [MDC]. */
    private suspend fun withLoggingContext(
        vararg context: Pair<String, String>,
        block: suspend () -> Unit
    ) {
      contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
      val reset = context.map { (key, value) -> MDC.putCloseable(key, value) }
      val result = withContext(MDCContext()) { runCatching { block() } }
      reset.runCatching { forEach(MDCCloseable::close) }
      result.getOrThrow()
    }

    /** Convert the [KSocketAddress] to an [InetSocketAddress]. */
    private fun KSocketAddress.toInetSocketAddress(): InetSocketAddress {
      return checkNotNull(toJavaAddress() as? InetSocketAddress) { "Unexpected address '$this'" }
    }

    /** Use the opened [ByteReadChannel] and [ByteWriteChannel] for the [Socket]. */
    private suspend fun Socket.withChannels(
        block: suspend (ByteReadChannel, ByteWriteChannel) -> Unit
    ) {
      contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
      use { socket ->
        val reader = socket.openReadChannel()
        val writer = socket.openWriteChannel(autoFlush = true)
        block(reader, writer)
      }
    }

    /** Read and verify the [handshake](https://neo4j.com/docs/bolt/current/bolt/handshake/). */
    private suspend fun ByteReadChannel.verifyHandshake(): ByteArray {

      /**
       * Verify the handshake [bytes] contains the [ID] and supports Bolt 5+.
       *
       * @throws IllegalStateException if the handshake is invalid/unsupported
       */
      fun verify(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes.copyOf())
        val id = buffer.getInt()
        check(id == ID) { "Unexpected identifier '0x${Integer.toHexString(id)}'" }
        val versions = buildList {
          repeat(4) { _ ->
            val version = Bolt.Version(buffer.getInt())
            if (version.major >= 5) return else this += "$version"
          }
        }
        error("None of the versions '$versions' are supported")
      }
      val bytes = ByteArray(Int.SIZE_BYTES * 5)
      readFully(bytes, 0, bytes.size)
      return bytes.also(::verify)
    }

    /**
     * Read the [version](https://neo4j.com/docs/bolt/current/bolt/handshake/#_version_negotiation).
     */
    private suspend fun ByteReadChannel.readVersion(): Bolt.Version {
      return Bolt.Version(readInt())
    }

    /** Read a [Bolt.Message] from the [ByteReadChannel]. */
    private suspend fun ByteReadChannel.readMessage(
        timeout: Duration = Duration.INFINITE,
    ): Bolt.Message {
      val structure = readChunked(timeout).unpack { structure() }
      return structure.toMessage()
    }

    /**
     * Read a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) message from the
     * [ByteReadChannel].
     */
    suspend fun ByteReadChannel.readChunked(timeout: Duration): ByteArray {
      var bytes = ByteArray(0)
      withTimeout(timeout) {
        while (true) {
          val size = readShort().toUShort().toInt()
          if (size == 0) {
            // NoOp chunk (connection keep-alive)
            if (bytes.isEmpty()) continue
            // Received all chunks, return the bytes
            break
          }
          val offset = bytes.lastIndex + 1
          bytes += ByteArray(size)
          readFully(bytes, offset, size)
        }
      }
      return bytes
    }

    /** Write a [Bolt.Message] to the [ByteWriteChannel]. */
    private suspend fun ByteWriteChannel.writeMessage(
        message: Bolt.Message,
        maxChunkSize: Int = UShort.MAX_VALUE.toInt(),
    ) {
      val structure = message.toStructure()
      writeChunked(PackStream.pack { structure(structure) }, maxChunkSize)
    }

    /**
     * Write a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) [message] to
     * the [ByteWriteChannel].
     */
    suspend fun ByteWriteChannel.writeChunked(message: ByteArray, maxChunkSize: Int) {
      message
          .asSequence()
          .chunked(maxChunkSize)
          .map { bytes -> bytes.toByteArray() }
          .forEach { chunk ->
            writeShort(chunk.size.toShort())
            writeFully(chunk)
            writeFully(byteArrayOf(0x0, 0x0))
          }
    }
  }
}
