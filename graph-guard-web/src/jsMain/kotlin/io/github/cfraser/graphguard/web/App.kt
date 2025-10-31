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
import dev.fritz2.headless.components.modal
import dev.fritz2.headless.foundation.portalRoot
import dev.fritz2.routing.routerOf
import io.github.cfraser.graphguard.web.rpc.Message
import io.github.cfraser.graphguard.web.rpc.Service
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
  PLUGINS("Plugins", "Plugin Editor", "fas fa-plug", "plugins"),
}

private val router = routerOf(Page.MESSAGE_STREAM.route)
private val messagesStore = storeOf(emptyList<Message>(), Job())
private val schemaStore = storeOf<String?>(null, Job())
private val errorStore = storeOf<String?>(null, Job())
private val schemaModalStore = storeOf(false, Job())
private val pluginEditorStore = storeOf("", Job())

private val service =
  try {
    HttpClient(Js) { installKrpc() }
      .rpc(
        run {
          // Build WebSocket URL from current page location
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

      // Load plugin from server
      service?.getPlugin()?.let { plugin -> pluginEditorStore.update(plugin.orEmpty()) }

      // Collect proxied messages
      service
        ?.getMessages()
        ?.catch { exception -> errorStore.update("Error: ${exception.message}") }
        ?.map { data ->
          // Prepend latest messages
          (listOf(data) + messagesStore.current).take(2048)
        }
        ?.collect { messages -> messagesStore.update(messages) }
    } catch (ex: Exception) {
      errorStore.update("Error: ${ex.message}")
    }
  }
  div("app-container") {
    div("main-card") {
      sidebar()
      div("main-content") {
        header()
        router.data.render { route ->
          errorStore.data.render { error ->
            if (error != null) {
              errorDisplay(error)
              return@render
            }
            val page = Page.entries.find { it.route == route } ?: Page.MESSAGE_STREAM
            when (page) {
              Page.MESSAGE_STREAM -> {
                messageStreamPage()
              }
              Page.QUERY_LOG -> {
                queryLogPage()
              }
              Page.PLUGINS -> {
                pluginsPage()
              }
            }
          }
        }
      }
    }
  }
  schemaModal()
  portalRoot()
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

private fun RenderContext.header() {
  header("header") {
    router.data.render { route ->
      val page = Page.entries.find { it.route == route } ?: Page.MESSAGE_STREAM
      h1 { +page.header }
      when (page) {
        Page.QUERY_LOG -> {
          schemaStore.data.render { schema ->
            if (schema != null) {
              div("header-schema-icon") {
                attr("title", "View graph schema")
                clicks.map { true } handledBy schemaModalStore.update
                i("fas fa-file-code") {}
                span { +"Schema" }
              }
            }
          }
        }
        Page.PLUGINS -> {
          div("header-schema-icon plugin-save") {
            attr("title", "Save plugin")
            clicks handledBy
              {
                CoroutineScope(job).launch {
                  try {
                    // Get content from the editor element
                    val editorElement = document.getElementById("plugin-code-editor-element")
                    val script = editorElement?.textContent?.takeIf(String::isNotBlank)

                    // Load the script on the server
                    service!!.load(script)

                    // Refresh the editor with the latest content from server
                    val refreshedPlugin = service.getPlugin()
                    pluginEditorStore.update(refreshedPlugin.orEmpty())

                    // Update the editor element with the refreshed content
                    editorElement?.textContent = refreshedPlugin.orEmpty()
                    editorElement?.let { element ->
                      (element as? HTMLElement)?.let { htmlElement ->
                        // Remove previous highlighting marker
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
        }
        else -> {}
      }
    }
  }
}

private fun RenderContext.messageStreamPage() {
  div("message-stream-container") {
    messagesStore.data.render { messages -> messageList(messages) }
  }
}

private fun RenderContext.messageList(messages: List<Message>) {
  div("message-list") {
    if (messages.isEmpty()) div("empty-state") { p { +"No Bolt messages intercepted yet..." } }
    else messages.forEach(::messageCard)
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
  div("query-log-container") { messagesStore.data.render { messages -> queryLogList(messages) } }
}

private fun RenderContext.queryLogList(messages: List<Message>) {
  val queries = getQueries(messages)
  div("query-log-list") {
    if (queries.isEmpty()) div("empty-state") { p { +"No queries intercepted yet..." } }
    else queries.forEach(::queryLogCard)
  }
}

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
      val response =
        nextMessages.firstNotNullOfOrNull { nextMessage ->
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
                      clicks.map { true } handledBy schemaModalStore.update
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

private fun RenderContext.errorDisplay(message: String) {
  div("error-display") {
    i("fas fa-exclamation-triangle") {}
    span { +" $message" }
  }
}

private fun RenderContext.pluginsPage() {
  div("plugins-container") { pluginEditor() }
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

          // Set content from store
          domNode.textContent = code

          // Apply syntax highlighting
          domNode.also(hljs::highlightElement)
        }
      }
    }
  }
}

private fun schemaModal() {
  modal {
    openState(schemaModalStore)
    modalPanel("schema-modal-panel") {
      modalOverlay("schema-modal-overlay") { clicks handledBy close }
      div("schema-modal-content") {
        div("schema-modal-header") {
          modalTitle("schema-modal-title") { +"Graph Schema" }
          button("schema-modal-close") {
            attr("aria-label", "Close")
            i("fas fa-times") {}
            clicks handledBy close
          }
        }
        div("schema-modal-body") {
          schemaStore.data.render { schema ->
            if (schema != null) {
              pre("schema-text") {
                code {
                  +schema
                  domNode.also(hljs::highlightElement)
                }
              }
            }
          }
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
