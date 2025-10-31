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

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.app.Command.Output
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [Inspect] the [Bolt] traffic. */
internal data object Inspect : Output, Server.Plugin {

  private val colors =
    arrayOf(
      TextColors.brightBlue,
      TextColors.brightGreen,
      TextColors.brightMagenta,
      TextColors.brightRed,
      TextColors.brightWhite,
      TextColors.brightYellow,
    )
  private val sessions =
    Caffeine.newBuilder().maximumSize(colors.size.toLong()).build<Bolt.Session, TextColors>()

  override suspend fun intercept(session: Bolt.Session, message: Bolt.Message) = message

  override suspend fun observe(event: Server.Event) {
    if (event !is Server.Proxied) return
    val messages = event.styled()
    withContext(Dispatchers.IO) { messages.forEach(terminal::println) }
  }

  /** Style `this` [Server.Proxied] event. */
  private fun Server.Proxied.styled(): List<String> {
    fun Bolt.Message.styled() =
      when (this) {
        is Bolt.Request -> "➡\uFE0F  $this".styled(TextStyles.italic.style, TextColors.cyan)
        is Bolt.Response -> "⬅\uFE0F  $this".styled(TextStyles.bold.style, TextColors.yellow)
        else -> error(this)
      }
    val color =
      sessions[session, { _ -> colors[sessions.estimatedSize().toInt() % colors.lastIndex] }]
    @Suppress("RemoveExplicitTypeArguments")
    return buildList<Bolt.Message> {
        this += received
        this +=
          when (val sent = sent) {
            is Bolt.Messages -> sent.messages
            else -> listOf(sent)
          }
      }
      .distinct()
      .map { message -> "${session.id} ".styled(color) + message.styled() }
  }
}
