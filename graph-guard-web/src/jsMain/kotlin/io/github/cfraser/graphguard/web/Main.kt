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

import emotion.react.css
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
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.AlignItems
import web.cssom.Auto
import web.cssom.Border
import web.cssom.BoxShadow
import web.cssom.Color
import web.cssom.Display
import web.cssom.JustifyContent
import web.cssom.LineStyle
import web.cssom.MaxHeight
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.TextAlign
import web.cssom.Transform
import web.cssom.Transition
import web.cssom.WhiteSpace
import web.cssom.WordWrap
import web.cssom.deg
import web.cssom.integer
import web.cssom.linearGradient
import web.cssom.px
import web.cssom.rem
import web.cssom.rgb
import web.cssom.string
import web.cssom.vh
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
      css {
        fontFamily = string("'Segoe UI', Tahoma, Geneva, Verdana, sans-serif")
        background = linearGradient(135.deg, rgb(102, 126, 234), rgb(118, 75, 162))
        minHeight = 100.vh
        padding = 20.px
      }

      div {
        css {
          maxWidth = 1200.px
          margin = Auto.auto
          backgroundColor = Color("white")
          borderRadius = 12.px
          boxShadow = BoxShadow(0.px, 10.px, 30.px, Color("rgba(0, 0, 0, 0.3)"))
          overflow = Overflow.hidden
        }

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
      css {
        background = linearGradient(135.deg, rgb(52, 152, 219), rgb(41, 128, 185))
        color = Color("white")
        padding = 30.px
        display = Display.flex
        justifyContent = JustifyContent.spaceBetween
        alignItems = AlignItems.center
      }

      h1 {
        css {
          fontSize = 2.rem
          fontWeight = integer(600)
          margin = 0.px
        }
        +"GraphGuard - Bolt Message Monitor"
      }

      div {
        css {
          background = Color("rgba(255, 255, 255, 0.2)")
          padding = Padding(10.px, 20.px)
          borderRadius = 20.px
          fontSize = 1.rem
          fontWeight = integer(500)
        }

        span {
          css { marginRight = 10.px }
          +(if (props.connected) "🟢" else "🔴")
        }
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
    div {
      css {
        padding = 20.px
        maxHeight = "calc(100vh - 200px)".unsafeCast<MaxHeight>()
        overflowY = Auto.auto
      }

      if (props.messages.isEmpty()) {
        div {
          css {
            padding = 30.px
            textAlign = TextAlign.center
            color = rgb(127, 140, 141)
          }
          p { +"Waiting for Bolt messages..." }
          p {
            css { fontSize = 0.9.rem }
            +"Messages will appear here in real-time when the proxy intercepts Bolt traffic."
          }
        }
      } else {
        props.messages.forEach { message -> MessageCard { this.message = message } }
      }
    }
  }

external interface MessageCardProps : Props {
  var message: ProxiedMessage
}

/** [MessageCard] component displays a single proxied message. */
val MessageCard =
  FC<MessageCardProps> { props ->
    val message = props.message
    val sessionColor = getSessionColor(message.session)

    div {
      css {
        marginBottom = 20.px
        borderRadius = 8.px
        overflow = Overflow.hidden
        boxShadow = BoxShadow(0.px, 2.px, 8.px, Color("rgba(0, 0, 0, 0.1)"))
        transition = "transform 0.2s".unsafeCast<Transition>()
        hover {
          transform = "translateY(-2px)".unsafeCast<Transform>()
          boxShadow = BoxShadow(0.px, 4.px, 12.px, Color("rgba(0, 0, 0, 0.15)"))
        }
      }

      // Session header
      div {
        css {
          backgroundColor = rgb(248, 249, 250)
          padding = Padding(12.px, 20.px)
          display = Display.flex
          justifyContent = JustifyContent.spaceBetween
          alignItems = AlignItems.center
          borderLeft = Border(4.px, LineStyle.solid, Color(sessionColor))
          fontSize = 0.9.rem
        }

        span {
          css {
            fontWeight = integer(600)
            color = rgb(44, 62, 80)
          }
          +"Session: ${message.session}"
        }

        span {
          css {
            color = rgb(127, 140, 141)
            fontSize = 0.85.rem
          }
          +"${message.source} → ${message.destination}"
        }
      }

      // Received message
      div {
        css {
          padding = Padding(15.px, 20.px)
          backgroundColor = rgb(227, 242, 253)
          borderBottom = Border(1.px, LineStyle.solid, rgb(236, 240, 241))
        }

        div {
          css {
            fontSize = 0.85.rem
            fontWeight = integer(600)
            color = rgb(33, 150, 243)
            marginBottom = 8.px
          }
          +"➡️ Received"
        }

        div {
          css {
            backgroundColor = Color("white")
            padding = 12.px
            borderRadius = 6.px
            fontFamily = string("'Courier New', monospace")
            fontSize = 0.9.rem
            color = rgb(52, 73, 94)
            wordWrap = WordWrap.breakWord
            whiteSpace = WhiteSpace.preWrap
            borderLeft = Border(3.px, LineStyle.solid, rgb(33, 150, 243))
          }
          +message.received
        }
      }

      // Sent message
      div {
        css {
          padding = Padding(15.px, 20.px)
          backgroundColor = rgb(255, 249, 230)
        }

        div {
          css {
            fontSize = 0.85.rem
            fontWeight = integer(600)
            color = rgb(243, 156, 18)
            marginBottom = 8.px
          }
          +"⬅️ Sent"
        }

        div {
          css {
            backgroundColor = Color("white")
            padding = 12.px
            borderRadius = 6.px
            fontFamily = string("'Courier New', monospace")
            fontSize = 0.9.rem
            color = rgb(52, 73, 94)
            wordWrap = WordWrap.breakWord
            whiteSpace = WhiteSpace.preWrap
            borderLeft = Border(3.px, LineStyle.solid, rgb(243, 156, 18))
          }
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
      css {
        padding = 30.px
        textAlign = TextAlign.center
        color = rgb(231, 76, 60)
      }
      p {
        css {
          fontSize = 1.2.rem
          fontWeight = integer(600)
        }
        +"⚠️ ${props.message}"
      }
    }
  }

/** Get a consistent color for a session ID. */
private fun getSessionColor(sessionId: String): String {
  val colors = listOf("#3498db", "#2ecc71", "#9b59b6", "#e74c3c", "#f39c12", "#1abc9c")
  return colors[sessionId.hashCode().mod(colors.size).let { if (it < 0) it + colors.size else it }]
}
