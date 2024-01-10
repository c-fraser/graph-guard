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
import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.BuildConfig
import io.github.cfraser.graphguard.Schema
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.cli.Command.Styled.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI

/** [main] is the entry point for the [Server] application, run by the [Command]. */
fun main(args: Array<String>) {
  Command().main(args)
}

/** [Command] runs [Server] with the CLI input. */
internal class Command :
    CliktCommand(name = "graph-guard", help = "Graph schema validation proxy server"),
    Server.Plugin {

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

  private val schema by
      mutuallyExclusiveOptions(
          option("-s", "--schema", help = "The input stream with the graph schema text")
              .inputStream()
              .convert { stream -> stream.use { it.readBytes().toString(Charsets.UTF_8) } },
          option("-f", "--schema-file", help = "The file with the graph schema")
              .file(mustExist = true, mustBeReadable = true, canBeDir = false)
              .convert { it.readText() })

  private val parallelism by
      option(
              "-n",
              "--parallelism",
              help = "The number of parallel coroutines used by the proxy server")
          .int()

  private val output by
      mutuallyExclusiveOptions(
          option("--debug", help = "Enable debug logging").flag().convert { Debug },
          option("--styled", help = "Enable styled output").flag().convert { Styled })

  override fun run() {
    var plugin = schema?.let { Schema(it).Validator() } ?: object : Server.Plugin {}
    when (output) {
      null -> {}
      Debug -> rootLogger.level = Level.DEBUG
      Styled -> {
        rootLogger.detachAppender("STDOUT")
        plugin = plugin then this
        terminal.println(
            TextColors.brightBlue(
                """
                  _____                 _      _____                     _ 
                 / ____|               | |    / ____|                   | |
                | |  __ _ __ __ _ _ __ | |__ | |  __ _   _  __ _ _ __ __| |
                | | |_ | '__/ _` | '_ \| '_ \| | |_ | | | |/ _` | '__/ _` |
                | |__| | | | (_| | |_) | | | | |__| | |_| | (_| | | | (_| |
                 \_____|_|  \__,_| .__/|_| |_|\_____|\__,_|\__,_|_|  \__,_|
                                 | |                                       
                                 |_|                                       ${"v${BuildConfig.VERSION}".styled(Color.Server)}
                """
                    .trimIndent()))
      }
    }
    Server(
            URI(graphUri),
            plugin = plugin,
            address = InetSocketAddress(hostname, port),
            parallelism = parallelism)
        .run()
  }

  override suspend fun observe(event: Server.Event) {
    if (event !is Server.Proxied) return
    val received = event.received
    if (received !is Bolt.Run) return
    val source = event.source.styled()
    val parameters =
        received.parameters
            .mapKeys { (key, _) -> key.styled(Color.Parameters, TextStyles.bold.style) }
            .let { "$it" }
    val query =
        received.query
            .let {
              received.parameters.toList().fold(it) { query, (key, _) ->
                query.replace("\$$key", "\$$key".styled(Color.Parameters))
              }
            }
            .styled(Color.Query, TextStyles.italic.style)
    val output =
        when (val sent = event.sent) {
          is Bolt.Failure -> {
            val violation = "${sent.metadata["message"]}".styled(Color.SchemaViolation)
            "❌ $source \"$violation\" $parameters $query"
          }
          else -> "✅ $source $parameters $query"
        }
    withContext(Dispatchers.IO) { terminal.println(output) }
  }

  /** [Command] output options. */
  private sealed interface Output

  /** [Debug] logging. */
  private data object Debug : Output

  /** [Styled] text output. */
  private data object Styled : Output {

    /** Colors for the [Styled] output. */
    enum class Color(val style: TextStyle) {
      Server(TextColors.brightYellow),
      Client(TextColors.brightCyan),
      Graph(TextColors.brightGreen),
      Parameters(TextColors.brightMagenta),
      Query(TextColors.rgb("#b4832f")),
      SchemaViolation(TextColors.brightRed)
    }
  }

  private companion object {

    /** The [Terminal] to use to display styled text/widgets in the console. */
    val terminal by lazy { Terminal().apply { info.updateTerminalSize() } }

    /** Get the *root* [Logger] or throw an [IllegalStateException]. */
    val rootLogger: Logger
      get() {
        return checkNotNull(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as? Logger) {
          "Failed to get root logger"
        }
      }

    /** Return the [Server.Connection] as a styled [String]. */
    fun Server.Connection.styled(): String {
      val color =
          when (this) {
            is Server.Connection.Client -> Color.Client
            is Server.Connection.Graph -> Color.Graph
          }
      return "${address.hostString}:${address.port}".styled(color)
    }

    /** Style `this` [String]. */
    fun String.styled(color: Color, vararg styles: TextStyle): String {
      val style = styles.fold(color.style) { folded, style -> folded + style }
      return style(this)
    }
  }
}
