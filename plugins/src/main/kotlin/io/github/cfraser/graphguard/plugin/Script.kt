package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.Server
import org.slf4j.LoggerFactory
import kotlin.properties.Delegates.notNull
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * [Script] is a [KotlinScript] definition for dynamically compiling and instantiating a
 * [Server.Plugin].
 */
@KotlinScript(
    displayName = "graph-guard plugin script",
    fileExtension = "gg.kts",
    compilationConfiguration = Script.Config::class)
abstract class Script {

  /** The [Script.Context] exposes the [plugin] to the [evaluate]d [Script]. */
  class Context internal constructor() {

    /** The [Server.Plugin] to use. */
    internal var plugin by notNull<Server.Plugin>()

    /**
     * Build a [Server.Plugin] with the [builder] function then set the [plugin].
     * > If [plugin] is invoked multiple times in a [Script], [Server.Plugin.then] the built
     * > [Server.Plugin]s are chained in order of invocation.
     *
     * @param builder the function that builds the [Server.Plugin]
     */
    fun plugin(builder: Server.Plugin.Builder.() -> Unit) {
      var plugin = Server.Plugin.DSL.plugin(builder)
      try {
        plugin = this.plugin then plugin
      } catch (_: IllegalStateException) {}
      this.plugin = plugin
    }
  }

  /** The [ScriptCompilationConfiguration] for a plugin [Script]. */
  internal object Config :
      ScriptCompilationConfiguration({
        defaultImports("io.github.cfraser.graphguard.*")
        implicitReceivers(Context::class)
        jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
      }) {

    private fun readResolve(): Any = Config
  }

  companion object {

    private val LOGGER = LoggerFactory.getLogger(Script::class.java)!!

    /**
     * Evaluate the [Script] [source] text to instantiate a [Server.Plugin].
     *
     * @param source the script text
     * @return the initialized [Server.Plugin]
     * @throws IllegalArgumentException if the [source] is invalid
     */
    fun evaluate(source: String): Server.Plugin {
      return evaluate(source.toScriptSource())
    }

    /**
     * Evaluate the [Script] [sourceCode], using the [BasicJvmScriptingHost], to instantiate a
     * [Server.Plugin].
     */
    private fun evaluate(sourceCode: SourceCode): Server.Plugin {
      val host = BasicJvmScriptingHost()
      val context = Context()
      val result =
          host
              .evalWithTemplate<Script>(
                  sourceCode,
                  compilation = { jvm { dependenciesFromCurrentContext(wholeClasspath = true) } },
                  evaluation = { implicitReceivers(context) })
              .runCatching { valueOrThrow() }
              .getOrElse { ex ->
                throw IllegalArgumentException("Failed to evaluate plugin script", ex)
              }
      LOGGER.debug("Evaluated plugin script: {}", result)
      return try {
        context.plugin
      } catch (ex: IllegalStateException) {
        throw IllegalArgumentException("Invalid plugin script", ex)
      }
    }
  }
}
