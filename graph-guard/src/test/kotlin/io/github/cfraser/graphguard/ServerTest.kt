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
import io.github.cfraser.graphguard.knit.use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import io.ktor.network.sockets.InetSocketAddress as KInetSocketAddress

class ServerTest : FunSpec() {

  init {
    test("TLS unsupported") {
      shouldThrow<IllegalStateException> { Server(URI("bolt+s://localhost:7687")) }
    }

    test("proxy bolt messages") { withNeo4j { withServer(block = ::runMoviesQueries) } }

    test("proxy bolt messages with async plugin") {
      withNeo4j {
        val server = Utils.server(boltURI())
        server.use { server.driver.use(::runMoviesQueries) }
      }
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
                            val data = PackStreamTest.byteArrayOfSize(size)
                            writer.writeShort(size.toShort())
                            writer.writeFully(data)
                          }
                          writer.writeFully(byteArrayOf(0x00, 0x00))
                        }
                      }
                }
              }
              .await()
      message shouldBe
          (PackStreamTest.byteArrayOfSize(1) +
              PackStreamTest.byteArrayOfSize(2) +
              PackStreamTest.byteArrayOfSize(3))
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
                              .writeChunked(PackStreamTest.byteArrayOfSize(5), 3)
                        }
                      }
                }
              }
              .await()
      chunked.array() shouldBe
          (byteArrayOf(0x0, 0x3) +
              PackStreamTest.byteArrayOfSize(3) +
              byteArrayOf(0x00, 0x00) +
              byteArrayOf(0x0, 0x2) +
              PackStreamTest.byteArrayOfSize(2) +
              byteArrayOf(0x00, 0x00))
    }

    test("measure proxy server latency").config(tags = setOf(LOCAL)) {
      val times = mutableListOf<Long>()
      val proxyTimes = mutableListOf<Long>()

      for (i in 0..7) {
        val time = withNeo4j {
          driver.use { driver -> measureTimeMillis { runMoviesQueries(driver) } }
        }
        val proxyTime = withNeo4j {
          withServer { driver -> measureTimeMillis { runMoviesQueries(driver) } }
        }
        if (i <= 2) continue
        times += time
        proxyTimes += proxyTime
      }

      // time with proxy server - time communicating directly with neo4j = proxy server latency
      println("Proxy server latency = ${proxyTimes.average() - times.average()}ms")
    }
  }
}
