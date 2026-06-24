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
package io.github.cfraser.graphguard.web

import dev.fritz2.core.RenderContext
import dev.fritz2.core.alt
import dev.fritz2.core.render
import dev.fritz2.core.src
import dev.fritz2.core.storeOf
import dev.fritz2.routing.routerOf
import io.github.cfraser.graphguard.web.rpc.Message
import io.github.cfraser.graphguard.web.rpc.Service
import io.github.cfraser.graphguard.web.rpc.Violation
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlin.js.Date
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement

/**
 * Main entry point for the *graph-guard-web* application.
 *
 * Uses [fritz2](https://www.fritz2.dev/) to render the [app] within the DOM *root* element.
 */
fun main() {
  render("#root") { app() }
}

private enum class Page(
  val sidebar: String,
  val header: String,
  val icon: String,
  val route: String,
) {

  MESSAGE_STREAM("Messages", "Bolt Messages", "fas fa-stream", "messages"),
  QUERY_LOG("Queries", "Query Log", "fas fa-list", "queries"),
  VIOLATIONS("Violations", "Schema Violations", "fas fa-shield-alt", "violations"),
  SCHEMA("Schema", "Graph Schema", "fas fa-file-code", "schema"),
  PLUGINS("Plugins", "Plugin Editor", "fas fa-plug", "plugins"),
}

private val router = routerOf(Page.MESSAGE_STREAM.route)
private val messagesStore = storeOf(emptyList<Message>(), Job())
private val violationsStore = storeOf(emptyList<TimestampedViolation>(), Job())
private val schemaStore = storeOf<String?>(null, Job())
private val errorStore = storeOf<String?>(null, Job())
private val pluginEditorStore = storeOf("", Job())

private val service =
  try {
    HttpClient(Js) { installKrpc() }
      .rpc(
        run {
          val protocol = if (window.location.protocol == "https:") "wss:" else "ws:"
          "$protocol//${window.location.host}/rpc"
        }
      ) {
        rpcConfig { serialization { json { classDiscriminator = "type" } } }
      }
      .withService<Service>()
  } catch (ex: Exception) {
    errorStore.update("Failed to initialize web service: ${ex.message}")
    null
  }

private fun RenderContext.app() {
  CoroutineScope(job).launch {
    try {
      service?.getSchema()?.also { schema -> schemaStore.update(schema) }
      service?.getPlugin()?.let { plugin -> pluginEditorStore.update(plugin) }
      service
        ?.getMessages()
        ?.catch { exception -> errorStore.update("Error: ${exception.message}") }
        ?.map { data -> (listOf(data) + messagesStore.current).take(2048) }
        ?.collect { messages -> messagesStore.update(messages) }
    } catch (ex: Exception) {
      errorStore.update("Error: ${ex.message}")
    }
  }
  CoroutineScope(job).launch {
    try {
      service
        ?.getViolations()
        ?.catch { exception -> errorStore.update("Error: ${exception.message}") }
        ?.collect { violation ->
          val current = violationsStore.current
          val existingIdx = current.indexOfFirst { it.violation == violation }
          val updated =
            if (existingIdx >= 0) {
              val refreshed = current[existingIdx].copy(lastSeen = Date.now())
              listOf(refreshed) + current.filterIndexed { i, _ -> i != existingIdx }
            } else {
              (listOf(TimestampedViolation(violation, Date.now())) + current).take(2048)
            }
          violationsStore.update(updated)
        }
    } catch (ex: Exception) {
      errorStore.update("Error: ${ex.message}")
    }
  }
  div("app-container") {
    div("main-card") {
      sidebar()
      div("main-content") {
        router.data.render { route ->
          errorStore.data.render { error ->
            if (error != null) {
              errorDisplay(error)
              return@render
            }
            val page = Page.entries.find { it.route == route } ?: Page.MESSAGE_STREAM
            when (page) {
              Page.MESSAGE_STREAM -> messageStreamPage()
              Page.QUERY_LOG -> queryLogPage()
              Page.VIOLATIONS -> violationsPage()
              Page.SCHEMA -> schemaPage()
              Page.PLUGINS -> pluginsPage()
            }
          }
        }
      }
    }
  }
}

private fun RenderContext.sidebar() {
  div("sidebar") {
    div("sidebar-logo") {
      img {
        src("graph-guard-dark.png")
        alt("graph-guard logo")
      }
    }
    Page.entries.forEach { page ->
      router.data.render { currentRoute ->
        div(if (page.route == currentRoute) "sidebar-item active" else "sidebar-item") {
          clicks.map { page.route } handledBy router.navTo
          i("sidebar-icon ${page.icon}") {}
          +page.sidebar
        }
      }
    }
  }
}

/**
 * Renders a page header then the [content] body. The [subtitle] and [action] slots are optional.
 */
private fun RenderContext.page(
  title: String,
  subtitle: (RenderContext.() -> Unit)? = null,
  action: (RenderContext.() -> Unit)? = null,
  content: RenderContext.() -> Unit,
) {
  header("header") {
    if (subtitle != null) {
      div("header-title-group") {
        h1 { +title }
        subtitle()
      }
    } else {
      h1 { +title }
    }
    action?.invoke(this)
  }
  content()
}

/** Renders a scrollable list of [items] from [data], showing [emptyText] when empty. */
private fun <T> RenderContext.flowPage(
  data: Flow<List<T>>,
  emptyText: String,
  card: RenderContext.(T) -> Unit,
) {
  div("flow-container") {
    data.render { items ->
      div("flow-list") {
        if (items.isEmpty()) div("empty-state") { p { +emptyText } } else items.forEach { card(it) }
      }
    }
  }
}

private fun RenderContext.messageStreamPage() {
  page(Page.MESSAGE_STREAM.header) {
    flowPage(messagesStore.data, "No Bolt messages intercepted yet...", RenderContext::messageCard)
  }
}

private fun RenderContext.messageCard(message: Message) {
  val baseClass =
    when (message) {
      is Message.ReceivedFromClient -> "message-received-from-client"
      is Message.SentToClient -> "message-sent-to-client"
      is Message.ReceivedFromGraph -> "message-received-from-graph"
      is Message.SentToGraph -> "message-sent-to-graph"
    }
  div("message-card $baseClass") {
    span("message-flow") {
      when (message) {
        is Message.ReceivedFromClient -> {
          i("message-icon client-icon fas fa-laptop") {}
          span("message-address") { +message.address }
          i("message-arrow fas fa-arrow-right") {}
          img("message-icon guard-icon") {
            src("graph-guard.png")
            alt("graph-guard")
          }
        }
        is Message.SentToClient -> {
          i("message-icon client-icon fas fa-laptop") {}
          span("message-address") { +message.address }
          i("message-arrow fas fa-arrow-left") {}
          img("message-icon guard-icon") {
            src("graph-guard.png")
            alt("graph-guard")
          }
        }
        is Message.SentToGraph -> {
          img("message-icon guard-icon") {
            src("graph-guard.png")
            alt("graph-guard")
          }
          i("message-arrow fas fa-arrow-right") {}
          i("message-icon database-icon fas fa-database") {}
          span("message-address") { +message.address }
        }
        is Message.ReceivedFromGraph -> {
          img("message-icon guard-icon") {
            src("graph-guard.png")
            alt("graph-guard")
          }
          i("message-arrow fas fa-arrow-left") {}
          i("message-icon database-icon fas fa-database") {}
          span("message-address") { +message.address }
        }
      }
    }
    span("message-content") { +"${message.bolt}" }
  }
}

private fun RenderContext.queryLogPage() {
  page(Page.QUERY_LOG.header) {
    flowPage(
      messagesStore.data.map { getQueries(it) },
      "No queries intercepted yet...",
      RenderContext::queryLogCard,
    )
  }
}

private data class TimestampedViolation(val violation: Violation, val lastSeen: Double)

private data class RunQuery(
  val session: String,
  val cypher: String,
  val parameters: JsonObject,
  val response: Message.Bolt.Response?,
  val clientAddress: String,
)

private fun getQueries(messages: List<Message>): List<RunQuery> = buildList {
  messages.groupBy(Message::session).forEach { (session, sessionMessages) ->
    sessionMessages.forEachIndexed { index, message ->
      if (message !is Message.ReceivedFromClient) return@forEachIndexed
      val run = message.bolt as? Message.Bolt.Run ?: return@forEachIndexed
      val nextMessages = sessionMessages.take(index + 1).reversed()
      val response = nextMessages.firstNotNullOfOrNull { nextMessage ->
        if (nextMessage !is Message.SentToClient) return@firstNotNullOfOrNull null
        when (val bolt = nextMessage.bolt) {
          is Message.Bolt.Success,
          is Message.Bolt.Failure -> bolt
          else -> null
        }
      }
      this += RunQuery(session, run.query, run.parameters, response, message.address)
    }
  }
}

private fun RenderContext.queryLogCard(query: RunQuery) {
  val (statusClass, statusIcon) =
    when (query.response) {
      is Message.Bolt.Success -> "query-success" to "fas fa-check-circle"
      is Message.Bolt.Failure -> "query-failure" to "fas fa-times-circle"
      else -> "query-pending" to "fas fa-clock"
    }
  div("query-log-card $statusClass") {
    div("query-log-header") {
      i("query-status-icon $statusIcon") {}
      span("query-flow") {
        i("query-icon fas fa-laptop") {}
        span("query-address") { +query.clientAddress }
      }
      span("query-session") {
        i("fas fa-circle-nodes") {}
        +" ${query.session}"
      }
    }
    div("query-log-content") {
      pre("query-text") {
        code("language-cypher") {
          +query.cypher
          domNode.also(hljs::highlightElement)
        }
      }
      // TODO: the `SUCCESS` response for `RUN` doesn't contain `statuses`. Need to find the `PULL`
      // with `has_more == false` to get `statuses`
      // see
      //  - https://neo4j.com/docs/bolt/current/bolt/message/#messages-run-success
      //  - https://neo4j.com/docs/bolt/current/bolt/message/#messages-pull-success
      query.response
        ?.let { response -> response as? Message.Bolt.Success }
        ?.let { success -> success.metadata["statuses"]?.runCatching { jsonArray }?.getOrNull() }
        ?.also { statuses ->
          details("query-statuses") {
            summary("query-statuses-summary") {
              i("fas fa-info-circle") {}
              +" ${statuses.size} Statuses"
            }
            pre("query-statuses-metadata") { +"$statuses" }
          }
        }
      query.response
        ?.let { response -> response as? Message.Bolt.Failure }
        ?.also { failure ->
          details("query-failure-details") {
            summary("query-failure-summary") {
              i("fas fa-exclamation-triangle") {}
              val message = failure.metadata["message"] ?: failure.metadata["description"]
              +" Failure: ${message ?: "Unknown"}"
              if (
                failure.metadata["neo4j_code"]?.jsonPrimitive?.content == "GraphGuard.Invalid.Query"
              ) {
                schemaStore.data.render { schema ->
                  if (schema != null) {
                    i("schema-icon fas fa-file-code") {
                      attr("title", "View schema")
                      clicks.map { Page.SCHEMA.route } handledBy router.navTo
                    }
                  }
                }
              }
            }
            pre("query-failure-metadata") { +"$failure" }
          }
        }
      if (query.parameters.isNotEmpty()) {
        details("query-parameters") {
          summary {
            i("fas fa-code") {}
            +" Parameters"
          }
          pre { +"${query.parameters}" }
        }
      }
    }
  }
}

private fun RenderContext.violationsPage() {
  page(
    Page.VIOLATIONS.header,
    subtitle = {
      p("header-subtitle") {
        +"Violations reported by the "
        a {
          attr(
            "href",
            "https://c-fraser.github.io/graph-guard/api/graph-guard-verify/io.github.cfraser.graphguard.verify/-verifier/index.html",
          )
          attr("target", "_blank")
          attr("rel", "noreferrer")
          +"Verifier"
        }
      }
    },
  ) {
    flowPage(violationsStore.data, "No violations found...", RenderContext::violationCard)
  }
}

private fun RenderContext.violationCard(tv: TimestampedViolation) {
  val violation = tv.violation
  div("violation-card") {
    i("fas fa-exclamation-triangle violation-icon") {}
    div("violation-body") {
      span("violation-message") { +violation.message }
      violation.elementId?.let { id ->
        span("violation-element-id") {
          i("fas fa-fingerprint") {}
          +" Element ID: $id"
          button("violation-copy-btn") {
            attr("title", "Copy MATCH query to clipboard")
            i("fas fa-copy") {}
            clicks handledBy
              {
                val quotedLabel = violation.name?.let { "`${it.replace("`", "``")}`" }
                val query =
                  if (violation.isNode) {
                    val label = if (quotedLabel != null) ":$quotedLabel" else ""
                    "MATCH (n$label) WHERE elementId(n) = '$id' RETURN n"
                  } else {
                    val type = if (quotedLabel != null) ":$quotedLabel" else ""
                    "MATCH ()-[r$type]->() WHERE elementId(r) = '$id' RETURN r"
                  }
                copyToClipboard(query)
              }
          }
        }
      }
      span("violation-timestamp") {
        i("fas fa-clock") {}
        +" Last seen: ${Date(tv.lastSeen).toLocaleString()}"
      }
    }
  }
}

private fun copyToClipboard(text: String) {
  window.navigator.asDynamic().clipboard.writeText(text)
}

private fun RenderContext.errorDisplay(message: String) {
  div("error-display") {
    i("fas fa-exclamation-triangle") {}
    span { +" $message" }
  }
}

private fun RenderContext.schemaPage() {
  page(Page.SCHEMA.header) {
    div("plugins-container") {
      schemaStore.data.render { schema ->
        if (schema != null) {
          div("plugin-editor-container") {
            pre("plugin-code-wrapper") {
              code("plugin-code-editor") {
                +schema
                domNode.also(hljs::highlightElement)
              }
            }
          }
        } else {
          div("empty-state") { p { +"No schema configured..." } }
        }
      }
    }
  }
}

private fun RenderContext.pluginsPage() {
  page(
    Page.PLUGINS.header,
    action = {
      div("header-schema-icon plugin-save") {
        attr("title", "Save plugin")
        clicks handledBy
          {
            CoroutineScope(job).launch {
              try {
                val editorElement = document.getElementById("plugin-code-editor-element")
                val script = editorElement?.textContent?.takeIf(String::isNotBlank)
                service!!.load(script)
                val refreshedPlugin = service.getPlugin()
                pluginEditorStore.update(refreshedPlugin.orEmpty())
                editorElement?.textContent = refreshedPlugin.orEmpty()
                editorElement?.let { element ->
                  (element as? HTMLElement)?.let { htmlElement ->
                    htmlElement.removeAttribute("data-highlighted")
                    htmlElement.className = "language-kotlin plugin-code-editor"
                    hljs.highlightElement(htmlElement)
                  }
                }
              } catch (ex: Exception) {
                errorStore.update("Failed to save plugin: ${ex.message}")
              }
            }
          }
        i("fas fa-save") {}
        span { +"Save" }
      }
    },
  ) {
    div("plugins-container") { pluginEditor() }
  }
}

private fun RenderContext.pluginEditor() {
  div("plugin-editor-container") {
    pluginEditorStore.data.render { code ->
      pre("plugin-code-wrapper") {
        code("language-kotlin plugin-code-editor") {
          attr("contenteditable", "true")
          attr("spellcheck", "false")
          attr("autocomplete", "off")
          attr("autocorrect", "off")
          attr("autocapitalize", "off")
          attr("id", "plugin-code-editor-element")
          domNode.textContent = code
          domNode.also(hljs::highlightElement)
        }
      }
    }
  }
}

/**
 * External declaration for the global [highlight.js](https://github.com/highlightjs/highlightjs)
 * object.
 */
@Suppress("ClassName")
private external object hljs {

  fun highlightElement(@Suppress("unused") element: HTMLElement)

  @Suppress("unused") fun highlightAuto(code: String): dynamic
}
