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
package io.github.cfraser.graphguard.rpc

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable

/**
 * [WebService] specifies an [Rpc] interface for interactions between *graph-guard-app* and
 * *graph-guard-web*.
 */
@Rpc
interface WebService {

  /**
   * Get a [Flow] of [Bolt](https://neo4j.com/docs/bolt/current/bolt/) messages proxied by
   * *graph-guard*.
   */
  fun getMessages(): Flow<ProxiedMessage>
}

/**
 * A [Bolt](https://neo4j.com/docs/bolt/current/bolt/) message proxied from the [source] to the
 * [destination].
 *
 * @property session the [Bolt session](https://neo4j.com/docs/bolt/current/bolt/message/#session)
 * @property source the *connection* that sent the [received] message
 * @property received the message received from the [source]
 * @property destination the *connection* that received the [sent] message
 * @property sent the message sent to the [destination]
 */
@Serializable
data class ProxiedMessage(
  val session: String,
  val source: String,
  val received: String,
  val destination: String,
  val sent: String,
)
