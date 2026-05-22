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

import io.github.cfraser.graphguard.PackStreamTest.Companion.toByteArray
import io.github.cfraser.graphguard.Server.Companion.readChunked
import io.github.cfraser.graphguard.Server.Companion.writeChunked
import io.github.cfraser.graphguard.knit.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.ssl.SslContextBuilder
import io.netty.pkitesting.CertificateBuilder
import java.net.URI
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.neo4j.configuration.connectors.BoltConnector
import org.neo4j.configuration.helpers.SocketAddress
import org.neo4j.harness.Neo4jBuilders

class ServerTest : FunSpec() {

  init {
    test("proxy bolt messages via IP socket") {
      withNeo4j { withServer(block = ::runMoviesQueries) }
    }

    test("proxy bolt messages via unix domain socket") {
      val udsPath = createTempFile().also(Path::deleteExisting)
      withNeo4j({
        // disable TCP bolt
        withConfig(BoltConnector.listen_address, SocketAddress("localhost", 0))
          // enable unix socket
          .withConfig(BoltConnector.enable_unix_socket, true)
          // specify socket path
          .withConfig(BoltConnector.unix_socket_path, udsPath)
      }) {
        withServer(block = ::runMoviesQueries)
      }
    }

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

    test("proxy bolt messages with sync plugin") {
      withNeo4j {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = ServerFactory.init(boltURI(), executor)
        try {
          server.test { server.driver.use(::runMoviesQueries) }
        } finally {
          executor.shutdown()
        }
      }
    }

    test("dechunk message") {
      val channel = Channel<ByteArray>(Channel.UNLIMITED)
      Server.Reader(channel).use { reader ->
        coroutineScope {
          launch {
            for (size in 1..3) {
              channel.send(byteArrayOf(0x0, size.toByte()))
              channel.send(PackStreamTest.byteArrayOfSize(size))
            }
            channel.send(byteArrayOf(0x0, 0x0))
            channel.close()
          }
          val message = reader.readChunked(3.seconds)
          message.toByteArray() shouldBe
            (PackStreamTest.byteArrayOfSize(1) +
              PackStreamTest.byteArrayOfSize(2) +
              PackStreamTest.byteArrayOfSize(3))
        }
      }
    }

    test("chunk message") {
      val writer = Server.Writer()
      writer.writeChunked(Unpooled.wrappedBuffer(PackStreamTest.byteArrayOfSize(5)), 3)
      writer.close()
      val result =
        buildList { for (buf in writer.channel) addAll(buf.toByteArray().toList()) }.toByteArray()
      result shouldBe
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

    test("encrypted connection from client") {
      withNeo4j {
        val bundle =
          CertificateBuilder()
            .subject("CN=localhost")
            .setIsCertificateAuthority(true)
            .buildSelfSigned()
        val sslContext =
          SslContextBuilder.forServer(bundle.toTempCertChainPem(), bundle.toTempPrivateKeyPem())
            .build()
        val server = Server(boltURI(), sslContext = sslContext)
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
