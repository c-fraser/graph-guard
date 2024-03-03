package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.properties.Delegates.notNull
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.onFailure
import kotlin.script.experimental.api.onSuccess
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.api.with
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
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
        defaultImports(DependsOn::class, Repository::class)
        implicitReceivers(Context::class)
        jvm {
          dependenciesFromCurrentContext(wholeClasspath = true)
          jvmTarget("17")
        }
        compilerOptions.append("-Xadd-modules=ALL-MODULE-PATH")
        ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
        refineConfiguration {
          onAnnotations(DependsOn::class, Repository::class, handler = ::resolveDependencies)
        }
      }) {

    private fun readResolve(): Any = Config
  }

  companion object {

    private val LOGGER = LoggerFactory.getLogger(Script::class.java)!!

    /** An [ExternalDependenciesResolver] for [Script] dependencies. */
    private val dependencyResolver =
        CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

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

    /** Resolve the dependencies, via the [Repository], that the [Script] [DependsOn]. */
    private fun resolveDependencies(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
      val annotations =
          context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf {
            it.isNotEmpty()
          } ?: return context.compilationConfiguration.asSuccess()
      return runBlocking(Dispatchers.IO) {
            dependencyResolver.resolveFromScriptSourceAnnotations(annotations)
          }
          .onFailure { result ->
            result
                .runCatching { valueOrThrow() }
                .getOrElse { ex -> LOGGER.error("Failed to resolve dependencies", ex) }
          }
          .onSuccess { files ->
            context.compilationConfiguration
                .with { dependencies.append(JvmDependency(files)) }
                .asSuccess()
          }
    }
  }
}
