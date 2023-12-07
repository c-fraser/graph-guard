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

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.github.cfraser.graphguard.Bolt.readChunked
import io.github.cfraser.graphguard.Bolt.readVersion
import io.github.cfraser.graphguard.Bolt.verifyHandshake
import io.github.cfraser.graphguard.Bolt.writeChunked
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
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.MDC.MDCCloseable

/**
 * [Server] proxies [Bolt](https://neo4j.com/docs/bolt/current/bolt/) 5+ messages to a
 * [Neo4j](https://neo4j.com/) (compatible) database and performs realtime schema validation of the
 * intercepted queries.
 *
 * @property address the [SocketAddress] to bind to
 * @property graphUri the [URI] of the graph to proxy data to
 * @property schema the [Schema] to use to validate intercepted queries
 * @property serverContext the lazily initialized [CoroutineContext] to use for the [Server]
 *   coroutines
 * @property validatedCache a [LoadingCache] of validated *Cypher* queries
 */
class Server
internal constructor(
    private val address: SocketAddress,
    private val graphUri: URI,
    private val schema: Schema,
    private val serverContext: Lazy<CoroutineContext>,
    private val validatedCache: LoadingCache<Pair<String, Map<String, Any?>>, Schema.InvalidQuery?>
) : Runnable {

  /**
   * Run the proxy server on the [address], connecting to the [graphUri].
   *
   * [Server.run] blocks indefinitely. To terminate the server, [java.lang.Thread.interrupt] the
   * blocked thread.
   */
  override fun run() {
    withLoggingContext("graph-guard.server" to "$address", "graph-guard.graph" to "$graphUri") {
      runBlocking(serverContext.value) {
        SelectorManager(serverContext.value).use { selector ->
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
    if ("+s" in graphUri.scheme) return socket.tls(coroutineContext = serverContext.value)
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
      try {
        val incoming =
            proxy(
                clientAddress,
                clientReader,
                graphAddress,
                graphWriter,
                GoodbyeHandler then RunHandler(clientAddress, clientWriter))
        val outgoing = proxy(graphAddress, graphReader, clientAddress, clientWriter)
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
        graphWriter.runCatching {
          withTimeout(3.seconds) {
            writeMessage(PackStream.Structure(Bolt.Message.GOODBYE.signature, emptyList()))
          }
        }
      }
    }
  }

  /**
   * [Server.Handler] handles
   * [Bolt messages](https://neo4j.com/docs/bolt/current/bolt/message/#messages).
   */
  private fun interface Handler {

    /**
     * Handle the [message]. Return `true` if intercepted message was handled, otherwise return
     * `false` to defer handling.
     */
    suspend fun handle(message: PackStream.Structure): Boolean
  }

  /** Intercept [Bolt.Message.GOODBYE] and [cancel] the [CoroutineScope] to end the session. */
  private object GoodbyeHandler : Handler {

    override suspend fun handle(message: PackStream.Structure): Boolean {
      if (message.signature != Bolt.Message.GOODBYE.signature) return false
      coroutineScope { cancel("${Bolt.Message.GOODBYE} message received") }
      return true
    }
  }

  /**
   * [RunHandler] is a [Server.Handler] that validates the query and parameters in a
   * [Bolt.Message.RUN] message. If the data in the [PackStream.Structure] is invalid, according to
   * the [schema], then respond to the [destination], via the [writer], with a
   * [Bolt.Message.FAILURE] message.
   */
  private inner class RunHandler(
      private val destination: SocketAddress,
      private val writer: ByteWriteChannel
  ) : Handler {

    override suspend fun handle(message: PackStream.Structure): Boolean {
      if (message.signature != Bolt.Message.RUN.signature) return false
      val query = (message.fields.getOrNull(0) as? String) ?: return false
      val parameters =
          @Suppress("UNCHECKED_CAST") (message.fields.getOrNull(1) as? Map<String, Any?>)
              ?: return false
      val invalid = validatedCache[query to parameters] ?: return false
      LOGGER.warn("Cypher query '{} {}' is invalid", query, parameters)
      val failure =
          PackStream.Structure(
              Bolt.Message.FAILURE.signature,
              listOf(mapOf("code" to "GraphGuard.Invalid.Query", "message" to invalid.message)))
      writer.writeMessage(failure)
      LOGGER.debug("Wrote {} to {} '{}'", Bolt.Message.FAILURE, destination, failure)
      return true
    }
  }

  companion object {

    /**
     * Create a [Server] instance.
     *
     * @param hostname the [InetSocketAddress.hostname] to bind to
     * @param port the [InetSocketAddress.port] to bind to
     * @param graphUri the [URI] of the graph to proxy data to
     * @param schema the [Schema] to use to validate intercepted queries
     * @param parallelism the number of parallel coroutines used by the [Server]
     * @param queryCacheSize the maximum size of the cache for validated queries
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        hostname: String,
        port: Int,
        graphUri: URI,
        schema: Schema,
        parallelism: Int? = null,
        queryCacheSize: Int = 1024
    ): Server {
      val address = InetSocketAddress(hostname, port)
      var dispatcher = Dispatchers.IO
      if (parallelism != null)
          dispatcher =
              @OptIn(ExperimentalCoroutinesApi::class) dispatcher.limitedParallelism(parallelism)
      val cache =
          Caffeine.newBuilder().maximumSize(queryCacheSize.toLong()).build<
              Pair<String, Map<String, Any?>>, Schema.InvalidQuery?> { (query, parameters) ->
            schema.validate(query, parameters)
          }
      return Server(address, graphUri, schema, lazy { dispatcher + MDCContext() }, cache)
    }

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

    /** Chain `this` [Server.Handler] with [that]. */
    private infix fun Handler.then(that: Handler): Handler {
      return Handler { message -> this.handle(message) || that.handle(message) }
    }

    /**
     * Proxy the [Bolt messages](https://neo4j.com/docs/bolt/current/bolt/message/#message-exchange)
     * from the [source] to the [destination] via the [reader] and [writer].
     */
    private fun CoroutineScope.proxy(
        source: SocketAddress,
        reader: ByteReadChannel,
        destination: SocketAddress,
        writer: ByteWriteChannel,
        handler: Handler = Handler { false }
    ): Job = launch {
      while (isActive) {
        val message =
            try {
              reader.readMessage()
            } catch (_: Throwable) {
              break
            }
        LOGGER.debug("Read {} from {} '{}'", Bolt.MESSAGES[message.signature], source, message)
        if (handler.handle(message)) continue
        writer.writeMessage(message)
        LOGGER.debug("Wrote {} to {} '{}'", Bolt.MESSAGES[message.signature], destination, message)
      }
    }

    /**
     * Read a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking)
     * [PackStream.Structure].
     */
    private suspend fun ByteReadChannel.readMessage(
        timeout: Duration = Duration.INFINITE
    ): PackStream.Structure {
      return readChunked(timeout).unpack { structure() }
    }

    /**
     * Write a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking)
     * [PackStream.Structure].
     */
    private suspend fun ByteWriteChannel.writeMessage(
        message: PackStream.Structure,
        maxChunkSize: Int = UShort.MAX_VALUE.toInt()
    ) {
      writeChunked(PackStream.pack { structure(message) }, maxChunkSize)
    }
  }
}
