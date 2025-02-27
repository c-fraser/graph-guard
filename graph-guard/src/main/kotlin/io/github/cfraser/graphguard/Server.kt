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
import io.ktor.network.tls.tls
import io.ktor.util.network.hostname
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManager
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
 * @property trustManager the [TrustManager] to use for the TLS connection to the [graph]
 */
class Server
@JvmOverloads
constructor(
    val graph: URI,
    plugin: Plugin = Plugin.DSL.plugin {},
    val address: InetSocketAddress = InetSocketAddress("localhost", 8787),
    private val parallelism: Int? = null,
    private val trustManager: TrustManager? = null
) : Runnable {

  private val running = AtomicBoolean()

  /**
   * The [Server.Plugin] used by the [Server].
   *
   * [plugin] delegates to the given [Server.Plugin] implementation, but prevents exceptions from
   * being propagated, to avoid proxy [Server] instability.
   */
  private val plugin =
      Plugin.DSL.plugin {
        intercept { session, message ->
          plugin
              .runCatching { intercept(session, message) }
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
    check(running.compareAndSet(false, true)) {
      "The ${Server::class.simpleName} is already started"
    }
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
                } catch (cancellation: CancellationException) {
                  LOGGER.debug("Proxy connection closed", cancellation)
                } catch (thrown: Throwable) {
                  LOGGER.error("Proxy connection failure", thrown)
                }
              }
            }
          }
    } catch (_: InterruptedException) {} finally {
      running.set(false)
    }
  }

  /** Bind the proxy [ServerSocket] to the [address] then run the [block]. */
  private suspend fun bind(block: suspend CoroutineScope.(SelectorManager, ServerSocket) -> Unit) {
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
    var socket = aSocket(selector).tcp().connect(KInetSocketAddress(graph.host, graph.port))
    if ("+s" in graph.scheme)
        socket =
            socket.tls(coroutineContext = coroutineContext) {
              trustManager = this@Server.trustManager
            }
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

  /** Proxy a [Bolt.Session] between the *client* and *graph*. */
  @Suppress("LongParameterList", "TooGenericExceptionCaught")
  private suspend fun proxy(
      clientConnection: Connection,
      clientReader: ByteReadChannel,
      clientWriter: ByteWriteChannel,
      graphConnection: Connection,
      graphReader: ByteReadChannel,
      graphWriter: ByteWriteChannel
  ) = coroutineScope {
    val handshake = clientReader.readHandshake()
    LOGGER.debug("Read handshake from {} '{}'", clientConnection, handshake)
    graphWriter.writeByteArray(handshake)
    LOGGER.debug("Wrote handshake to {}", graphConnection)
    var version = graphReader.readVersion()
    LOGGER.debug("Read version from {} '{}'", graphConnection, version)
    // modern protocol negotiation
    if (version == Bolt.Version.NEGOTIATION_V2) {
      val versions = graphReader.readVersions()
      LOGGER.debug("Read versions from {} '{}'", graphConnection, versions)
      var capabilities = graphReader.readByte()
      LOGGER.debug("Read capabilities from {} '{}'", graphConnection, capabilities)
      clientWriter.writeVersion(Bolt.Version.NEGOTIATION_V2)
      clientWriter.writeByte(versions.size.toByte())
      versions.forEach { v -> clientWriter.writeVersion(v) }
      LOGGER.debug("Wrote versions to {}", clientConnection)
      clientWriter.writeByte(capabilities)
      LOGGER.debug("Wrote capabilities to {}", clientConnection)
      version = clientReader.readVersion()
      LOGGER.debug("Read negotiated version from {} '{}'", clientConnection, version)
      capabilities = clientReader.readByte()
      LOGGER.debug("Read negotiated capabilities from {} '{}'", clientConnection, capabilities)
      graphWriter.writeVersion(version)
      LOGGER.debug("Wrote negotiated version to {}", graphConnection)
      graphWriter.writeByte(capabilities)
      LOGGER.debug("Wrote negotiated capabilities to {}", graphConnection)
    }
    // complete legacy handshake
    else {
      clientWriter.writeVersion(version)
      LOGGER.debug("Wrote version to {}", clientConnection)
    }
    val session = Bolt.Session("${UUID.randomUUID()}", version)
    val requestWriter = graphConnection to graphWriter
    val responseWriter = clientConnection to clientWriter
    try {
      val incoming =
          proxy(session, clientConnection, clientReader) { message ->
            if (message is Bolt.Request) requestWriter else responseWriter
          }
      val outgoing =
          proxy(session, graphConnection, graphReader) { message ->
            if (message is Bolt.Response) responseWriter else requestWriter
          }
      select {
        incoming.onJoin { outgoing.cancel() }
        outgoing.onJoin { incoming.cancel() }
      }
    } catch (cancellation: CancellationException) {
      LOGGER.debug("Proxy session closed", cancellation)
    } catch (thrown: Throwable) {
      LOGGER.error("Proxy session failure", thrown)
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
      session: Bolt.Session,
      source: Connection,
      reader: ByteReadChannel,
      resolver: (Bolt.Message) -> Pair<Connection, ByteWriteChannel>
  ): Job = launch {
    while (isActive) {
      val message =
          try {
            reader.readMessage()
          } catch (_: Throwable) {
            break
          }
      LOGGER.debug("Read '{}' from {}", message, source)
      val intercepted = plugin.intercept(session, message)
      val (destination, writer) = resolver(intercepted)
      try {
        writer.writeMessage(intercepted)
      } catch (thrown: Throwable) {
        LOGGER.error("Failed to write '{}' to {}", intercepted, destination, thrown)
        break
      }
      LOGGER.debug("Wrote '{}' to {}", intercepted, destination)
      plugin.observe(Proxied(session, source, message, destination, intercepted))
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
     * @param session the [Bolt.Session]
     * @param message the intercepted
     *   [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages)
     * @return the [Bolt.Message] to send
     */
    suspend fun intercept(session: Bolt.Session, message: Bolt.Message): Bolt.Message

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
        intercept { session, message ->
          that.intercept(session, `this`.intercept(session, message))
        }
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
       * @param session the [Bolt.Session]
       * @param message the intercepted
       *   [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages)
       * @return a [CompletableFuture] with the [Bolt.Message] to send
       */
      abstract fun interceptAsync(
          session: String,
          message: Bolt.Message,
      ): CompletableFuture<Bolt.Message>

      /**
       * Asynchronously [observe] the [event].
       *
       * @param event the [Server.Event] that occurred
       * @return a [CompletableFuture] of [Void]
       */
      abstract fun observeAsync(event: Event): CompletableFuture<Void>

      /** [interceptAsync] then [await] for the [CompletableFuture] to complete. */
      final override suspend fun intercept(
          session: Bolt.Session,
          message: Bolt.Message,
      ): Bolt.Message = interceptAsync(session.id, message).await()

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
       *  intercept { _, message -> message.also(::println) }
       *  observe { _, event -> println(event) }
       * }
       * ```
       *
       * @param builder the function that builds the [Server.Plugin]
       * @return the built [Server.Plugin]
       */
      fun plugin(builder: Builder.() -> Unit): Plugin = Builder().apply(builder).build()
    }

    /**
     * [Server.Plugin.Builder] builds a [Server.Plugin].
     *
     * @property interceptor the [Server.Plugin.intercept] function
     * @property observer the [Server.Plugin.observe] function
     */
    class Builder internal constructor() {

      private var interceptor: (suspend (Bolt.Session, Bolt.Message) -> Bolt.Message)? = null
      private var observer: (suspend (Event) -> Unit)? = null

      /**
       * Set the [Server.Plugin.intercept] implementation.
       *
       * @param interceptor the [Server.Plugin.intercept] function to use
       * @throws IllegalStateException if [Server.Plugin.intercept] has already been set
       */
      fun intercept(interceptor: suspend (Bolt.Session, Bolt.Message) -> Bolt.Message) {
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
        val interceptor = interceptor ?: { _, message -> message }
        val observer = observer ?: { _ -> }
        return object : Plugin {

          /** Intercept the [message] with the [interceptor]. */
          override suspend fun intercept(session: Bolt.Session, message: Bolt.Message) =
              interceptor(session, message)

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
   * @property session the [Bolt.Session]
   * @property source the [Connection] that sent the [received] [Bolt.Message]
   * @property received the [Bolt.Message] received from the [source]
   * @property destination the [Connection] that received the [sent] [Bolt.Message]
   * @property sent the [Bolt.Message] sent to the [destination]
   */
  @JvmRecord
  data class Proxied(
      val session: Bolt.Session,
      val source: Connection,
      val received: Bolt.Message,
      val destination: Connection,
      val sent: Bolt.Message
  ) : Event

  /** The [Server] has stopped. */
  data object Stopped : Event

  @VisibleForTesting
  @Suppress("TooManyFunctions")
  internal companion object {

    private val LOGGER = LoggerFactory.getLogger(Server::class.java)!!

    /** Run the [block] with the [context] in the [MDC]. */
    private suspend fun withLoggingContext(
        vararg context: Pair<String, String>,
        block: suspend () -> Unit
    ) {
      val reset = context.map { (key, value) -> MDC.putCloseable(key, value) }
      val result = withContext(MDCContext()) { runCatching { block() } }
      reset.runCatching { forEach(MDCCloseable::close) }
      result.getOrThrow()
    }

    /** Convert the [KSocketAddress] to an [InetSocketAddress]. */
    private fun KSocketAddress.toInetSocketAddress(): InetSocketAddress =
        checkNotNull(toJavaAddress() as? InetSocketAddress) { "Unexpected address '$this'" }

    /** Use the opened [ByteReadChannel] and [ByteWriteChannel] for the [Socket]. */
    private suspend fun Socket.withChannels(
        block: suspend (ByteReadChannel, ByteWriteChannel) -> Unit
    ) {
      use { socket ->
        val reader = socket.openReadChannel()
        val writer = socket.openWriteChannel(autoFlush = true)
        block(reader, writer)
      }
    }

    /** Read and verify the [handshake](https://neo4j.com/docs/bolt/current/bolt/handshake/). */
    private suspend fun ByteReadChannel.readHandshake(): ByteArray {

      /**
       * Verify the handshake [bytes] contains the [ID] and supports Bolt 5+.
       *
       * @throws IllegalStateException if the handshake is invalid/unsupported
       */
      fun verify(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes.copyOf())
        val id = buffer.getInt()
        check(id == ID) { "Unexpected identifier '0x${Integer.toHexString(id)}'" }
        val versions = Array(4) { _ -> Bolt.Version.decode(buffer.getInt()) }
        check(versions.any { version -> version.major >= 5 }) {
          "None of the versions '$versions' are supported"
        }
      }
      val bytes = readByteArray(Int.SIZE_BYTES * 5)
      return bytes.also(::verify)
    }

    /** Read the [Bolt.Version]. */
    private suspend inline fun ByteReadChannel.readVersion(): Bolt.Version =
        Bolt.Version.decode(readInt())

    /** Read the [Bolt.Version]s supported by the graph. */
    private suspend fun ByteReadChannel.readVersions(): Array<Bolt.Version> {
      var size = 0L
      var position = 0.toByte()
      while (true) {
        val segment = readByte()
        size = size or ((127 and segment.toInt()).toByte().toLong()) shl (position * 7)
        position++
        if ((segment.toInt() shr 7) == 0) break
      }
      return Array(size.toInt()) { readVersion() }
    }

    /** Write the [version]. */
    private suspend inline fun ByteWriteChannel.writeVersion(version: Bolt.Version) {
      writeInt(version.encode())
    }

    /** Read a [Bolt.Message] from the [ByteReadChannel]. */
    private suspend fun ByteReadChannel.readMessage(
        timeout: Duration = Duration.INFINITE
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
          bytes += readByteArray(size)
        }
      }
      return bytes
    }

    /** Write a [Bolt.Message] to the [ByteWriteChannel]. */
    private suspend fun ByteWriteChannel.writeMessage(
        message: Bolt.Message,
        maxChunkSize: Int = UShort.MAX_VALUE.toInt(),
    ) {
      (if (message is Bolt.Messages) message.messages else listOf(message))
          .map { msg -> msg.toStructure() }
          .map { structure -> PackStream.pack { structure(structure) } }
          .forEach { bytes -> writeChunked(bytes, maxChunkSize) }
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
            writeByteArray(chunk)
            writeByteArray(byteArrayOf(0x0, 0x0))
          }
    }
  }
}
