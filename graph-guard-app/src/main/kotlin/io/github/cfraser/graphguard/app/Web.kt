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
@file:OptIn(Internal::class)

package io.github.cfraser.graphguard.app

import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.app.Command.Output
import io.github.cfraser.graphguard.app.Web.Companion.flatten
import io.github.cfraser.graphguard.app.Web.Message.Direction.ReceivedFromClient
import io.github.cfraser.graphguard.app.Web.Message.Direction.ReceivedFromGraph
import io.github.cfraser.graphguard.app.Web.Message.Direction.SentToClient
import io.github.cfraser.graphguard.app.Web.Message.Direction.SentToGraph
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.utils.Internal
import io.github.cfraser.graphguard.validate.Query
import io.github.cfraser.graphguard.validate.Schema
import io.github.cfraser.graphguard.verify.Verifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * Start the [Web] application.
 *
 * @property port the port number to bind the [Web.server] to
 * @property schemaText the schema text used to validate queries
 * @property graphUri the Bolt URI of the graph being proxied
 * @property verifyOptions the [Command.VerifyOptions] for the [Verifier]
 */
internal class Web(
  val port: Int,
  private val schemaText: Lazy<String?>,
  private val graphUri: Lazy<String>,
  private val verifyOptions: Lazy<Command.VerifyOptions?>,
) : Output, Server.Plugin {

  /**
   * The [io.ktor.server.engine.EmbeddedServer] serving the web resources and the corresponding API.
   */
  val server =
    embeddedServer(Netty, port) {
      install(WebSockets)
      routing {
        staticResources("/", "static")
        get("/api/schema") {
          when (val schema = schemaText.value) {
            null -> call.respond(HttpStatusCode.NoContent)
            else -> call.respondText(schema)
          }
        }
        get("/api/plugin") {
          when (val plugin = PluginLoader.getPlugin()) {
            null -> call.respond(HttpStatusCode.NoContent)
            else -> call.respondText(plugin)
          }
        }
        post("/api/plugin") {
          val script = call.receiveText()
          PluginLoader.load(script.ifBlank { null })
          call.respond(HttpStatusCode.OK)
        }
        webSocket("/ws") {
          val messagesJob = launch {
            messages.collect { message ->
              val json = buildJsonObject {
                put("type", "message")
                put(
                  "data",
                  buildJsonObject {
                    put("session", message.session)
                    put("direction", message.direction::class.simpleName)
                    put("address", message.address)
                    put("bolt", message.bolt.toJsonObject())
                  },
                )
              }
              send(Frame.Text(json.toString()))
            }
          }
          val violationsJob = launch {
            violations.collect { violation ->
              val json = buildJsonObject {
                put("type", "violation")
                put(
                  "data",
                  buildJsonObject {
                    put("message", violation.message)
                    put("elementId", violation.elementId.toJsonElement())
                    put("name", violation.name.toJsonElement())
                    put("isNode", violation.isNode)
                  },
                )
              }
              send(Frame.Text(json.toString()))
            }
          }
          try {
            closeReason.await()
          } finally {
            messagesJob.cancel()
            violationsJob.cancel()
          }
        }
      }
      // after application startup, begin the periodic verification according to the interval
      monitor.subscribe(ApplicationStarted) handler@{
        val options = verifyOptions.value ?: return@handler
        verifyScope.launch {
          while (true) {
            verify()
            delay(options.interval)
          }
        }
      }
      // close the driver after the application is stopped
      monitor.subscribe(ApplicationStopped) {
        verifyScope.cancel()
        driver?.runCatching { close() }
      }
    }

  /** The [kotlinx.coroutines.flow.Flow] of [Message]s [intercept]ed. */
  private val messages = MutableSharedFlow<Message>(replay = 2048)

  /** The [kotlinx.coroutines.flow.Flow] of [Violation]s detected by the [Verifier]. */
  private val violations = MutableSharedFlow<Violation>(replay = 2048)

  /** The [Schema] lazily initialized from the [schemaText]. */
  private val schema: Schema? by lazy { schemaText.value?.let(Schema::init) }

  /** A [Mutex] to isolate [Verifier.verify] execution. */
  private val verifyMutex = Mutex()

  /**
   * The [CoroutineScope] running the [kotlinx.coroutines.Job] to [Verifier.verify] the graph
   * periodically.
   */
  private val verifyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /** The [Driver] to use to [Verifier.verify] the graph. */
  private val driver: Driver? by lazy driver@{
    val options = verifyOptions.value ?: return@driver null
    GraphDatabase.driver(graphUri.value, AuthTokens.basic(options.user, options.password))
  }

  /** A [Mutex] synchronizing access to [recentLabels]. */
  private val labelsMutex = Mutex()

  /**
   * The recent node labels from [intercept]ed [Bolt.Run] messages.
   *
   * > Used to incrementally [Verifier.verify] the graph.
   */
  private val recentLabels = mutableSetOf<String>()

  override suspend fun intercept(session: Bolt.Session, message: Bolt.Message): Bolt.Message {
    if (message is Bolt.Run) {
      val labels = Query.parse(message.query)?.nodes.orEmpty()
      if (labels.isNotEmpty()) labelsMutex.withLock { recentLabels += labels }
    }
    return message
  }

  override suspend fun observe(event: Server.Event) {
    if (event !is Server.Proxied) return
    if (event.source is Server.Connection.Client)
      event.received.flatten().forEach { bolt ->
        messages.emit(
          Message(event.session.id, ReceivedFromClient, bolt, "${event.source.address}")
        )
      }
    if (event.destination is Server.Connection.Client)
      event.sent.flatten().forEach { bolt ->
        messages.emit(Message(event.session.id, SentToClient, bolt, "${event.destination.address}"))
      }
    if (event.destination is Server.Connection.Graph)
      event.sent.flatten().forEach { bolt ->
        messages.emit(Message(event.session.id, SentToGraph, bolt, "${event.destination.address}"))
      }
    if (event.source is Server.Connection.Graph)
      event.received.flatten().forEach { bolt ->
        messages.emit(Message(event.session.id, ReceivedFromGraph, bolt, "${event.source.address}"))
      }
  }

  private suspend fun verify() {
    val driver = driver ?: return
    val schema = schema ?: return
    val verifier = Verifier(driver, schema)
    if (!verifyMutex.tryLock()) return
    try {
      val labels = labelsMutex.withLock { recentLabels.toSet().also { recentLabels.clear() } }
      verifier.verify(labels).forEach { violation ->
        violations.emit(
          Violation(
            violation.schemaViolation.ruleViolation.message,
            violation.elementId,
            violation.entity?.name,
            violation.entity !is Schema.Violation.Entity.Relationship,
          )
        )
      }
    } finally {
      verifyMutex.unlock()
    }
  }

  /**
   * A [Server.Plugin] that dynamically loads [io.github.cfraser.graphguard.plugin.Script] plugins.
   */
  object PluginLoader : Server.Plugin {

    private data class Loaded(val script: String?, val plugin: Server.Plugin)

    private val mutex = Mutex()

    private var loaded = Loaded(null, plugin {})

    suspend fun getPlugin(): String? = mutex.withLock { loaded.script }

    suspend fun load(script: String?) {
      if (script == null) {
        mutex.withLock { loaded = Loaded(script, plugin {}) }
        return
      }
      val plugin = withContext(Dispatchers.IO) { Script.evaluate(script) }
      mutex.withLock { this.loaded = Loaded(script, plugin) }
    }

    override suspend fun intercept(session: Bolt.Session, message: Bolt.Message) =
      mutex.withLock { loaded.plugin }.intercept(session, message)

    override suspend fun observe(event: Server.Event) =
      mutex.withLock { loaded.plugin }.observe(event)
  }

  data class Message(
    val session: String,
    val direction: Direction,
    val bolt: Bolt.Message,
    val address: String,
  ) {

    enum class Direction {
      ReceivedFromClient,
      SentToClient,
      SentToGraph,
      ReceivedFromGraph,
    }
  }

  data class Violation(
    val message: String,
    val elementId: String?,
    val name: String?,
    val isNode: Boolean,
  )

  private companion object {

    /** Recursively [flatten] the [Bolt.Messages] within `this`. */
    fun Bolt.Message.flatten(): List<Bolt.Message> =
      if (this is Bolt.Messages) messages.flatMap { it.flatten() } else listOf(this)

    /** Convert this [Bolt.Message] into a [JsonObject]. */
    fun Bolt.Message.toJsonObject(): JsonObject =
      when (this) {
        is Bolt.Hello ->
          buildJsonObject {
            put("type", Bolt.Hello::class.simpleName)
            put("extra", extra.toJsonObject())
          }
        Bolt.Goodbye -> buildJsonObject { put("type", Bolt.Goodbye::class.simpleName) }
        is Bolt.Logon ->
          buildJsonObject {
            put("type", Bolt.Logon::class.simpleName)
            put("auth", auth.toJsonObject())
          }
        Bolt.Logoff -> buildJsonObject { put("type", Bolt.Logoff::class.simpleName) }
        is Bolt.Begin ->
          buildJsonObject {
            put("type", Bolt.Begin::class.simpleName)
            put("extra", extra.toJsonObject())
          }
        Bolt.Commit -> buildJsonObject { put("type", Bolt.Commit::class.simpleName) }
        Bolt.Rollback -> buildJsonObject { put("type", Bolt.Rollback::class.simpleName) }
        is Bolt.Route ->
          buildJsonObject {
            put("type", Bolt.Route::class.simpleName)
            put("routing", routing.toJsonObject())
            put(
              "bookmarks",
              buildJsonArray { bookmarks.forEach { bookmark -> add(bookmark.toJsonElement()) } },
            )
            put("extra", extra.toJsonObject())
          }
        Bolt.Reset -> buildJsonObject { put("type", Bolt.Reset::class.simpleName) }
        is Bolt.Run ->
          buildJsonObject {
            put("type", Bolt.Run::class.simpleName)
            put("query", query)
            put("parameters", parameters.toJsonObject())
            put("extra", extra.toJsonObject())
          }
        is Bolt.Discard ->
          buildJsonObject {
            put("type", Bolt.Discard::class.simpleName)
            put("extra", extra.toJsonObject())
          }
        is Bolt.Pull ->
          buildJsonObject {
            put("type", Bolt.Pull::class.simpleName)
            put("extra", extra.toJsonObject())
          }
        is Bolt.Telemetry ->
          buildJsonObject {
            put("type", Bolt.Telemetry::class.simpleName)
            put("api", api)
          }
        is Bolt.Success ->
          buildJsonObject {
            put("type", Bolt.Success::class.simpleName)
            put("metadata", metadata.toJsonObject())
          }
        is Bolt.Record ->
          buildJsonObject {
            put("type", Bolt.Record::class.simpleName)
            put("data", buildJsonArray { data.forEach { any -> add(any.toJsonElement()) } })
          }
        Bolt.Ignored -> buildJsonObject { put("type", Bolt.Ignored::class.simpleName) }
        is Bolt.Failure ->
          buildJsonObject {
            put("type", Bolt.Failure::class.simpleName)
            put("metadata", metadata.toJsonObject())
          }
        is Bolt.Messages ->
          buildJsonObject {
            put("type", Bolt.Messages::class.simpleName)
            put(
              "messages",
              buildJsonArray { messages.forEach { message -> add(message.toJsonObject()) } },
            )
          }
      }

    /** Convert this [Map] into a [JsonObject]. */
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
