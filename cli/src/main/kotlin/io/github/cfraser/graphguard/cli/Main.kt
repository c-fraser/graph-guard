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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.time.LocalDateTime

/** [main] is the entry point for the [Server] application, run by the [Command]. */
fun main(args: Array<String>) {
  Command().main(args)
}

/** [Command] runs [Server] with the CLI input. */
internal class Command :
    CliktCommand(name = "graph-guard", help = "Graph schema validation proxy server") {

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
          option("--styled", help = "Enable styled output").flag().convert { Styled() })

  override fun run() {
    var plugin = schema?.let { Schema(it).Validator() } ?: object : Server.Plugin {}
    when (val output = output) {
      null -> {}
      Debug -> rootLogger.level = Level.DEBUG
      is Styled -> {
        rootLogger.detachAppender("STDOUT")
        plugin = plugin then output
        output.printBanner()
      }
    }
    Server(
            URI(graphUri),
            plugin = plugin,
            address = InetSocketAddress(hostname, port),
            parallelism = parallelism)
        .run()
  }

  /** [Command] output options. */
  private sealed interface Output

  /** [Debug] logging. */
  private data object Debug : Output

  /** [Styled] text output. */
  private inner class Styled : Output, Server.Plugin {

    /** The [Terminal] to use to display styled text/widgets in the console. */
    private val terminal by lazy { Terminal().apply { info.updateTerminalSize() } }

    /** An [ObjectMapper] to serialize the [Bolt.Run.parameters]. */
    private val objectMapper by lazy { jacksonObjectMapper() }

    /**
     * A [Regex] for the Cypher
     * [keywords](https://neo4j.com/docs/cypher-manual/current/syntax/reserved/).
     * > Refer to [clauses](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_clauses),
     * > [subclauses](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_subclauses),
     * > [modifiers](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_modifiers),
     * > [expressions](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_expressions),
     * > [operators](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_operators),
     * > [schema](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_schema),
     * > [hints](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_hints),
     * > [literals](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_literals), and keywords
     * > reserved for
     * > [future](https://neo4j.com/docs/cypher-manual/5/syntax/reserved/#_reserved_for_future_use)
     * > use.
     */
    private val cypherKeywordsRegex by lazy {
      val clauses =
          arrayOf(
              "call",
              "create",
              "delete",
              "detach",
              "foreach",
              "load",
              "match",
              "merge",
              "optional",
              "remove",
              "return",
              "set",
              "show",
              "start",
              "union",
              "unwind",
              "with",
          )
      val subclauses =
          arrayOf(
              "limit",
              "order",
              "skip",
              "where",
              "yield",
          )
      val modifiers =
          arrayOf(
              "asc",
              "ascending",
              "assert",
              "by",
              "csv",
              "desc",
              "descending",
              "on",
          )
      val expressions =
          arrayOf(
              "all",
              "case",
              "count",
              "else",
              "end",
              "exists",
              "then",
              "when",
          )
      val operators =
          arrayOf(
              "and",
              "as",
              "contains",
              "distinct",
              "ends",
              "in",
              "is",
              "not",
              "or",
              "starts",
              "xor",
          )
      val schema =
          arrayOf(
              "constraint",
              "create",
              "drop",
              "exists",
              "index",
              "node",
              "key",
              "unique",
          )
      val hints =
          arrayOf(
              "index",
              "join",
              "scan",
              "using",
          )
      val literals = arrayOf("false", "null", "true")
      val future =
          arrayOf(
              "add",
              "do",
              "for",
              "mandatory",
              "of",
              "require",
              "scalar",
          )
      // cypher keywords/commands relating to interacting with the DBMS
      val dbms =
          arrayOf(
              "built",
              "defined",
              "executable",
              "functions",
              "if",
              "procedures",
              "settings",
              "terminate",
              "transactions",
              "use",
              "user",
          )
      val keywords =
          clauses +
              subclauses +
              modifiers +
              expressions +
              operators +
              schema +
              hints +
              literals +
              future +
              dbms
      Regex("\\b(${keywords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
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

    override suspend fun observe(event: Server.Event) {
      if (event !is Server.Proxied) return
      val received = event.received
      if (received !is Bolt.Run) return
      val time = "${LocalDateTime.now()}".styled(TextColors.gray, TextStyles.underline.style)
      val source =
          "${event.source.address.hostString}:${event.source.address.port}"
              .styled(TextColors.gray, TextStyles.underline.style)
      val metadata = "$time $source"
      val message = received.styled()
      val output =
          when (val sent = event.sent) {
            is Bolt.Failure -> {
              val violation = "${sent.metadata["message"]}".styled(TextColors.brightRed)
              "❌ $metadata \"$violation\" $message"
            }
            else -> "✅ $metadata $message"
          }
      withContext(Dispatchers.IO) { terminal.println(output) }
    }

    /** Return the [Bolt.Run] message as a styled [String]. */
    private fun Bolt.Run.styled(): String {
      return "${parameters.styled()}\n${query.trim().styled(parameters)}\n"
    }

    /** Return the [Bolt.Run.parameters] as a styled [String]. */
    private fun Map<String, Any?>.styled(): String {
      val parameters =
          mapKeys { (key, _) -> key.styled(TextColors.brightMagenta) }
              .toList()
              .joinToString { (key, value) ->
                "$key: ${objectMapper.writeValueAsString(value).styled(TextColors.green)}"
              }
      return "${":params".styled(TextColors.brightBlue)} {$parameters}"
    }

    /** Style `this` [Bolt.Run.query]. */
    private fun String.styled(parameters: Map<String, Any?>): String {
      return parameters
          .toList()
          .fold(this) { query, (key, _) ->
            query.replace("\$$key", "\$$key".styled(TextColors.brightMagenta))
          }
          .replace(Regex("^.*//.*\$")) { it.value.styled(TextColors.gray) }
          .replace(cypherKeywordsRegex) { it.value.styled(TextColors.brightYellow) }
          .replace(Regex("(-->|<--|--|\\||!|&|\\+|-|\\*|/|%|^|=|<>|=~|<|>|<=|>=)")) {
            it.value.styled(TextColors.brightYellow)
          }
          .replace(Regex("\".*\"")) {
            it.value.reset(TextColors.brightYellow).styled(TextColors.green)
          }
          .replace(Regex("'.*'")) {
            it.value.reset(TextColors.brightYellow).styled(TextColors.green)
          }
          .replace(Regex("\\b[+-]?[\\d.]?\\d+\\b")) { it.value.styled(TextColors.green) }
          .let {
            Regex("\\s*:(\\w+)\\s*").findAll(it).fold(it) { query, match ->
              val label = checkNotNull(match.groups[1]).value
              query.replace(
                  Regex("\\b$label\\b"), label.styled(TextColors.cyan, TextStyles.italic.style))
            }
          }
    }

    /** Style `this` [String]. */
    private fun String.styled(style: TextStyle, vararg styles: TextStyle): String {
      return styles.fold(style, TextStyle::plus)(this)
    }

    /** Reset the [style] on `this` [String]. */
    private fun String.reset(style: TextStyle): String {
      val token = checkNotNull(Styled::class.qualifiedName)
      val tag = style(token).substringBefore(token)
      return replace(tag, "")
    }
  }

  private companion object {

    /** Get the *root* [Logger] or throw an [IllegalStateException]. */
    val rootLogger: Logger
      get() {
        return checkNotNull(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as? Logger) {
          "Failed to get root logger"
        }
      }
  }
}
