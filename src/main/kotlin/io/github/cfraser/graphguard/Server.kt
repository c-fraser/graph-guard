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
import io.github.cfraser.graphguard.Server.Handler
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.net.URI
import java.nio.ByteBuffer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.MDC.MDCCloseable

/**
 * [Server] proxies [Bolt](https://neo4j.com/docs/bolt/current/bolt/) data to a
 * [Neo4j](https://neo4j.com/) (5+ compatible) database and performs dynamic message transformation
 * via the [handler].
 *
 * @param hostname the hostname to bind the [Server] to
 * @param port the port to bind the [Server] to
 * @param parallelism the number of parallel coroutines used by the [Server]
 * @property graphUri the [URI] of the graph to proxy data to
 * @property handler the [Server.Handler] to use to [Server.Handler.handle] proxied messages
 */
class Server(
    private val graphUri: URI,
    private val handler: Handler,
    hostname: String = "localhost",
    port: Int = 8787,
    parallelism: Int? = null
) : Runnable {

  /**
   * Initialize a [Server] that performs realtime [schema] validation of the intercepted queries.
   *
   * @param graphUri the [URI] of the graph to proxy data to
   * @param schema the [Schema] to use to validate intercepted queries
   * @param cacheSize the size of the [Schema.Validator] cache
   * @param hostname the hostname to bind the [Server] to
   * @param port the port to bind the [Server] to
   * @param parallelism the number of parallel coroutines used by the [Server]
   */
  constructor(
      graphUri: URI,
      schema: Schema,
      cacheSize: Long? = null,
      hostname: String = "localhost",
      port: Int = 8787,
      parallelism: Int? = null
  ) : this(graphUri, schema.Validator(cacheSize), hostname, port, parallelism)

  /** The [SocketAddress] of the server. */
  private val address = InetSocketAddress(hostname, port)

  /**
   * The lazily initialized [CoroutineContext] to use for the [Server] coroutines.
   * > Inherits MDC of the calling thread.
   */
  private val context by lazy {
    var dispatcher = Dispatchers.IO
    if (parallelism != null)
        dispatcher =
            @OptIn(ExperimentalCoroutinesApi::class) dispatcher.limitedParallelism(parallelism)
    dispatcher + MDCContext()
  }

  /**
   * Run the proxy server on the [address], connecting to the [graphUri].
   *
   * [Server.run] blocks indefinitely. To terminate the server, [java.lang.Thread.interrupt] the
   * blocked thread.
   */
  override fun run() {
    withLoggingContext("graph-guard.server" to "$address", "graph-guard.graph" to "$graphUri") {
      runBlocking(context) {
        SelectorManager(context).use { selector ->
          bind(selector).use { server ->
            LOGGER.info("Running proxy server on '{}'", server.localAddress)
            while (isActive) {
              try {
                val clientConnection = server.accept()
                val clientAddress = clientConnection.remoteAddress
                LOGGER.info("Accepted client connection from '{}'", clientAddress)
                withLoggingContext("graph-guard.client" to "$clientAddress") {
                  launch(MDCContext()) {
                    val graphConnection = connect(selector)
                    val graphAddress = graphConnection.remoteAddress
                    LOGGER.info("Connected to graph at '{}'", graphAddress)
                    clientConnection.open { clientReader, clientWriter ->
                      graphConnection.open { graphReader, graphWriter ->
                        session(
                            clientAddress,
                            clientReader,
                            clientWriter,
                            graphAddress,
                            graphReader,
                            graphWriter)
                      }
                      LOGGER.info("Closed graph connection to '{}'", graphAddress)
                    }
                    LOGGER.info("Closed client connection to '{}'", clientAddress)
                  }
                }
              } catch (thrown: Throwable) {
                when (thrown) {
                  is CancellationException -> LOGGER.warn("Proxy connection cancelled", thrown)
                  else -> LOGGER.error("Proxy connection failure", thrown)
                }
              }
            }
          }
          LOGGER.info("Terminated proxy server")
        }
      }
    }
  }

  /** Bind the proxy [ServerSocket] to the [address]. */
  private fun bind(selector: SelectorManager): ServerSocket {
    return aSocket(selector).tcp().bind(address)
  }

  /** Connect a [Socket] to the [graphUri]. */
  private suspend fun connect(selector: SelectorManager): Socket {
    val socket = aSocket(selector).tcp().connect(InetSocketAddress(graphUri.host, graphUri.port))
    if ("+s" in graphUri.scheme) return socket.tls(coroutineContext = context)
    return socket
  }

  /**
   * Manage a [Bolt (proxy) session](https://neo4j.com/docs/bolt/current/bolt/message/#session)
   * between the *client* and *graph*.
   */
  private suspend fun session(
      clientAddress: SocketAddress,
      clientReader: ByteReadChannel,
      clientWriter: ByteWriteChannel,
      graphAddress: SocketAddress,
      graphReader: ByteReadChannel,
      graphWriter: ByteWriteChannel
  ) {
    coroutineScope {
      val handshake = clientReader.verifyHandshake()
      LOGGER.debug("Read handshake from {} '{}'", clientAddress, handshake)
      graphWriter.writeFully(handshake)
      LOGGER.debug("Wrote handshake to {}", graphAddress)
      val version = graphReader.readVersion()
      LOGGER.debug("Read version from {} '{}'", graphAddress, version)
      clientWriter.writeInt(version.bytes())
      LOGGER.debug("Wrote version to {}", clientAddress)
      val requestWriter = graphAddress to graphWriter
      val responseWriter = clientAddress to clientWriter
      try {
        val incoming =
            proxy(clientAddress, clientReader) {
              if (it is Bolt.Request) requestWriter else responseWriter
            }
        val outgoing =
            proxy(graphAddress, graphReader) {
              if (it is Bolt.Response) responseWriter else requestWriter
            }
        select {
          incoming.onJoin { outgoing.cancel() }
          outgoing.onJoin { incoming.cancel() }
        }
      } catch (thrown: Throwable) {
        when (thrown) {
          is CancellationException -> LOGGER.warn("Proxy session cancelled", thrown)
          else -> LOGGER.error("Proxy session failure", thrown)
        }
      } finally {
        graphWriter.runCatching { withTimeout(3.seconds) { writeMessage(Bolt.Goodbye) } }
      }
    }
  }

  /**
   * Proxy the *handled*
   * [Bolt messages](https://neo4j.com/docs/bolt/current/bolt/message/#message-exchange) from the
   * [source] to the *resolved destination*.
   * > Intercept [Bolt.Goodbye] and [cancel] the [CoroutineScope] to end the session.
   */
  private fun CoroutineScope.proxy(
      source: SocketAddress,
      reader: ByteReadChannel,
      resolver: (Bolt.Message) -> Pair<SocketAddress, ByteWriteChannel>
  ): Job = launch {
    while (isActive) {
      var message =
          try {
            reader.readMessage()
          } catch (_: Throwable) {
            break
          }
      LOGGER.debug("Read '{}' from {}", message, source)
      message =
          try {
            handler.handle(message)
          } catch (thrown: Throwable) {
            LOGGER.error("Failed to handle '{}'", message, thrown)
            message
          }
      if (message == Bolt.Goodbye) {
        cancel("${Bolt.Goodbye}")
        return@launch
      }
      val (destination, writer) = resolver(message)
      writer.writeMessage(message)
      LOGGER.debug("Wrote '{}' to {}", message, destination)
    }
  }

  /**
   * [Server.Handler] handles
   * [Bolt messages](https://neo4j.com/docs/bolt/current/bolt/message/#messages).
   */
  fun interface Handler {

    /**
     * Handle the [message]. If the returned, optionally transformed, [Bolt.Message] is a
     * [Bolt.Request] then it's sent to the graph server, otherwise the [Bolt.Response] is sent to
     * the proxy client.
     *
     * [Handler.handle] will (almost certainly) be executed concurrently, via concurrent proxy
     * sessions managed by the [Server]. Thus, perform any necessary synchronization in the
     * [Handler.handle] implementation.
     */
    suspend fun handle(message: Bolt.Message): Bolt.Message

    companion object {

      /**
       * Chain `this` [Server.Handler] with [that].
       * > `this` [Server.Handler.handle] is invoked before [that].
       */
      operator fun Handler.plus(that: Handler): Handler {
        return Handler { message -> that.handle(this.handle(message)) }
      }
    }
  }

  @VisibleForTesting
  internal companion object {

    private val LOGGER = LoggerFactory.getLogger(Server::class.java)!!

    /** Run the [block] with the [context] in the [MDC]. */
    @OptIn(ExperimentalContracts::class)
    private fun withLoggingContext(vararg context: Pair<String, String>, block: () -> Unit) {
      contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
      val reset = context.map { (key, value) -> MDC.putCloseable(key, value) }
      val result = runCatching(block)
      reset.runCatching { forEach(MDCCloseable::close) }
      result.getOrThrow()
    }

    /** Use the opened [ByteReadChannel] and [ByteWriteChannel] for the [Socket]. */
    @OptIn(ExperimentalContracts::class)
    private suspend fun Socket.open(block: suspend (ByteReadChannel, ByteWriteChannel) -> Unit) {
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
        maxChunkSize: Int = UShort.MAX_VALUE.toInt()
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
