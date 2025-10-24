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

import io.github.cfraser.graphguard.rpc.ProxiedMessage
import io.github.cfraser.graphguard.rpc.WebService
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import react.FC
import react.Props
import react.create
import react.dom.client.createRoot
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.picture
import react.dom.html.ReactHTML.source
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document
import web.location.location

/**
 * Main entry point for the *graph-guard-web* application.
 *
 * Initializes the [React](https://react.dev/) application by rendering the [App] component into the
 * DOM root element.
 */
fun main() {
  document.getElementById(ElementId("root"))?.let(::createRoot)?.render(App.create())
    ?: error("Failed to initialize app")
}

/**
 * Available pages in the application.
 *
 * @property displayName the human-readable name of the page
 * @property icon the emoji icon representing the page
 */
internal enum class Page(val displayName: String, val icon: String) {
  /** The message stream page displaying real-time Bolt traffic. */
  MESSAGE_STREAM("Bolt Traffic", "📡"),

  /** The query log page (placeholder). */
  QUERY_LOG("Query Log", "📋"),

  /** The plugins management page (placeholder). */
  PLUGINS("Plugins", "🔌"),
}

/**
 * [App] is the root React component for the *graph-guard-web* application.
 *
 * Manages the application state including:
 * - Current active page
 * - Connection to the [WebService] via WebSocket RPC
 * - Real-time streaming of [ProxiedMessage] data
 * - Error handling and connection status
 * - Message buffer management (maintains last 100 messages per session)
 */
internal val App =
  FC<Props> {
    var currentPage by useState(Page.MESSAGE_STREAM)
    var messages by useState(emptyList<ProxiedMessage>())
    var selectedSession by useState<String?>(null)
    var connected by useState(false)
    var error by useState<String?>(null)

    // Connect to RPC service on mount
    react.useEffectOnce {
      val scope = CoroutineScope(Dispatchers.Default)
      scope.launch {
        try {
          // Build WebSocket URL from current page location
          val protocol = if (location.protocol == "https:") "wss:" else "ws:"
          val wsUrl = "$protocol//${location.host}/rpc"

          // Initialize RPC service
          val service =
            HttpClient(Js) { installKrpc() }
              .rpc(wsUrl) { rpcConfig { serialization { json() } } }
              .withService<WebService>()
          connected = true

          // Collect proxied messages
          service
            .getMessages()
            .catch { e ->
              error = "Error: ${e.message}"
              connected = false
            }
            .collect { message ->
              // Keep last 100 messages total, newest first
              messages = (listOf(message) + messages).take(100)
              // Auto-select first session if none selected
              if (selectedSession == null && messages.isNotEmpty()) {
                selectedSession = messages.first().session
              }
            }
        } catch (e: Exception) {
          error = "Failed to connect: ${e.message}"
          connected = false
        }
      }
    }

    div {
      className = ClassName("app-container")

      div {
        className = ClassName("main-card")

        Sidebar {
          this.currentPage = currentPage
          this.onPageChange = { newPage -> currentPage = newPage }
        }

        div {
          className = ClassName("main-content")

          Header {
            this.connected = connected
            this.messageCount = messages.size
            this.currentPage = currentPage
          }

          when (currentPage) {
            Page.MESSAGE_STREAM -> {
              if (error != null) {
                ErrorDisplay { this.message = error!! }
              } else {
                MessageStreamPage {
                  this.messages = messages
                  this.selectedSession = selectedSession
                  this.onSessionChange = { session -> selectedSession = session }
                }
              }
            }
            Page.QUERY_LOG -> {
              PlaceholderPage {
                this.title = "Query Log"
                this.description =
                  "View and analyze Neo4j query logs. Coming soon with detailed query performance metrics and filtering capabilities."
                this.icon = "📋"
              }
            }
            Page.PLUGINS -> {
              PlaceholderPage {
                this.title = "Plugins"
                this.description =
                  "Manage graph-guard plugins to extend functionality. Configure validation rules, custom transformations, and monitoring extensions."
                this.icon = "🔌"
              }
            }
          }
        }
      }
    }
  }

/**
 * Properties for the [Sidebar] component.
 *
 * @property currentPage the currently active [Page]
 * @property onPageChange callback invoked when the user selects a different page
 */
internal external interface SidebarProps : Props {
  var currentPage: Page
  var onPageChange: (Page) -> Unit
}

/**
 * [Sidebar] component displays the navigation menu.
 *
 * Shows all available pages with icons and highlights the currently active page.
 */
internal val Sidebar =
  FC<SidebarProps> { props ->
    div {
      className = ClassName("sidebar")

      div {
        className = ClassName("sidebar-logo")
        picture {
          source {
            media = "(prefers-color-scheme: dark)"
            srcSet = "graph-guard-dark.png"
          }
          img {
            src = "graph-guard-light.png"
            alt = "graph-guard logo"
          }
        }
      }

      Page.entries.forEach { page ->
        div {
          className =
            if (page == props.currentPage) ClassName("sidebar-item active")
            else ClassName("sidebar-item")
          onClick = { props.onPageChange(page) }

          span {
            className = ClassName("sidebar-icon")
            +page.icon
          }
          +page.displayName
        }
      }
    }
  }

/**
 * Properties for the [Header] component.
 *
 * @property connected whether the WebSocket connection is active
 * @property messageCount the total number of messages received
 * @property currentPage the currently active [Page]
 */
internal external interface HeaderProps : Props {
  var connected: Boolean
  var messageCount: Int
  var currentPage: Page
}

/**
 * [Header] component displays the application title and connection status.
 *
 * Shows the current page name, connection indicator, and message count.
 */
internal val Header =
  FC<HeaderProps> { props ->
    header {
      className = ClassName("header")

      h1 { +props.currentPage.displayName }

      if (props.currentPage == Page.MESSAGE_STREAM) {
        div {
          className = ClassName("header-status")

          span { +(if (props.connected) "🟢" else "🔴") }
        }
      }
    }
  }

/**
 * Properties for the [MessageStreamPage] component.
 *
 * @property messages the list of [ProxiedMessage] instances to display
 * @property selectedSession the currently selected session ID
 * @property onSessionChange callback invoked when a session is selected
 */
internal external interface MessageStreamPageProps : Props {
  var messages: List<ProxiedMessage>
  var selectedSession: String?
  var onSessionChange: (String?) -> Unit
}

/**
 * [MessageStreamPage] component displays the message stream page.
 *
 * Contains the session selector and message list with real-time Bolt traffic monitoring.
 */
internal val MessageStreamPage =
  FC<MessageStreamPageProps> { props ->
    div {
      className = ClassName("message-stream-container")

      SessionSelector {
        this.messages = props.messages
        this.selectedSession = props.selectedSession
        this.onSessionChange = props.onSessionChange
      }

      val filteredMessages =
        props.selectedSession?.let { session -> props.messages.filter { it.session == session } }
          ?: emptyList()

      MessageList { this.messages = filteredMessages }
    }
  }

/**
 * Properties for the [SessionSelector] component.
 *
 * @property messages the list of all [ProxiedMessage] instances
 * @property selectedSession the currently selected session ID
 * @property onSessionChange callback invoked when a session is selected
 */
internal external interface SessionSelectorProps : Props {
  var messages: List<ProxiedMessage>
  var selectedSession: String?
  var onSessionChange: (String?) -> Unit
}

/**
 * [SessionSelector] component displays the session selection sidebar.
 *
 * Shows all active sessions with message counts and allows filtering by session.
 */
internal val SessionSelector =
  FC<SessionSelectorProps> { props ->
    // Group messages by session and count
    val sessionCounts =
      props.messages
        .groupBy { it.session }
        .mapValues { (_, msgs) -> msgs.size }
        .entries
        .sortedByDescending { (_, count) -> count }

    div {
      className = ClassName("session-selector")

      div {
        className = ClassName("session-selector-header")
        +"Sessions"
        if (sessionCounts.isNotEmpty()) {
          span {
            className = ClassName("session-count-badge")
            +"${sessionCounts.size}"
          }
        }
      }

      div {
        className = ClassName("session-list")

        if (sessionCounts.isEmpty()) {
          div {
            className = ClassName("empty-sessions")
            +"No sessions yet"
          }
        } else {
          sessionCounts.forEach { (session, count) ->
            div {
              className =
                if (session == props.selectedSession) ClassName("session-item active")
                else ClassName("session-item")
              onClick = { props.onSessionChange(session) }

              div {
                className = ClassName("session-id")
                +session.take(12) // Show first 12 chars of session ID
              }

              span {
                className = ClassName("session-message-count")
                +"$count"
              }
            }
          }
        }
      }
    }
  }

/**
 * Properties for the [MessageList] component.
 *
 * @property messages the list of [ProxiedMessage] instances to display
 */
internal external interface MessageListProps : Props {
  var messages: List<ProxiedMessage>
}

/**
 * [MessageList] component displays the scrollable list of proxied messages.
 *
 * Includes:
 * - A header section with message count
 * - A scrollable container with custom styling
 * - Empty state when no messages are present
 * - Latest message indicator with fade animation
 */
internal val MessageList =
  FC<MessageListProps> { props ->
    // Scrollable message list
    div {
      className = ClassName("message-list")

      if (props.messages.isEmpty()) {
        div {
          className = ClassName("empty-state")
          p { +"Waiting for Bolt messages..." }
          p { +"Messages will appear here in real-time when the proxy intercepts Bolt traffic." }
        }
      } else {
        props.messages.forEachIndexed { index, message ->
          MessageCard {
            this.message = message
            this.isLatest = index == 0
          }
        }
      }
    }
  }

/**
 * Properties for the [MessageCard] component.
 *
 * @property message the [ProxiedMessage] to display
 * @property isLatest whether this is the most recently received message
 */
internal external interface MessageCardProps : Props {
  var message: ProxiedMessage
  var isLatest: Boolean
}

/**
 * [MessageCard] component displays a single proxied
 * [Bolt](https://neo4j.com/docs/bolt/current/bolt/) message.
 *
 * Displays:
 * - Session information (session ID and routing)
 * - Received message content (from client to server)
 * - Sent message content (from server to client)
 * - Visual indicator for the latest message
 */
internal val MessageCard =
  FC<MessageCardProps> { props ->
    val message = props.message

    div {
      className =
        if (props.isLatest) ClassName("message-card latest-indicator")
        else ClassName("message-card")

      // Session header
      div {
        className = ClassName("session-header")

        span {
          className = ClassName("session-header-title")
          +"Session: ${message.session}"
        }

        span {
          className = ClassName("session-header-route")
          +"${message.source} → ${message.destination}"
        }
      }

      // Received message
      div {
        className = ClassName("message-received")

        div {
          className = ClassName("message-received-label")
          +"➡️ Received"
        }

        div {
          className = ClassName("message-received-content")
          +message.received
        }
      }

      // Sent message
      div {
        className = ClassName("message-sent")

        div {
          className = ClassName("message-sent-label")
          +"⬅️ Sent"
        }

        div {
          className = ClassName("message-sent-content")
          +message.sent
        }
      }
    }
  }

/**
 * Properties for the [PlaceholderPage] component.
 *
 * @property title the page title
 * @property description a brief description of the page functionality
 * @property icon the emoji icon for the page
 */
internal external interface PlaceholderPageProps : Props {
  var title: String
  var description: String
  var icon: String
}

/**
 * [PlaceholderPage] component displays a placeholder for unimplemented pages.
 *
 * Shows a centered message with an icon and description of the future functionality.
 */
internal val PlaceholderPage =
  FC<PlaceholderPageProps> { props ->
    div {
      className = ClassName("placeholder-page")

      div {
        className = ClassName("placeholder-page-icon")
        +props.icon
      }

      div {
        className = ClassName("placeholder-page-title")
        +props.title
      }

      div {
        className = ClassName("placeholder-page-description")
        +props.description
      }
    }
  }

/**
 * Properties for the [ErrorDisplay] component.
 *
 * @property message the error message to display
 */
internal external interface ErrorDisplayProps : Props {
  var message: String
}

/**
 * [ErrorDisplay] component shows error messages.
 *
 * Displayed when the WebSocket connection fails or encounters an error during message streaming.
 */
internal val ErrorDisplay =
  FC<ErrorDisplayProps> { props ->
    div {
      className = ClassName("error-display")
      p { +"⚠️ ${props.message}" }
    }
  }
