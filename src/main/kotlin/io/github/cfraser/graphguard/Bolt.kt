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

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlinx.coroutines.withTimeout

/** Utilities for reading and writing [Bolt](https://neo4j.com/docs/bolt/current/bolt/) 5+ data. */
internal object Bolt {

  /**
   * The
   * [Bolt identification](https://neo4j.com/docs/bolt/current/bolt/handshake/#_bolt_identification)
   * bytes.
   */
  private const val ID = 0x6060b017

  /** The [Message]s indexed by [Message.signature]. */
  val MESSAGES = Message.entries.associateBy { it.signature }

  /**
   * A [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages).
   *
   * @property signature the [Byte] which identifies the message
   */
  @Suppress("unused")
  enum class Message(val signature: Byte) {

    /** The [HELLO](https://neo4j.com/docs/bolt/current/bolt/message/#messages-hello) message. */
    HELLO(0x01),

    /**
     * The [GOODBYE](https://neo4j.com/docs/bolt/current/bolt/message/#messages-goodbye) message.
     */
    GOODBYE(0x02),

    /** The [LOGON](https://neo4j.com/docs/bolt/current/bolt/message/#messages-logon) message. */
    LOGON(0x6a),

    /** The [LOGOFF](https://neo4j.com/docs/bolt/current/bolt/message/#messages-logoff) message. */
    LOGOFF(0x6b),

    /** The [BEGIN](https://neo4j.com/docs/bolt/current/bolt/message/#messages-begin) message. */
    BEGIN(0x11),

    /** The [COMMIT](https://neo4j.com/docs/bolt/current/bolt/message/#messages-commit) message. */
    COMMIT(0x12),

    /**
     * The [ROLLBACK](https://neo4j.com/docs/bolt/current/bolt/message/#messages-rollback) message.
     */
    ROLLBACK(0x13),

    /** The [HELLO](https://neo4j.com/docs/bolt/current/bolt/message/#messages-reset) message. */
    RESET(0x0f),

    /**
     * The [RUN](https://neo4j.com/docs/bolt/current/bolt/message/#messages-run) message signature.
     */
    RUN(0x10),

    /**
     * The [DISCARD](https://neo4j.com/docs/bolt/current/bolt/message/#messages-discard) message.
     */
    DISCARD(0x2f),

    /** The [PULL](https://neo4j.com/docs/bolt/current/bolt/message/#messages-pull) message. */
    PULL(0x3f),

    /**
     * The [SUCCESS](https://neo4j.com/docs/bolt/current/bolt/message/#messages-success) message.
     */
    SUCCESS(0x70),

    /** The [RECORD](https://neo4j.com/docs/bolt/current/bolt/message/#messages-record) message. */
    RECORD(0x71),

    /**
     * The [IGNORED](https://neo4j.com/docs/bolt/current/bolt/message/#messages-ignored) message.
     */
    IGNORED(0x7e),

    /**
     * The [FAILURE](https://neo4j.com/docs/bolt/current/bolt/message/#messages-failure) message.
     */
    FAILURE(0x7f)
  }

  /** A [Bolt version](https://neo4j.com/docs/bolt/current/bolt-compatibility/). */
  data class Version(val major: Int, val minor: Int) {

    constructor(bytes: Int) : this(bytes and 0x000000ff, (bytes shr 8) and 0x000000ff)

    fun bytes(): Int {
      val minor = minor shl 8
      return minor or major
    }

    override fun toString(): String {
      return "$major.$minor"
    }
  }

  /** Read and verify the [handshake](https://neo4j.com/docs/bolt/current/bolt/handshake/). */
  suspend fun ByteReadChannel.verifyHandshake(): ByteArray {

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
          val version = Version(buffer.getInt())
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
  suspend fun ByteReadChannel.readVersion(): Version {
    return Version(readInt())
  }

  /** Read a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) message. */
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

  /** Write a [chunked](https://neo4j.com/docs/bolt/current/bolt/message/#chunking) [message]. */
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
