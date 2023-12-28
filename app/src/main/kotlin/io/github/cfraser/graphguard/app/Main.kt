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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.inputStream
import com.github.ajalt.clikt.parameters.types.int
import io.github.cfraser.graphguard.BuildConfig
import io.github.cfraser.graphguard.Schema
import io.github.cfraser.graphguard.Server
import java.net.InetSocketAddress
import java.net.URI

/** [main] is the entry point for the [Server] application. */
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
      option("-h", "--hostname", help = "The hostname to bind the proxy server to")
          .default("localhost")

  private val port by
      option("-p", "--port", help = "The port to bind the proxy server to").int().default(8787)

  private val graphUri by
      option("-g", "--graph", help = "The Bolt URI of the graph to guard")
          .default("bolt://localhost:7687")
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
          .required()

  override fun run() {
    Server(URI(graphUri), Schema(schema).Validator(), address = InetSocketAddress(hostname, port))
        .run()
  }
}
