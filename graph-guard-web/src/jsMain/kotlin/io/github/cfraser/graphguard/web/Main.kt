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
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

/** Main entry point for the GraphGuard web application. */
fun main() {
  val container = document.getElementById(ElementId("root")) ?: error("Root element not found")
  createRoot(container).render(App.create())
}

/** [App] is the root React component for the GraphGuard web application. */
val App =
  FC<Props> {
    var messages by useState(emptyList<ProxiedMessage>())
    var connected by useState(false)
    var error by useState<String?>(null)

    // Connect to RPC service on mount
    react.useEffectOnce {
      val scope = CoroutineScope(Dispatchers.Default)
      scope.launch {
        try {
          val rpcClient =
            HttpClient(Js) { installKrpc() }
              .rpc("ws://localhost:8080/web") { rpcConfig { serialization { json() } } }

          val service = rpcClient.withService<WebService>()
          connected = true

          service
            .getMessages()
            .catch { e ->
              error = "Error: ${e.message}"
              connected = false
            }
            .collect { message ->
              messages = listOf(message) + messages.take(99) // Keep last 100 messages
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

        Header {
          this.connected = connected
          this.messageCount = messages.size
        }

        if (error != null) {
          ErrorDisplay { this.message = error!! }
        } else {
          MessageList { this.messages = messages }
        }
      }
    }
  }

external interface HeaderProps : Props {
  var connected: Boolean
  var messageCount: Int
}

/** [Header] component displays the application title and status. */
val Header =
  FC<HeaderProps> { props ->
    header {
      className = ClassName("header")

      h1 { +"GraphGuard - Bolt Message Monitor" }

      div {
        className = ClassName("header-status")

        span { +(if (props.connected) "🟢" else "🔴") }
        +"${props.messageCount} Messages"
      }
    }
  }

external interface MessageListProps : Props {
  var messages: List<ProxiedMessage>
}

/** [MessageList] component displays the list of proxied messages. */
val MessageList =
  FC<MessageListProps> { props ->
    // Header with message count
    div {
      className = ClassName("message-header")

      h2 { +"Message Stream" }

      if (props.messages.isNotEmpty()) {
        div {
          className = ClassName("message-counter")
          +"${props.messages.size} message${if (props.messages.size != 1) "s" else ""}"
        }
      }
    }

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

external interface MessageCardProps : Props {
  var message: ProxiedMessage
  var isLatest: Boolean
}

/** [MessageCard] component displays a single proxied message. */
val MessageCard =
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

external interface ErrorDisplayProps : Props {
  var message: String
}

/** [ErrorDisplay] component shows error messages. */
val ErrorDisplay =
  FC<ErrorDisplayProps> { props ->
    div {
      className = ClassName("error-display")
      p { +"⚠️ ${props.message}" }
    }
  }
