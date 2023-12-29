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

import io.github.cfraser.graphguard.Server.Companion.readChunked
import io.github.cfraser.graphguard.Server.Companion.writeChunked
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeOneOf
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.properties.Delegates.notNull
import kotlin.time.Duration.Companion.seconds
import io.ktor.network.sockets.InetSocketAddress as KInetSocketAddress

class ServerTest : FunSpec() {

  init {
    test("proxy bolt messages").config(tags = setOf(LOCAL)) {
      withNeo4j { withServer { runMoviesQueries() } }
    }

    test("observe server events").config(tags = setOf(LOCAL)) {
      val lock = Mutex()
      val clientAddresses = mutableListOf<InetSocketAddress>()
      val clientConnections by lazy { clientAddresses.map { Server.Connection.Client(it) } }
      var graphAddress by notNull<InetSocketAddress>()
      val graphConnection by lazy { Server.Connection.Graph(graphAddress) }
      val events = mutableListOf<Server.Event>()
      val observer =
          object : Server.Plugin {
            override suspend fun observe(event: Server.Event) {
              lock.withLock {
                when (event) {
                  is Server.Connected ->
                      when (event.connection) {
                        is Server.Connection.Client -> clientAddresses += event.connection.address
                        is Server.Connection.Graph -> graphAddress = event.connection.address
                      }
                  else -> {}
                }
                events += event
              }
            }
          }
      withNeo4j {
        withServer(plugin = MOVIES_GRAPH_SCHEMA.Validator() then observer) {
          GraphDatabase.driver("bolt://localhost:8787", AuthTokens.basic("neo4j", adminPassword))
              .use { driver ->
                driver.session().use { session ->
                  session.run(MoviesGraph.MATCH_TOM_HANKS).list().shouldBeEmpty()
                  session.runCatching { run("MATCH (n:N) RETURN n") }
                }
              }
        }
      }
      lock.isLocked shouldBe false
      events.forExactly(1) { it shouldBe Server.Started }
      events.filterIsInstance<Server.Connected>() shouldContainInOrder
          clientConnections.flatMap {
            listOf(Server.Connected(it), Server.Connected(graphConnection))
          }
      events.filterIsInstance<Server.Proxied>().forEach { event ->
        when (val message = event.received) {
          is Bolt.Hello,
          is Bolt.Logon,
          is Bolt.Pull,
          is Bolt.Reset -> {
            event.source.address shouldBeOneOf clientConnections.map { it.address }
            event.destination.address shouldBe graphConnection.address
            message shouldBe event.sent
          }
          is Bolt.Success -> {
            event.source.address shouldBe graphConnection.address
            event.destination.address shouldBeOneOf clientConnections.map { it.address }
            message shouldBe event.sent
          }
          is Bolt.Run ->
              if (message.query.contains("Tom Hanks")) {
                event.source.address shouldBeOneOf clientConnections.map { it.address }
                event.destination.address shouldBe graphConnection.address
                message shouldBe event.sent
              } else {
                event.source.address shouldBe event.destination.address
                event.sent.shouldBeTypeOf<Bolt.Failure>()
              }
          else -> fail("Received unexpected '$message'")
        }
      }
      events.filterIsInstance<Server.Disconnected>() shouldContainAll
          clientConnections.flatMap {
            listOf(Server.Disconnected(graphConnection), Server.Disconnected(it))
          }
      events.forExactly(1) { it shouldBe Server.Stopped }
    }

    test("dechunk message") {
      val message =
          SelectorManager(coroutineContext)
              .use { selector ->
                val address = KInetSocketAddress("localhost", 8787)
                aSocket(selector).tcp().bind(address).use { ss ->
                  async {
                        ss.accept().use { socket ->
                          socket.openReadChannel().readChunked(3.seconds)
                        }
                      }
                      .also { _ ->
                        aSocket(selector).tcp().connect(address).use { socket ->
                          val writer = socket.openWriteChannel(autoFlush = true)
                          for (size in 1..3) {
                            val data = byteArrayOfSize(size)
                            writer.writeShort(size.toShort())
                            writer.writeFully(data)
                          }
                          writer.writeFully(byteArrayOf(0x00, 0x00))
                        }
                      }
                }
              }
              .await()
      message shouldBe (byteArrayOfSize(1) + byteArrayOfSize(2) + byteArrayOfSize(3))
    }

    test("chunk message") {
      val chunked =
          SelectorManager(coroutineContext)
              .use { selector ->
                val address = KInetSocketAddress("localhost", 8787)
                aSocket(selector).tcp().bind(address).use { ss ->
                  async {
                        val buffer = ByteBuffer.allocate(13)
                        ss.accept().use { socket ->
                          withTimeout(3.seconds) { socket.openReadChannel().readFully(buffer) }
                        }
                        buffer
                      }
                      .also { _ ->
                        aSocket(selector).tcp().connect(address).use { socket ->
                          socket
                              .openWriteChannel(autoFlush = true)
                              .writeChunked(byteArrayOfSize(5), 3)
                        }
                      }
                }
              }
              .await()
      chunked.array() shouldBe
          (byteArrayOf(0x0, 0x3) +
              byteArrayOfSize(3) +
              byteArrayOf(0x00, 0x00) +
              byteArrayOf(0x0, 0x2) +
              byteArrayOfSize(2) +
              byteArrayOf(0x00, 0x00))
    }
  }
}
