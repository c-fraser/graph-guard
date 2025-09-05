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
import io.github.cfraser.graphguard.knit.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress as KInetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeShort
import java.net.URI
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.neo4j.configuration.connectors.BoltConnector
import org.neo4j.harness.Neo4jBuilders

class ServerTest : FunSpec() {

  init {
    test("proxy bolt messages") { withNeo4j { withServer(block = ::runMoviesQueries) } }

    test("concurrent connections") {
      val neo4j = Neo4jBuilders.newInProcessBuilder().build()
      val server = Server(neo4j.boltURI())
      try {
        server.start()
        coroutineScope {
          Array(17) { _ -> launch { server.driver.use { driver -> runMoviesQueries(driver) } } }
            .toList()
            .joinAll()
        }
      } finally {
        server.close()
        neo4j.close()
      }
    }

    test("proxy bolt messages with async plugin") {
      withNeo4j {
        val server = JServer.initialize(boltURI())
        server.test { server.driver.use(::runMoviesQueries) }
      }
    }

    test("dechunk message") {
      val message =
        SelectorManager(coroutineContext)
          .use { selector ->
            val address = KInetSocketAddress("localhost", 8787)
            aSocket(selector).tcp().bind(address).use { serverSocket ->
              async {
                  serverSocket.accept().use { socket ->
                    socket.openReadChannel().readChunked(3.seconds)
                  }
                }
                .also { _ ->
                  aSocket(selector).tcp().connect(address).use { socket ->
                    val writer = socket.openWriteChannel(autoFlush = true)
                    for (size in 1..3) {
                      val data = PackStreamTest.byteArrayOfSize(size)
                      writer.writeShort(size.toShort())
                      writer.writeByteArray(data)
                    }
                    writer.writeByteArray(byteArrayOf(0x00, 0x00))
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
            aSocket(selector).tcp().bind(address).use { serverSocket ->
              async {
                  val buffer = ByteBuffer.allocate(13)
                  serverSocket.accept().use { socket ->
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

    test("encrypted connection to graph") {
      Neo4jBuilders.newInProcessBuilder()
        .withConfig(BoltConnector.enabled, true)
        .withConfig(BoltConnector.encryption_level, BoltConnector.EncryptionLevel.REQUIRED)
        .build()
        .use { neo4j ->
          val boltURI = neo4j.boltURI().let { uri -> URI("bolt+ssc://${uri.host}:${uri.port}/") }
          val server = Server(boltURI, trustManager = InsecureTrustManager)
          server.test { server.driver.use { driver -> runMoviesQueries(driver) } }
        }
    }

    xtest("measure proxy server latency").config(tags = setOf(LOCAL)) {
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

  /**
   * [InsecureTrustManager] is a [X509TrustManager] with certificate validation disabled.
   * > [InsecureTrustManager] can be used, perhaps naively, to trust self-signed certificates.
   */
  @Suppress("EmptyFunctionBlock")
  private object InsecureTrustManager : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
}
