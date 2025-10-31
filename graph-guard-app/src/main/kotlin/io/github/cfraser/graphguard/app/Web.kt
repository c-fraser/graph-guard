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
package io.github.cfraser.graphguard.app

import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.app.Command.Output
import io.github.cfraser.graphguard.app.Web.PluginLoader.mutex
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.web.rpc.Message
import io.github.cfraser.graphguard.web.rpc.Service
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Start the [Web] application.
 *
 * @property port the port number to bind the [Web.server] to
 * @property schema the schema text used to validate queries
 */
internal class Web(val port: Int, private val schema: Lazy<String?>) :
  Output, Server.Plugin, Service {

  val server =
    embeddedServer(Netty, port) {
      install(Krpc)
      routing {
        staticResources("/", "static")
        rpc("/rpc") {
          rpcConfig { serialization { json { classDiscriminator = "type" } } }
          registerService<Service> { this@Web }
        }
      }
    }

  private val messages = MutableSharedFlow<Message>(replay = 2048)

  override suspend fun intercept(session: Bolt.Session, message: Bolt.Message) = message

  override suspend fun observe(event: Server.Event) {
    if (event !is Server.Proxied) return
    if (event.source is Server.Connection.Client)
      event.received.toRpc().forEach { message ->
        messages.emit(
          Message.ReceivedFromClient(event.session.id, message, "${event.source.address}")
        )
      }
    if (event.destination is Server.Connection.Client)
      event.sent.toRpc().forEach { message ->
        messages.emit(
          Message.SentToClient(event.session.id, message, "${event.destination.address}")
        )
      }
    if (event.destination is Server.Connection.Graph)
      event.sent.toRpc().forEach { message ->
        messages.emit(
          Message.SentToGraph(event.session.id, message, "${event.destination.address}")
        )
      }
    if (event.source is Server.Connection.Graph)
      event.received.toRpc().forEach { message ->
        messages.emit(
          Message.ReceivedFromGraph(event.session.id, message, "${event.source.address}")
        )
      }
  }

  override fun getMessages() = messages

  override suspend fun getSchema() = schema.value

  override suspend fun getPlugin() = PluginLoader.getPlugin()

  override suspend fun load(script: String?) = PluginLoader.load(script)

  /**
   * A [Server.Plugin] that dynamically loads [io.github.cfraser.graphguard.plugin.Script] plugins.
   */
  object PluginLoader : Server.Plugin {

    /**
     * A [PluginLoader.Loaded] is the [script] source and [Script.evaluate]d [plugin] currently
     * being used by the [Server].
     */
    private data class Loaded(val script: String?, val plugin: Server.Plugin)

    /** A [Mutex] for updating/reading [PluginLoader.loaded]. */
    private val mutex = Mutex()

    /**
     * The [PluginLoader.Loaded] [Server.Plugin].
     * > Use the [mutex] to update [PluginLoader.loaded].
     */
    private var loaded = Loaded(null, plugin {})

    /** Get the [PluginLoader.loaded] script source. */
    suspend fun getPlugin(): String? = mutex.withLock { loaded.script }

    /** [Script.evaluate] the [script] then load the [Server.Plugin]. */
    suspend fun load(script: String?) {
      if (script == null) {
        mutex.withLock { loaded = Loaded(script, plugin {}) }
        return
      }
      val plugin = withContext(Dispatchers.IO) { Script.evaluate(script) }
      mutex.withLock { this.loaded = Loaded(script, plugin) }
    }

    /** [Server.Plugin.intercept] the [message] with the [PluginLoader.loaded]. */
    override suspend fun intercept(session: Bolt.Session, message: Bolt.Message) =
      mutex.withLock { loaded.plugin }.intercept(session, message)

    /** [Server.Plugin.observe] the [event] with the [PluginLoader.loaded]. */
    override suspend fun observe(event: Server.Event) =
      mutex.withLock { loaded.plugin }.observe(event)
  }

  private companion object {

    @Suppress("CyclomaticComplexMethod")
    fun Bolt.Message.toRpc(): List<Message.Bolt> =
      when (this) {
        is Bolt.Messages -> messages.flatMap { it.toRpc() }
        is Bolt.Begin -> listOf(Message.Bolt.Begin(extra.toJsonObject()))
        Bolt.Commit -> listOf(Message.Bolt.Commit)
        is Bolt.Discard -> listOf(Message.Bolt.Discard(extra.toJsonObject()))
        is Bolt.Failure -> listOf(Message.Bolt.Failure(metadata.toJsonObject()))
        Bolt.Goodbye -> listOf(Message.Bolt.Goodbye)
        is Bolt.Hello -> listOf(Message.Bolt.Hello(extra.toJsonObject()))
        Bolt.Ignored -> listOf(Message.Bolt.Ignored)
        Bolt.Logoff -> listOf(Message.Bolt.Logoff)
        is Bolt.Logon -> listOf(Message.Bolt.Logon(auth.toJsonObject()))
        is Bolt.Pull -> listOf(Message.Bolt.Pull(extra.toJsonObject()))
        is Bolt.Record ->
          listOf(
            Message.Bolt.Record(
              buildJsonArray { data.forEach { value -> add(value.toJsonElement()) } }
            )
          )
        Bolt.Reset -> listOf(Message.Bolt.Reset)
        Bolt.Rollback -> listOf(Message.Bolt.Rollback)
        is Bolt.Route ->
          listOf(Message.Bolt.Route(routing.toJsonObject(), bookmarks, extra.toJsonObject()))
        is Bolt.Run ->
          listOf(Message.Bolt.Run(query, parameters.toJsonObject(), extra.toJsonObject()))
        is Bolt.Success -> listOf(Message.Bolt.Success(metadata.toJsonObject()))
        is Bolt.Telemetry -> listOf(Message.Bolt.Telemetry(api))
      }

    /** Convert the [Map] to a [JsonObject]. */
    fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
      this@toJsonObject.forEach { (key, value) -> put(key, value.toJsonElement()) }
    }

    /** Convert [Any] to a [JsonElement]. */
    fun Any?.toJsonElement(): JsonElement =
      when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> ->
          buildJsonObject {
            @Suppress("UNCHECKED_CAST")
            (this@toJsonElement as Map<String, Any?>).forEach { (key, value) ->
              put(key, value.toJsonElement())
            }
          }
        is List<*> ->
          buildJsonArray { this@toJsonElement.forEach { item -> add(item.toJsonElement()) } }
        else -> JsonPrimitive("$this")
      }
  }
}
