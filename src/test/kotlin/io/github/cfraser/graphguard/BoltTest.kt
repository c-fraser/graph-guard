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

import io.github.cfraser.graphguard.Bolt.readChunked
import io.github.cfraser.graphguard.Bolt.writeChunked
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

class BoltTest : FunSpec() {

  init {
    test("dechunk message") {
      val message =
          SelectorManager(coroutineContext)
              .use { selector ->
                val address = InetSocketAddress("localhost", 8787)
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
                val address = InetSocketAddress("localhost", 8787)
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
