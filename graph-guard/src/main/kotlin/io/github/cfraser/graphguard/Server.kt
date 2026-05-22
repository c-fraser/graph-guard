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
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.PooledByteBufAllocator
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import javax.net.ssl.TrustManager
import kotlin.io.path.deleteExisting
import kotlin.io.path.notExists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.MDC.MDCCloseable
import reactor.core.publisher.Mono
import reactor.netty.Connection as ReactorNettyConnection
import reactor.netty.DisposableServer
import reactor.netty.NettyInbound
import reactor.netty.NettyOutbound
import reactor.netty.tcp.TcpClient
import reactor.netty.tcp.TcpServer

/**
 * [Server] proxies [Bolt](https://neo4j.com/docs/bolt/current/bolt/) data to a
 * [Neo4j](https://neo4j.com/) (5+ compatible) database and performs dynamic message transformation
 * through the [plugin].
 *
 * @param plugin the [Server.Plugin] to use to intercept proxied messages and observe server events
 * @property graph the [URI] of the graph database to proxy data to/from
 * @property address the [Server.Address] to bind the [Server] to
 * @property trustManager the [TrustManager] to use for the TLS connection to the [graph]
 * @property sslContext the [SslContext] to use for TLS connection to this [Server]
 */
class Server
@JvmOverloads
constructor(
  val graph: URI,
  plugin: Plugin = Plugin.DSL.plugin {},
  val address: Address = Address.InetSocket("localhost", 8787),
  private val trustManager: TrustManager? = null,
  private val sslContext: SslContext? = null,
) : AutoCloseable {

  /** The [running] [DisposableServer] accepting client connections. */
  private lateinit var running: DisposableServer

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
   * Start the [Server].
   *
   * The [Server] is ready to accept client connections after [Server.start] returns.
   */
  @Synchronized
  @Suppress("TooGenericExceptionCaught")
  fun start() {
    check(!::running.isInitialized) { "The proxy server has already been started" }
    if (address is Address.UnixDomainSocket)
      check(address.path.notExists()) { "The unix domain socket file already exists" }
    running =
      try {
        run()
      } catch (e: Exception) {
        close()
        LOGGER.error("Proxy server failed to start", e)
        throw e
      }
    LOGGER.info("Started proxy server on '{}'", running.address())
    runBlocking(MDCContext() + Dispatchers.IO) { plugin.observe(Started) }
  }

  /** Stop the [Server]. */
  override fun close() {
    runBlocking(MDCContext() + Dispatchers.IO) {
      runCatching { running.disposeNow(10.seconds.toJavaDuration()) }
      LOGGER.info("Stopped proxy server")
      plugin.observe(Stopped)
      (address as? Address.UnixDomainSocket)?.let { uds ->
        runCatching { uds.path.deleteExisting() }
      }
    }
  }

  /** Run the [DisposableServer] on the [address], proxying clients to the [graph]. */
  private fun run(): DisposableServer {
    return TcpServer.create()
      .let { server ->
        when (address) {
          is Address.InetSocket -> server.host(address.hostname).port(address.port)
          is Address.UnixDomainSocket ->
            server.bindAddress { UnixDomainSocketAddress.of(address.path) }
        }
      }
      .let { server ->
        when (val sslContext = sslContext) {
          null -> server
          else -> server.secure { spec -> spec.sslContext(sslContext) }
        }
      }
      .handle { inbound, outbound -> accept(inbound, outbound) }
      .bindNow(10.seconds.toJavaDuration())
  }

  /**
   * Accept a client connection from the [inbound] channel and [launch] a coroutine to [proxy] the
   * [Bolt.Session].
   */
  @Suppress("TooGenericExceptionCaught")
  private fun accept(inbound: NettyInbound, outbound: NettyOutbound): Mono<Void> {
    val channel = (inbound as? ReactorNettyConnection)?.channel()
    val clientAddress = channel?.remoteAddress() ?: InetSocketAddress(0)
    val clientConnection = Connection.Client(clientAddress)
    return mono(Dispatchers.IO) {
        withLoggingContext("graph-guard.server" to "$address", "graph-guard.graph" to "$graph") {
          LOGGER.debug("Accepted connection from '{}'", clientConnection)
          plugin.observe(Connected(clientConnection))
          try {
            withLoggingContext("graph-guard.client" to "$clientConnection") {
              val allocator = channel?.alloc() ?: PooledByteBufAllocator.DEFAULT
              coroutineScope {
                try {
                  reader(inbound, allocator).use { clientReader ->
                    writer(outbound, allocator).use { clientWriter ->
                      connect { graphConnection, graphReader, graphWriter ->
                        proxy(
                          clientConnection,
                          clientReader,
                          clientWriter,
                          graphConnection,
                          graphReader,
                          graphWriter,
                        )
                      }
                    }
                  }
                } finally {
                  coroutineContext.cancelChildren()
                }
              }
            }
          } catch (e: CancellationException) {
            LOGGER.debug("Proxy connection closed", e)
          } catch (e: Exception) {
            LOGGER.error("Proxy connection failure", e)
          } finally {
            LOGGER.debug("Closed connection from '{}'", clientConnection)
            plugin.observe(Disconnected(clientConnection))
          }
        }
      }
      .then()
  }

  /** Create a [Reader] for the [inbound] stream. */
  private fun CoroutineScope.reader(inbound: NettyInbound, allocator: ByteBufAllocator): Reader {
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    launch {
      try {
        inbound
          .receive()
          .map { buf -> ByteArray(buf.readableBytes()).also(buf::readBytes) }
          .asFlow()
          .collect { bytes -> channel.send(bytes) }
      } finally {
        channel.close()
      }
    }
    return Reader(channel, allocator)
  }

  /** Create a [Writer] for the [outbound] channel. */
  private fun CoroutineScope.writer(outbound: NettyOutbound, allocator: ByteBufAllocator): Writer {
    val writer = Writer(allocator)
    launch {
      outbound
        .send(flow { for (buf in writer.channel) emit(buf) }.asFlux())
        .then()
        .awaitSingleOrNull()
    }
    return writer
  }

  /**
   * Connect to the [graph] then run the [block] with the [Reader] and [Writer] for the
   * [Connection].
   */
  @Suppress("NestedBlockDepth")
  private suspend fun CoroutineScope.connect(
    block: suspend CoroutineScope.(Connection, Reader, Writer) -> Unit
  ) {
    val client =
      TcpClient.create()
        .remoteAddress {
          if ("+unix" in graph.scheme) UnixDomainSocketAddress.of(graph.path)
          else InetSocketAddress(graph.host, graph.port)
        }
        .let { client ->
          if ("+s" in graph.scheme) {
            val sslContext =
              SslContextBuilder.forClient()
                .let { builder ->
                  when (val trustManager = trustManager) {
                    null -> builder
                    else -> builder.trustManager(trustManager)
                  }
                }
                .build()
            client.secure { spec -> spec.sslContext(sslContext) }
          } else client
        }
    val connection = client.connect().awaitSingleOrNull() ?: error("Failed to connect to graph")
    val channel = connection.channel()
    val graphConnection = Connection.Graph(channel.remoteAddress())
    reader(connection.inbound(), channel.alloc()).use { graphReader ->
      writer(connection.outbound(), channel.alloc()).use { graphWriter ->
        LOGGER.debug("Connected to '{}'", graphConnection)
        plugin.observe(Connected(graphConnection))
        try {
          block(graphConnection, graphReader, graphWriter)
        } finally {
          connection.dispose()
          LOGGER.debug("Closed connection to '{}'", graphConnection)
          plugin.observe(Disconnected(graphConnection))
        }
      }
    }
  }

  /** Proxy a [Bolt.Session] between the *client* and *graph*. */
  @Suppress("LongParameterList", "TooGenericExceptionCaught")
  private suspend fun CoroutineScope.proxy(
    clientConnection: Connection,
    clientReader: Reader,
    clientWriter: Writer,
    graphConnection: Connection,
    graphReader: Reader,
    graphWriter: Writer,
  ) {
    val handshake =
      flow { emit(clientReader.readHandshake()) }
        .retry(3) { cause -> cause is EOFException }
        .catch { thrown ->
          LOGGER.error("Failed to read handshake from {}", clientConnection, thrown)
        }
        .firstOrNull() ?: return
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
      val manifest = clientWriter.allocator.buffer(4 + 1 + versions.size * 4 + 1)
      manifest.writeInt(Bolt.Version.NEGOTIATION_V2.encode())
      manifest.writeByte(versions.size)
      for (v in versions) manifest.writeInt(v.encode())
      manifest.writeByte(capabilities.toInt())
      clientWriter.channel.send(manifest)
      LOGGER.debug("Wrote manifest to {}", clientConnection)
      version = clientReader.readVersion()
      LOGGER.debug("Read negotiated version from {} '{}'", clientConnection, version)
      capabilities = clientReader.readByte()
      LOGGER.debug("Read negotiated capabilities from {} '{}'", clientConnection, capabilities)
      val negotiated = graphWriter.allocator.buffer(4 + 1)
      negotiated.writeInt(version.encode())
      negotiated.writeByte(capabilities.toInt())
      graphWriter.channel.send(negotiated)
      LOGGER.debug("Wrote negotiated version/capabilities to {}", graphConnection)
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
    } catch (thrown: Exception) {
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
    reader: Reader,
    resolver: (Bolt.Message) -> Pair<Connection, Writer>,
  ): Job = launch {
    while (isActive) {
      val message =
        try {
          reader.readMessage()
        } catch (_: Exception) {
          break
        }
      LOGGER.debug("Read '{}' from {}", message, source)
      val intercepted = plugin.intercept(session, message)
      val (destination, writer) = resolver(intercepted)
      try {
        writer.writeMessage(intercepted)
      } catch (thrown: Exception) {
        LOGGER.error("Failed to write '{}' to {}", intercepted, destination, thrown)
        break
      }
      LOGGER.debug("Wrote '{}' to {}", intercepted, destination)
      plugin.observe(Proxied(session, source, message, destination, intercepted))
      if (intercepted == Bolt.Goodbye) cancel("${Bolt.Goodbye}")
    }
  }

  /** An address for the [Server] to bind to. */
  sealed interface Address {

    /**
     * An IP socket address.
     *
     * @property hostname the hostname to bind to
     * @property port the port to bind to
     * @see InetSocketAddress
     */
    @JvmRecord data class InetSocket(val hostname: String, val port: Int) : Address

    /**
     * A unix domain socket address.
     *
     * @property path the [Path] to the unix domain socket file
     * @see UnixDomainSocketAddress
     */
    @JvmInline value class UnixDomainSocket(val path: Path) : Address
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

    /**
     * [Server.Plugin.Blocking] is a blocking [Server.Plugin] intended for use in *Java* code.
     *
     * @param executor the [Executor] to use as a [kotlinx.coroutines.CoroutineDispatcher] to run
     *   the blocking operations
     */
    abstract class Blocking(executor: Executor) : Plugin {

      private val dispatcher = executor.asCoroutineDispatcher()

      /**
       * [Server.Plugin.intercept] the [message].
       * > [interceptBlocking] is run on the [Executor] to prevent blocking [Server] coroutines.
       *
       * @param session the [Bolt.Session]
       * @param message the intercepted
       *   [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages)
       * @return the [Bolt.Message] to send
       */
      abstract fun interceptBlocking(session: String, message: Bolt.Message): Bolt.Message

      /**
       * [Server.Plugin.observe] the [event].
       * > [observeBlocking] is run on the [Executor] to prevent blocking [Server] coroutines.
       *
       * @param event the [Server.Event] that occurred
       */
      abstract fun observeBlocking(event: Event)

      /** [interceptBlocking] on the [dispatcher]. */
      final override suspend fun intercept(
        session: Bolt.Session,
        message: Bolt.Message,
      ): Bolt.Message = withContext(dispatcher) { interceptBlocking(session.id, message) }

      /** [observeBlocking] on the [dispatcher]. */
      final override suspend fun observe(event: Event) {
        withContext(dispatcher) { observeBlocking(event) }
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
   * @property address the [SocketAddress] of the proxy source/destination
   */
  sealed interface Connection {

    val address: SocketAddress

    /**
     * A client the [Server] accepted a connection from.
     *
     * @property address the [SocketAddress] of the client
     */
    @JvmRecord data class Client(override val address: SocketAddress) : Connection

    /**
     * The graph database the [Server] connected to.
     *
     * @property address the [SocketAddress] of the graph database
     */
    @JvmRecord data class Graph(override val address: SocketAddress) : Connection
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
    val sent: Bolt.Message,
  ) : Event

  /** The [Server] has stopped. */
  data object Stopped : Event

  /** Utilities to read bytes from a [ReceiveChannel]. */
  internal class Reader(
    private val channel: ReceiveChannel<ByteArray>,
    internal val allocator: ByteBufAllocator = ByteBufAllocator.DEFAULT,
  ) : AutoCloseable {

    private val buf: ByteBuf = allocator.buffer()

    suspend fun readByte(): Byte {
      receive(1)
      return buf.readByte()
    }

    suspend fun readByteArray(size: Int): ByteArray {
      if (size == 0) return ByteArray(0)
      receive(size)
      return ByteArray(size).also { buf.readBytes(it) }
    }

    suspend fun readShort(): Short {
      receive(Short.SIZE_BYTES)
      return buf.readShort()
    }

    suspend fun readInt(): Int {
      receive(Int.SIZE_BYTES)
      return buf.readInt()
    }

    override fun close() {
      buf.release()
      channel.cancel()
    }

    private suspend fun receive(size: Int) {
      if (buf.readableBytes() < size) buf.discardReadBytes()
      while (buf.readableBytes() < size) {
        val next = channel.receiveCatching().getOrNull() ?: throw EOFException("Connection closed")
        if (next.isNotEmpty()) buf.writeBytes(next)
      }
    }
  }

  /** Utilities to write bytes to a [Channel]. */
  internal class Writer(val allocator: ByteBufAllocator = ByteBufAllocator.DEFAULT) :
    AutoCloseable {

    val channel = Channel<ByteBuf>(Channel.UNLIMITED)

    suspend fun writeByteArray(bytes: ByteArray) =
      channel.send(allocator.buffer(bytes.size).writeBytes(bytes))

    suspend fun writeInt(i: Int) = channel.send(allocator.buffer(4).writeInt(i))

    override fun close() {
      channel.close()
    }
  }

  @VisibleForTesting
  @Suppress("TooManyFunctions")
  internal companion object {

    private val LOGGER = LoggerFactory.getLogger(Server::class.java)!!

    /** Run the [block] with the [context] in the [MDC]. */
    private suspend fun withLoggingContext(
      vararg context: Pair<String, String>,
      block: suspend CoroutineScope.() -> Unit,
    ) {
      val reset = context.map { (key, value) -> MDC.putCloseable(key, value) }
      val result = withContext(MDCContext() + Dispatchers.IO) { runCatching { block() } }
      reset.runCatching { forEach(MDCCloseable::close) }
      result.getOrThrow()
    }

    /**
     * Read and verify the [handshake](https://neo4j.com/docs/bolt/current/bolt/handshake/).
     *
     * @throws IllegalStateException if the handshake is invalid/unsupported
     */
    private suspend fun Reader.readHandshake(): ByteArray {
      val bytes = readByteArray(Int.SIZE_BYTES * 5)
      val buf = allocator.buffer(bytes.size)
      try {
        buf.writeBytes(bytes)
        val id = buf.readInt()
        check(id == ID) { "Unexpected identifier '0x${Integer.toHexString(id)}'" }
        val versions = Array(4) { Bolt.Version.decode(buf.readInt()) }
        check(versions.any { version -> version.major >= 5 }) {
          "None of the versions '${versions.contentToString()}' are supported"
        }
      } finally {
        buf.release()
      }
      return bytes
    }

    /** Read the [Bolt.Version]. */
    private suspend inline fun Reader.readVersion(): Bolt.Version = Bolt.Version.decode(readInt())

    /** Read the [Bolt.Version]s supported by the graph. */
    private suspend fun Reader.readVersions(): Array<Bolt.Version> {
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
    private suspend inline fun Writer.writeVersion(version: Bolt.Version) {
      writeInt(version.encode())
    }

    /** Read a [Bolt.Message] from the [Reader]. */
    private suspend fun Reader.readMessage(timeout: Duration = Duration.INFINITE): Bolt.Message {
      val buf = readChunked(timeout)
      return try {
        buf.unpack { structure() }.toMessage()
      } finally {
        buf.release()
      }
    }

    /**
     * Read a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) message from the
     * [Reader].
     */
    @VisibleForTesting
    internal suspend fun Reader.readChunked(timeout: Duration): ByteBuf {
      val buf = allocator.buffer()
      withTimeout(timeout) {
        while (true) {
          val size = readShort().toUShort().toInt()
          if (size == 0) {
            // NoOp chunk (connection keep-alive)
            if (!buf.isReadable) continue
            // Received all chunks
            break
          }
          buf.writeBytes(readByteArray(size))
        }
      }
      return buf
    }

    /** Write a [Bolt.Message] to the [Writer]. */
    private suspend fun Writer.writeMessage(
      message: Bolt.Message,
      maxChunkSize: Int = UShort.MAX_VALUE.toInt(),
    ) {
      (if (message is Bolt.Messages) message.messages else listOf(message))
        .map { msg -> msg.toStructure() }
        .map { structure -> PackStream.pack(allocator) { structure(structure) } }
        .forEach { buf -> writeChunked(buf, maxChunkSize) }
    }

    /**
     * Write a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) [message] to
     * the [Writer].
     */
    @VisibleForTesting
    internal suspend fun Writer.writeChunked(message: ByteBuf, maxChunkSize: Int) {
      try {
        while (message.isReadable) {
          val chunkSize = minOf(message.readableBytes(), maxChunkSize)
          val buf =
            allocator
              .buffer(2 + chunkSize + 2)
              .writeShort(chunkSize)
              .writeBytes(message, chunkSize)
              .writeShort(0)
          channel.send(buf)
        }
      } finally {
        message.release()
      }
    }
  }
}
