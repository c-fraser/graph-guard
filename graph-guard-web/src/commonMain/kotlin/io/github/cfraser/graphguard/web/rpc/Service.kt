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
package io.github.cfraser.graphguard.web.rpc

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * [Service] specifies an [Rpc] interface for interactions between *graph-guard-app* and
 * *graph-guard-web*.
 */
@Rpc
interface Service {

  /** Get a [Flow] of [Message] proxied by *graph-guard*. */
  fun getMessages(): Flow<Message>

  /**
   * Get the [schema text](https://github.com/c-fraser/graph-guard?tab=readme-ov-file#schema) being
   * used by *graph-guard* to validate queries.
   */
  suspend fun getSchema(): String?

  /** Get the *graph-guard* plugins script. */
  suspend fun getPlugin(): String?

  /** Evaluate the [script] then load the *graph-guard* plugin. */
  suspend fun load(script: String?)
}

/**
 * A [Bolt](https://neo4j.com/docs/bolt/current/bolt/) message proxied by *graph-guard*.
 *
 * @property session the [Bolt session](https://neo4j.com/docs/bolt/current/bolt/message/#session)
 * @property bolt the [Bolt] message received/sent from/to the [address]
 * @property address the address of the source/destination of the [bolt]
 */
@Serializable
sealed interface Message {

  val session: String
  val bolt: Bolt
  val address: String

  /** A [bolt] received from the client. */
  @Serializable
  data class ReceivedFromClient(
    override val session: String,
    override val bolt: Bolt,
    override val address: String,
  ) : Message

  /** A [bolt] sent to the client. */
  @Serializable
  data class SentToClient(
    override val session: String,
    override val bolt: Bolt,
    override val address: String,
  ) : Message

  /** A [bolt] received from the graph. */
  @Serializable
  data class ReceivedFromGraph(
    override val session: String,
    override val bolt: Bolt,
    override val address: String,
  ) : Message

  /** A [bolt] sent to the graph. */
  @Serializable
  data class SentToGraph(
    override val session: String,
    override val bolt: Bolt,
    override val address: String,
  ) : Message

  /**
   * > Refer to [Bolt](https://github.com/c-fraser/graph-guard/blob/main/graph-guard/src/main/kotlin/io/github/cfraser/graphguard/Bolt.kt).
   */
  @Serializable
  sealed interface Bolt {

    @Serializable sealed interface Request : Bolt

    @Serializable sealed interface Response : Bolt

    @Serializable data class Hello(val extra: JsonObject) : Request

    @Serializable data object Goodbye : Request

    @Serializable data class Logon(val auth: JsonObject) : Request

    @Serializable data object Logoff : Request

    @Serializable data class Begin(val extra: JsonObject) : Request

    @Serializable data object Commit : Request

    @Serializable data object Rollback : Request

    @Serializable
    data class Route(val routing: JsonObject, val bookmarks: List<String>, val extra: JsonObject) :
      Request

    @Serializable data object Reset : Request

    @Serializable
    data class Run(val query: String, val parameters: JsonObject, val extra: JsonObject) : Request

    @Serializable data class Discard(val extra: JsonObject) : Request

    @Serializable data class Pull(val extra: JsonObject) : Request

    @Serializable data class Telemetry(val api: Long) : Request

    @Serializable data class Success(val metadata: JsonObject) : Response

    @Serializable data class Record(val data: JsonArray) : Response

    @Serializable data object Ignored : Response

    @Serializable data class Failure(val metadata: JsonObject) : Response
  }
}
