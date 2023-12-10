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

/** [Bolt](https://neo4j.com/docs/bolt/current/bolt/) message data. */
object Bolt {

  /**
   * The
   * [Bolt identification](https://neo4j.com/docs/bolt/current/bolt/handshake/#_bolt_identification)
   * bytes.
   */
  internal const val ID = 0x6060b017

  /** A [Bolt version](https://neo4j.com/docs/bolt/current/bolt-compatibility/). */
  internal data class Version(val major: Int, val minor: Int) {

    constructor(bytes: Int) : this(bytes and 0x000000ff, (bytes shr 8) and 0x000000ff)

    fun bytes(): Int {
      val minor = minor shl 8
      return minor or major
    }

    override fun toString(): String {
      return "$major.$minor"
    }
  }

  /** A [Bolt message](https://neo4j.com/docs/bolt/current/bolt/message/#messages). */
  sealed interface Message

  /** A [Message] received from the proxy client. */
  sealed interface Request : Message

  /** A [Message] received from the graph server. */
  sealed interface Response : Message

  /** The [HELLO](https://neo4j.com/docs/bolt/current/bolt/message/#messages-hello) message. */
  data class Hello(val extra: Map<String, Any?>) : Request

  /** The [GOODBYE](https://neo4j.com/docs/bolt/current/bolt/message/#messages-goodbye) message. */
  data object Goodbye : Request

  /** The [LOGON](https://neo4j.com/docs/bolt/current/bolt/message/#messages-logon) message. */
  data class Logon(val auth: Map<String, Any?>) : Request

  /** The [LOGOFF](https://neo4j.com/docs/bolt/current/bolt/message/#messages-logoff) message. */
  data object Logoff : Request

  /** The [BEGIN](https://neo4j.com/docs/bolt/current/bolt/message/#messages-begin) message. */
  data class Begin(val extra: Map<String, Any?>) : Request

  /** The [COMMIT](https://neo4j.com/docs/bolt/current/bolt/message/#messages-commit) message. */
  data object Commit : Request

  /**
   * The [ROLLBACK](https://neo4j.com/docs/bolt/current/bolt/message/#messages-rollback) message.
   */
  data object Rollback : Request

  /** The [HELLO](https://neo4j.com/docs/bolt/current/bolt/message/#messages-reset) message. */
  data object Reset : Request

  /**
   * The [RUN](https://neo4j.com/docs/bolt/current/bolt/message/#messages-run) message signature.
   */
  data class Run(
      val query: String,
      val parameters: Map<String, Any?>,
      val extra: Map<String, Any?>
  ) : Request

  /** The [DISCARD](https://neo4j.com/docs/bolt/current/bolt/message/#messages-discard) message. */
  data class Discard(val extra: Map<String, Any?>) : Request

  /** The [PULL](https://neo4j.com/docs/bolt/current/bolt/message/#messages-pull) message. */
  data class Pull(val extra: Map<String, Any?>) : Request

  /**
   * The [TELEMETRY](https://neo4j.com/docs/bolt/current/bolt/message/#messages-telemetry) message.
   */
  data class Telemetry(val api: Long) : Request

  /** The [SUCCESS](https://neo4j.com/docs/bolt/current/bolt/message/#messages-success) message. */
  data class Success(val metadata: Map<String, Any?>) : Response

  /** The [RECORD](https://neo4j.com/docs/bolt/current/bolt/message/#messages-record) message. */
  data class Record(val data: List<Any?>) : Response

  /** The [IGNORED](https://neo4j.com/docs/bolt/current/bolt/message/#messages-ignored) message. */
  data object Ignored : Response

  /** The [FAILURE](https://neo4j.com/docs/bolt/current/bolt/message/#messages-failure) message. */
  data class Failure(val metadata: Map<String, Any?>) : Response

  /**
   * Convert the [PackStream.Structure] to a [Message].
   *
   * Throws [IllegalStateException] if the [PackStream.Structure.id] doesn't correspond to a
   * [Bolt.Message].
   */
  @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
  internal fun PackStream.Structure.toMessage(): Message {
    return when (id) {
      0x11.toByte() -> Begin(fields[0] as Map<String, Any?>)
      0x12.toByte() -> Commit
      0x2f.toByte() -> Discard(fields[0] as Map<String, Any?>)
      0x7f.toByte() -> Failure(fields[0] as Map<String, Any?>)
      0x02.toByte() -> Goodbye
      0x01.toByte() -> Hello(fields[0] as Map<String, Any?>)
      0x7e.toByte() -> Ignored
      0x6b.toByte() -> Logoff
      0x6a.toByte() -> Logon(fields[0] as Map<String, Any?>)
      0x3f.toByte() -> Pull(fields[0] as Map<String, Any?>)
      0x71.toByte() -> Record(fields[0] as List<Any?>)
      0x0f.toByte() -> Reset
      0x13.toByte() -> Rollback
      0x10.toByte() ->
          Run(fields[0] as String, fields[1] as Map<String, Any?>, fields[2] as Map<String, Any?>)
      0x70.toByte() -> Success(fields[0] as Map<String, Any?>)
      0x54.toByte() -> Telemetry(fields[0] as Long)
      else -> error("Unknown message '$this'")
    }
  }

  /** Convert the [Message] to a [PackStream.Structure]. */
  @Suppress("CyclomaticComplexMethod")
  internal fun Message.toStructure(): PackStream.Structure {
    val (id, fields) =
        when (this) {
          is Begin -> 0x11.toByte() to listOf(extra)
          Commit -> 0x12.toByte() to emptyList()
          is Discard -> 0x2f.toByte() to listOf(extra)
          is Failure -> 0x7f.toByte() to listOf(metadata)
          Goodbye -> 0x02.toByte() to emptyList()
          is Hello -> 0x01.toByte() to listOf(extra)
          Ignored -> 0x7e.toByte() to emptyList()
          Logoff -> 0x6b.toByte() to emptyList()
          is Logon -> 0x6a.toByte() to listOf(auth)
          is Pull -> 0x3f.toByte() to listOf(extra)
          is Record -> 0x71.toByte() to listOf(data)
          Reset -> 0x0f.toByte() to emptyList()
          Rollback -> 0x13.toByte() to emptyList()
          is Run -> 0x10.toByte() to listOf(query, parameters, extra)
          is Success -> 0x70.toByte() to listOf(metadata)
          is Telemetry -> 0x54.toByte() to listOf(api)
        }
    return PackStream.Structure(id, fields)
  }
}
