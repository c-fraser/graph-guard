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
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import kotlinx.knit.KnitPlugin
import kotlinx.knit.KnitPluginExtension
import kotlinx.validation.KotlinApiBuildTask
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration
import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jreleaser.gradle.plugin.JReleaserExtension
import org.jreleaser.gradle.plugin.tasks.JReleaserFullReleaseTask
import org.jreleaser.model.Active

buildscript {
  repositories { mavenCentral() }
  dependencies { classpath(libs.knit) }
  configurations.classpath {
    resolutionStrategy {
      // See https://github.com/jreleaser/jreleaser/issues/1643
      force("org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r")
    }
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.jreleaser)
  alias(libs.plugins.dependency.versions)
  alias(libs.plugins.kover)
  alias(libs.plugins.compatibility.validator)
}

apply<KnitPlugin>()

allprojects {
  group = "io.github.c-fraser"
  version = "1.1.0"

  repositories { mavenCentral() }
}

val web = project(":graph-guard-web")

subprojects project@{
  if (this@project == web) apply(plugin = "org.jetbrains.kotlin.multiplatform")
  else {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    apply<DokkaPlugin>()

    configure<JavaPluginExtension> { withSourcesJar() }
  }

  configure<KotlinProjectExtension> {
    jvmToolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
  }

  apply<DetektPlugin>()

  tasks {
    withType<Jar> {
      manifest {
        val module = this@project.name.replaceFirst("-", "").replace("-", ".")
        attributes("Automatic-Module-Name" to "io.github.cfraser.$module")
      }

      withType<Detekt> {
        mustRunAfter(withType<AntlrTask>(), withType<SpotlessTask>())
        parallel = true
        buildUponDefaultConfig = true
        allRules = true
        config.setFrom(rootDir.resolve("detekt.yml"))
      }
    }
  }

  afterEvaluate {
    tasks.withType<Test> {
      useJUnitPlatform()
      systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
    }
  }

  plugins.withType<DokkaPlugin> {
    @Suppress("unused")
    val dokkaHtmlPartial by
      tasks.getting(DokkaTaskPartial::class) {
        outputDirectory.set(layout.buildDirectory.dir("docs/partial"))
      }
    tasks.withType<DokkaTaskPartial> {
      pluginConfiguration<DokkaBase, DokkaBaseConfiguration> {
        footerMessage = "Copyright &copy; 2023 c-fraser"
      }
    }
  }

  plugins.withType<MavenPublishPlugin> {
    configure<PublishingExtension> {
      val javadocJar by
        this@project.tasks.registering(Jar::class) {
          val dokkaJavadoc by this@project.tasks.getting(DokkaTask::class)
          dependsOn(dokkaJavadoc)
          archiveClassifier.set("javadoc")
          from(dokkaJavadoc.outputDirectory.get())
        }

      publications {
        create<MavenPublication>("maven") {
          from(this@project.components["java"])
          artifact(javadocJar)
          pom {
            name.set(this@project.name)
            description.set("${this@project.name}-${this@project.version}")
            url.set("https://github.com/c-fraser/graph-guard")
            inceptionYear.set("2023")

            issueManagement {
              system.set("GitHub")
              url.set("https://github.com/c-fraser/graph-guard/issues")
            }

            licenses {
              license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
              }
            }

            developers {
              developer {
                id.set("c-fraser")
                name.set("Chris Fraser")
              }
            }

            scm {
              url.set("https://github.com/c-fraser/graph-guard")
              connection.set("scm:git:git://github.com/c-fraser/graph-guard.git")
              developerConnection.set("scm:git:ssh://git@github.com/c-fraser/graph-guard.git")
            }
          }
        }
      }

      plugins.withType<SigningPlugin> {
        configure<SigningExtension> {
          publications.withType<MavenPublication>().all mavenPublication@{
            useInMemoryPgpKeys(System.getenv("GPG_SIGNING_KEY"), System.getenv("GPG_PASSWORD"))
            sign(this@mavenPublication)
          }
        }
      }
    }
  }
}

val kotlinSourceFiles by lazy {
  fileTree(rootProject.rootDir) {
    include("**/src/**/*.kt")
    // Exclude the files automatically generated by `kotlinx-knit`
    exclude("**/knit/**/*.kt")
  }
}

configure<SpotlessExtension> {
  val ktfmtVersion = libs.versions.ktfmt.get()
  val licenseHeader =
    """
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
    """
      .trimIndent()

  kotlin {
    ktfmt(ktfmtVersion).googleStyle()
    licenseHeader(licenseHeader)
    target(kotlinSourceFiles)
  }

  kotlinGradle {
    ktfmt(ktfmtVersion).googleStyle()
    licenseHeader(licenseHeader, "(import|buildscript|plugins|include|@Suppress)")
    target(fileTree(rootProject.rootDir) { include("**/*.gradle.kts") })
  }

  java {
    googleJavaFormat()
    licenseHeader(licenseHeader)
    target(
      fileTree(rootProject.rootDir) {
        include("**/src/**/*.java")
        exclude("**/validate/Schema*")
      }
    )
  }

  antlr4 {
    antlr4Formatter()
    licenseHeader(licenseHeader)
    target("**/src/main/antlr/*.g4")
  }

  format("prettier") {
    prettier()
      .config(mapOf("printWidth" to 100, "tabWidth" to 2, "semi" to false, "singleQuote" to true))
    target(
      fileTree(rootProject.rootDir) {
        include("**/extra.css", "**/index.html", "**/*.json", "**/*.yml")
        exclude("**/bin/**", "**/build/**", "**/dist/**", "**/docs/**")
      }
    )
  }
}

configure<NexusPublishExtension> publish@{
  this@publish.repositories {
    sonatype {
      nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
      snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
      username.set(System.getenv("SONATYPE_USERNAME"))
      password.set(System.getenv("SONATYPE_PASSWORD"))
    }
  }
}

val app = project(":graph-guard-app")
val appTar: Provider<RegularFile> =
  app.layout.buildDirectory.file("distributions/${app.name}-shadow.tar")
val appZip: Provider<RegularFile> =
  app.layout.buildDirectory.file("distributions/${app.name}-shadow.zip")
val appImage: Provider<RegularFile> =
  app.layout.buildDirectory.file("distributions/${app.name}-image.tar")

configure<JReleaserExtension> {
  project {
    authors.set(listOf("c-fraser"))
    license.set("Apache-2.0")
    inceptionYear.set("2023")
    description.set("Graph query validation proxy server")
    links { homepage.set("https://github.com/c-fraser/graph-guard") }
  }

  release {
    github {
      repoOwner.set("c-fraser")
      overwrite.set(true)
      token.set(System.getenv("GITHUB_TOKEN").orEmpty())
      changelog {
        formatted.set(Active.ALWAYS)
        format.set("- {{commitShortHash}} {{commitTitle}}")
        contributors.enabled.set(false)
        for (status in listOf("added", "changed", "fixed", "removed")) {
          labeler {
            label.set(status)
            title.set(status)
          }
          category {
            title.set(status.capitalized())
            labels.set(listOf(status))
          }
        }
      }
    }
    distributions {
      create(app.name) {
        artifact { path.set(appTar) }
        artifact { path.set(appZip) }
        brew {
          active.set(Active.NEVER)
          downloadUrl.set(
            "https://github.com/c-fraser/graph-guard/releases/latest/download/graph-guard-app.zip"
          )
          @Suppress("DEPRECATION")
          repository {
            active.set(Active.RELEASE)
            repoOwner.set("c-fraser")
            username.set("c-fraser")
            multiPlatform.set(true)
            token.set(System.getenv("GITHUB_TOKEN").orEmpty())
          }
        }
      }
    }
  }
}

apiValidation { ignoredProjects += listOf(app.name, web.name) }

configure<KnitPluginExtension> { files = files("README.md") }

tasks {
  clean {
    doLast {
      rootProject.allprojects
        .map { it.layout.buildDirectory.asFile.get().parentFile.resolve("bin") }
        .filter { it.exists() }
        .forEach { it.deleteRecursively() }
    }
  }

  withType<DokkaMultiModuleTask> {
    pluginConfiguration<DokkaBase, DokkaBaseConfiguration> {
      footerMessage = "Copyright &copy; 2023 c-fraser"
    }
  }

  val dokkaHtmlMultiModule by getting(DokkaMultiModuleTask::class)

  val setupDocs by registering {
    dependsOn(dokkaHtmlMultiModule)
    doLast {
      copy {
        from(dokkaHtmlMultiModule.outputDirectory)
        into(layout.projectDirectory.dir("docs/api"))
      }
      val docs = rootDir.resolve("docs/index.md")
      rootDir.resolve("README.md").copyTo(docs, overwrite = true)
      docs.writeText(
        docs
          .readText()
          // unqualify docs references
          .replace(Regex("\\(docs/.*\\)")) { it.value.replace("docs/", "") }
          .replace(Regex("src=\"docs/")) { "src=\"" }
          // remove inline TOC
          .replace(
            Regex("<!--- TOC -->[\\s\\S]*<!--- END -->[\\n|\\r|\\n\\r]", RegexOption.MULTILINE),
            "",
          )
      )
    }
  }

  val demoScript = rootDir.resolve("demo/client.py").toPath()

  val ruffCheck by
    registering(Exec::class) { commandLine("uvx", "ruff", "check", "--fix", demoScript) }

  val ruffFormat by registering(Exec::class) { commandLine("uvx", "ruff", "format", demoScript) }

  val spotlessPython by registering { dependsOn(ruffCheck, ruffFormat) }

  spotlessApply {
    finalizedBy(spotlessPython)
    mustRunAfter(setupDocs)
  }

  val spotlessKotlin by
    getting(SpotlessTask::class) {
      mustRunAfter(
        *rootProject.allprojects
          .flatMap {
            it.tasks.withType<KotlinCompile>() +
              it.tasks.withType<AntlrTask>() +
              it.tasks.withType<KotlinApiBuildTask>() +
              it.tasks.withType<Test>()
          }
          .toTypedArray(),
        provider {
          web.tasks.filter { task ->
            task.name.contains("Compile", ignoreCase = true) ||
              task.name.contains("Metadata", ignoreCase = true) ||
              task.name.contains("commonMain", ignoreCase = true)
          }
        },
      )
    }
  val spotlessKotlinGradle by getting(SpotlessTask::class) { mustRunAfter(spotlessKotlin) }
  val spotlessJava by getting(SpotlessTask::class) { mustRunAfter(spotlessKotlinGradle) }
  val spotlessAntlr4 by getting(SpotlessTask::class) { mustRunAfter(spotlessJava) }
  @Suppress("unused")
  val spotlessPrettier by getting(SpotlessTask::class) { mustRunAfter(spotlessAntlr4) }

  val releaseApp by registering {
    dependsOn(
      ":graph-guard-app:shadowDistTar",
      ":graph-guard-app:shadowDistZip",
      ":graph-guard-app:jibBuildTar",
    )
    doLast {
      arrayOf(appTar, appZip).forEach { dist ->
        app.layout.buildDirectory
          .file("distributions/${app.name}-shadow-$version.${dist.get().asFile.extension}")
          .map(RegularFile::getAsFile)
          .get()
          .copyTo(dist.map(RegularFile::getAsFile).get())
      }
      app.layout.buildDirectory
        .file("jib-image.tar")
        .map(RegularFile::getAsFile)
        .get()
        .copyTo(appImage.map(RegularFile::getAsFile).get())
    }
  }

  withType<JReleaserFullReleaseTask> { dependsOn(releaseApp) }
}
