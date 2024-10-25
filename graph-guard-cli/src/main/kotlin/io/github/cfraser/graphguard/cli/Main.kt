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
@file:JvmName("Main")

package io.github.cfraser.graphguard.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.inputStream
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.plugin.Validator
import io.github.cfraser.graphguard.validate.Schema
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** [main] is the entry point for the [Server] application, run by the [Command]. */
fun main(args: Array<String>) {
  Command().main(args)
}

/** [Command] runs [Server] with the CLI input. */
internal class Command : CliktCommand(name = "graph-guard") {

  init {
    versionOption(BuildConfig.VERSION)
  }

  private val hostname by
      option("-h", "--hostname", help = "The hostname to bind the proxy (and web) server to")
          .default("0.0.0.0")

  private val port by
      option("-p", "--port", help = "The port to bind the proxy server to").int().default(8787)

  private val graphUri by
      option("-g", "--graph", help = "The Bolt URI of the graph to guard")
          .default("bolt://127.0.0.1:7687")
          .validate { uri ->
            uri.runCatching(::URI).getOrElse { _ -> fail("Graph URI '$uri' is invalid") }
          }

  private val parallelism by
      option(
              "-n",
              "--parallelism",
              help = "The number of parallel coroutines used by the proxy server")
          .int()

  private val schema by
      mutuallyExclusiveOptions(
          option("-s", help = "The input stream with the graph schema text")
              .inputStream()
              .convert { stream -> stream.use { it.readBytes().toString(Charsets.UTF_8) } },
          option("--schema", help = "The graph schema file")
              .file(mustExist = true, mustBeReadable = true, canBeDir = false)
              .convert { it.readText() })

  private val script by
      mutuallyExclusiveOptions(
          option("-k", help = "The input stream with the plugin script text")
              .inputStream()
              .convert { stream -> stream.use { it.readBytes().toString(Charsets.UTF_8) } },
          option("--script", help = "The plugin script file")
              .file(mustExist = true, mustBeReadable = true, canBeDir = false)
              .convert { it.readText() })

  private val output by
      mutuallyExclusiveOptions(
          option("--debug", help = "Enable debug logging").flag().convert { Debug },
          option("--inspect", help = "Inspect proxied Bolt messages").flag().convert { Inspect })

  override fun run() {
    var plugin =
        listOfNotNull(schema?.let(::Schema)?.let { Validator(it) }, script?.let(Script::evaluate))
            .fold(plugin {}, Server.Plugin::then)
    when (val output = output) {
      null -> {}
      Debug -> {
        logger("io.github.cfraser.graphguard").level = Level.DEBUG
      }
      is Inspect -> {
        logger(Logger.ROOT_LOGGER_NAME).detachAppender("STDOUT")
        plugin = plugin then checkNotNull(output as? Server.Plugin)
        printBanner()
      }
    }
    Server(
            URI(graphUri),
            plugin = plugin,
            address = InetSocketAddress(hostname, port),
            parallelism = parallelism)
        .run()
  }

  override fun help(context: Context) = "Graph query validation proxy server"

  /** [Command] output options. */
  private sealed interface Output

  /** [Debug] logging. */
  private data object Debug : Output

  /** [Inspect] the [Bolt] traffic. */
  private data object Inspect : Output, Server.Plugin {

    private val colors =
        arrayOf(
            TextColors.brightBlue,
            TextColors.brightGreen,
            TextColors.brightMagenta,
            TextColors.brightRed,
            TextColors.brightWhite,
            TextColors.brightYellow)
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

  private companion object {

    /** The [Terminal] to use to display styled text/widgets in the console. */
    private val terminal by lazy { Terminal().apply(Terminal::updateSize) }

    /** Get the [Logger] with the [name] or throw an [IllegalStateException]. */
    fun logger(name: String): Logger {
      return checkNotNull(LoggerFactory.getLogger(name) as? Logger) { "Failed to get root logger" }
    }

    /** Print the styled ASCII text banner. */
    fun printBanner() {
      terminal.println(
          """
            _____                 _      _____                     _ 
           / ____|               | |    / ____|                   | |
          | |  __ _ __ __ _ _ __ | |__ | |  __ _   _  __ _ _ __ __| |
          | | |_ | '__/ _` | '_ \| '_ \| | |_ | | | |/ _` | '__/ _` |
          | |__| | | | (_| | |_) | | | | |__| | |_| | (_| | | | (_| |
           \_____|_|  \__,_| .__/|_| |_|\_____|\__,_|\__,_|_|  \__,_|
                           | |                                       
                           |_|                                       ${"v${BuildConfig.VERSION}".styled(TextColors.brightYellow)}
          """
              .trimIndent()
              .styled(TextColors.brightBlue))
    }

    /** Style `this` [String]. */
    private fun String.styled(style: TextStyle, vararg styles: TextStyle): String {
      return styles.fold(style, TextStyle::plus)(this)
    }
  }
}
