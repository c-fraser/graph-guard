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
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.inputStream
import com.github.ajalt.clikt.parameters.types.int
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.cfraser.graphguard.BuildConfig
import io.github.cfraser.graphguard.Schema
import io.github.cfraser.graphguard.Server
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import java.net.InetSocketAddress
import java.net.URI
import java.time.LocalDateTime
import org.slf4j.LoggerFactory

/** [main] is the entry point for the [Server] application. */
fun main(args: Array<String>) {
  App().main(args)
}

/** [App] runs [Server] with the CLI input. */
internal class App :
    CliktCommand(name = "graph-guard", help = "Graph schema validation proxy server") {

  init {
    versionOption(BuildConfig.VERSION)
  }

  private val hostname by
      option("-h", "--hostname", help = "The hostname to bind the proxy (and web) server to")
          .default("0.0.0.0")

  private val port by
      option("-p", "--port", help = "The port to bind the proxy server to").int().default(8787)

  private val web by
      option("-w", "--web", help = "The port to find the web server to").int().default(8080)

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

  override fun run() {
    val webServer =
        embeddedServer(Netty, host = hostname, port = web) {
              LoggingMeterRegistry().installMetrics()
              TODO().configureRoutes()
            }
            .start()
    @Suppress("HttpUrlsUsage") LOGGER.info("Started web server on http://{}:{}", hostname, port)
    try {
      val schemaValidator = schema?.let { Schema(it).Validator() } ?: object : Server.Plugin {}
      Server(
              URI(graphUri),
              plugin = schemaValidator,
              address = InetSocketAddress(hostname, port),
              parallelism = parallelism)
          .run()
    } finally {
      webServer.stop()
    }
  }

  /** [App.Service] stores and provides access to [Server] data. */
  interface Service {

    /** Store a [query] proxied by the [Server]. */
    suspend fun putQuery(query: Query)

    /** Return the queries proxied by the [Server]. */
    suspend fun getQueries(): Collection<Query>
  }

  /** A parameterized [cypher] [Query] intercepted by the [Server]. */
  data class Query(val cypher: String, val parameters: Map<String, Any?>, val runAt: LocalDateTime)

  private companion object {

    private val LOGGER = LoggerFactory.getLogger(App::class.java)

    /** Install metrics in the [Application] with the [MeterRegistry]. */
    context(Application)
    fun MeterRegistry.installMetrics() {
      install(MicrometerMetrics) {
        registry = this@installMetrics
        meterBinders =
            listOf(
                ClassLoaderMetrics(),
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                ProcessorMetrics(),
                JvmThreadMetrics(),
                FileDescriptorMetrics(),
                UptimeMetrics())
      }
    }

    /** Configure the [Application.routing]. */
    context(Application)
    fun Service.configureRoutes() {
      install(ContentNegotiation) { json() }
      routing {
        singlePageApplication {
          useResources = true
          filesPath = "web"
          defaultPage = "index.html"
        }
        route("/v0") { get { call.respond(TODO()) } }
      }
    }
  }
}
