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

package io.github.cfraser.graphguard.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.default
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
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.plugin.Validator
import io.github.cfraser.graphguard.validate.Schema
import java.net.URI
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

  private val graph by
    option("-g", "--graph", help = "The Bolt URI of the graph to guard")
      .default("bolt://127.0.0.1:7687")
      .validate { uri ->
        uri.runCatching(::URI).getOrElse { _ -> fail("Graph URI '$uri' is invalid") }
      }

  private val address by
    mutuallyExclusiveOptions(
        option("--address", help = $$"The $host:$port to bind the proxy server to").convert {
          address ->
          val (host, port) = address.split(":", limit = 2)
          Server.Address.InetSocket(
            host,
            port.toIntOrNull() ?: fail("Port number '$port' is invalid"),
          )
        },
        option(
            "--path",
            help = "The path of the unix domain socket file to bind the proxy server to",
          )
          .path(canBeDir = false)
          .convert { path ->
            if (path.exists()) fail("The unix domain socket file '$path' already exists")
            Server.Address.UnixDomainSocket(path)
          },
        help = "The address the bind the proxy server to",
      )
      .default(Server.Address.InetSocket("0.0.0.0", 8787))

  private val schema by
    mutuallyExclusiveOptions(
      option("-s", help = "The input stream with the graph schema text").inputStream().convert {
        stream ->
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
      },
      option("--schema", help = "The graph schema file")
        .file(mustExist = true, mustBeReadable = true, canBeDir = false)
        .convert { it.readText() },
      help = "The graph schema for the proxy server to validate",
    )

  private val script by
    mutuallyExclusiveOptions(
      option("-k", help = "The input stream with the plugin script text").inputStream().convert {
        stream ->
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
      },
      option("--script", help = "The plugin script file")
        .file(mustExist = true, mustBeReadable = true, canBeDir = false)
        .convert { it.readText() },
      help = "The plugin script for the proxy server to use",
    )

  private val debug by option("--debug", help = "Enable debug logging").flag()

  private val output by
    mutuallyExclusiveOptions<Output>(
      option("--inspect", help = "Inspect proxied Bolt messages").flag().convert { Inspect },
      option("--web", help = "Run the web application").int().convert { port ->
        Web(port, lazy { schema })
      },
    )

  override fun run() {
    val plugin by lazy {
      listOfNotNull(script?.let(Script::evaluate), schema?.let(::Schema)?.let(::Validator)).chain()
    }
    if (debug) logger("io.github.cfraser.graphguard").level = Level.DEBUG
    val output =
      when (val output = output) {
        null -> {
          plugin
        }
        is Inspect -> {
          logger(Logger.ROOT_LOGGER_NAME).detachAppender("STDOUT")
          printBanner()
          plugin then output
        }
        is Web -> {
          output.server.start()
          printBanner()
          terminal.println(
            System.lineSeparator() +
              "Running web application: " +
              "http://localhost:${output.port}"
                .styled(TextColors.brightCyan, TextStyles.underline.style) +
              System.lineSeparator()
          )

          Web.PluginLoader.run {
            script?.also { script -> runBlocking(Dispatchers.IO) { load(script) } }
            schema?.let(::Schema)?.let(::Validator)?.let { plugin -> this then plugin } ?: this
          } then output
        }
      }
    Server(URI(graph), output, address).use { server ->
      server.start()
      Thread.currentThread().join()
    }
  }

  override fun help(context: Context) = "Graph query validation proxy server"

  /** [Command] output options. */
  sealed interface Output

  private companion object {

    /** Get the [Logger] with the [name] or throw an [IllegalStateException]. */
    fun logger(name: String): Logger =
      checkNotNull(LoggerFactory.getLogger(name) as? Logger) { "Failed to get root logger" }

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
          .styled(TextColors.brightBlue)
      )
    }
  }
}

/** The [Terminal] to use to display styled text/widgets in the console. */
internal val terminal by lazy { Terminal().apply(Terminal::updateSize) }

/** Style `this` [String]. */
internal fun String.styled(style: TextStyle, vararg styles: TextStyle): String =
  styles.fold(style, TextStyle::plus)(this)

/** Chain the [Collection] of [Server.Plugin] with [Server.Plugin.then]. */
internal fun Collection<Server.Plugin>.chain(): Server.Plugin = fold(plugin {}, Server.Plugin::then)
