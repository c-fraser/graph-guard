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
import com.diffplug.gradle.spotless.FormatExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.gitlab.arturbosch.detekt.Detekt
import kotlinx.knit.KnitPluginExtension
import kotlinx.validation.KotlinApiBuildTask
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jreleaser.gradle.plugin.JReleaserExtension
import org.jreleaser.gradle.plugin.tasks.JReleaserFullReleaseTask
import org.jreleaser.model.Active

buildscript {
  repositories { mavenCentral() }
  dependencies { classpath(libs.knit) }
}

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.dokka)
  alias(libs.plugins.detekt)
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.jreleaser)
  alias(libs.plugins.dependency.versions)
  alias(libs.plugins.kover)
  alias(libs.plugins.compatibility.validator)
  `java-library`
  `java-test-fixtures`
  `maven-publish`
  signing
}

apply(plugin = "kotlinx-knit")

allprojects project@{
  apply(plugin = "org.jetbrains.kotlin.jvm")

  group = "io.github.c-fraser"
  version = "0.8.2"

  configure<JavaPluginExtension> {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
  }

  tasks.withType<Jar> {
    manifest {
      val module = this@project.name.replaceFirst("-", "").replace("-", ".")
      attributes("Automatic-Module-Name" to "io.github.cfraser.$module")
    }
  }

  repositories { mavenCentral() }

  afterEvaluate {
    dependencies {
      testImplementation(libs.kotest.assertions)
      testImplementation(libs.kotest.runner)
    }

    tasks.withType<Test> { useJUnitPlatform() }
  }

  plugins.withType<DokkaPlugin> {
    tasks.withType<DokkaTask> {
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
            url.set("https://github.com/c-fraser/${rootProject.name}")
            inceptionYear.set("2023")

            issueManagement {
              system.set("GitHub")
              url.set("https://github.com/c-fraser/${rootProject.name}/issues")
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
              url.set("https://github.com/c-fraser/${rootProject.name}")
              connection.set("scm:git:git://github.com/c-fraser/${rootProject.name}.git")
              developerConnection.set(
                  "scm:git:ssh://git@github.com/c-fraser/${rootProject.name}.git")
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

dependencies {
  implementation(libs.kotlinx.coroutines)
  implementation(libs.kotlinx.coroutines.slf4j)
  implementation(libs.ktor.network)
  implementation(libs.slf4j.api)

  testImplementation(libs.kotest.datatest)
  testImplementation(libs.knit.test)
  testRuntimeOnly(libs.slf4j.nop)

  testFixturesApi(rootProject)
  testFixturesApi(project(":graph-guard-plugins"))
  testFixturesApi(libs.neo4j.java.driver)
  testFixturesApi(libs.testcontainers)
  testFixturesApi(libs.testcontainers.neo4j)
  testFixturesImplementation(libs.kotest.runner)
}

val kotlinSourceFiles by lazy {
  fileTree(rootProject.rootDir) {
    include("**/src/*.kt")
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
    ktfmt(ktfmtVersion)
    licenseHeader(licenseHeader)
    target(kotlinSourceFiles)
  }

  kotlinGradle {
    ktfmt(ktfmtVersion)
    licenseHeader(licenseHeader, "(import|buildscript|plugins|rootProject|@Suppress)")
    target(fileTree(rootProject.rootDir) { include("**/*.gradle.kts") })
  }

  antlr4 {
    antlr4Formatter()
    licenseHeader(licenseHeader)
    target("**/src/antlr/*.g4")
  }

  fun FormatExtension.pretty() =
      prettier()
          .config(
              mapOf("printWidth" to 100, "tabWidth" to 2, "semi" to false, "singleQuote" to true))

  fun ConfigurableFileTree.excludes() =
      exclude("**/bin/**", "**/build/**", "**/dist/**", "**/docs/**")

  format("prettier") {
    pretty()
    target(
        fileTree(rootProject.rootDir) {
          include("**/*.json", "**/*.yml")
          excludes()
        })
  }
}

configure<NexusPublishExtension> publish@{
  this@publish.repositories {
    sonatype {
      nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
      snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
      username.set(System.getenv("SONATYPE_USERNAME"))
      password.set(System.getenv("SONATYPE_PASSWORD"))
    }
  }
}

val cli = project(":graph-guard-cli")
val cliDist: Provider<RegularFile> = cli.layout.buildDirectory.file("distributions/${cli.name}.tar")

configure<JReleaserExtension> {
  project {
    authors.set(listOf("c-fraser"))
    license.set("Apache-2.0")
    extraProperties.put("inceptionYear", "2023")
    description.set("Extensible graph database proxy server")
    links { homepage.set("https://github.com/c-fraser/${rootProject.name}") }
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
            title.set(status.capitalizeAsciiOnly())
            labels.set(listOf(status))
          }
        }
      }
    }
    distributions { create(cli.name) { artifact { path.set(cliDist) } } }
  }
}

apiValidation { ignoredProjects += listOf(cli.name) }

configure<KnitPluginExtension> { files = files("README.md") }

tasks {
  val setupAsciinemaPlayer by creating {
    val css = file("docs/cli/asciinema-player.css")
    val js = file("docs/cli/asciinema-player.min.js")
    onlyIf { !css.exists() && !js.exists() }
    doLast {
      arrayOf(css, js).forEach { file ->
        exec {
          commandLine(
              "curl",
              "-L",
              "-o",
              file,
              "https://github.com/asciinema/asciinema-player/releases/download/v3.6.3/${file.name}")
        }
      }
      file("docs/cli/index.html")
          .writeText(
              """<!DOCTYPE html>
              <html lang='en'>
              <head>
                <meta charset='UTF-8'>
                <title>graph-guard-cli demo</title>
                <link rel='stylesheet' type='text/css' href='asciinema-player.css'/>
              </head>
              <body>
              <div id='demo'></div>
              <script src='asciinema-player.min.js'></script>
              <script>
                AsciinemaPlayer.create(
                    'demo.cast',
                    document.getElementById('demo'),
                    {autoPlay: true, loop: true, idleTimeLimit: 3});
              </script>
              </body>
              </html>"""
                  .trimIndent())
    }
  }

  val setupDocs by creating {
    dependsOn(setupAsciinemaPlayer, dokkaHtml, ":graph-guard-plugins:dokkaHtml")
    doLast {
      copy {
        from(dokkaHtml.get().outputDirectory)
        into(layout.projectDirectory.dir("docs/api"))
      }
      copy {
        val validatorDocs by project(":graph-guard-plugins").tasks.named<DokkaTask>("dokkaHtml")
        from(validatorDocs.outputDirectory)
        into(layout.projectDirectory.dir("docs/api/plugins"))
      }
      val docs = rootDir.resolve("docs/index.md")
      rootDir.resolve("README.md").copyTo(docs, overwrite = true)
      docs.writeText(
          docs
              .readText()
              // remove project header
              .replace("# graph-guard${System.lineSeparator()}", "# ${System.lineSeparator()}")
              // unqualify docs references
              .replace(Regex("\\(docs/.*\\)")) { it.value.replace("docs/", "") }
              // remove inline TOC
              .replace(
                  Regex(
                      "<!--- TOC -->[\\s\\S]*<!--- END -->[\\n|\\r|\\n\\r]", RegexOption.MULTILINE),
                  ""))
    }
  }

  spotlessApply { mustRunAfter(setupDocs) }

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
                .toTypedArray())
      }
  val spotlessKotlinGradle by getting(SpotlessTask::class) { mustRunAfter(spotlessKotlin) }
  val spotlessAntlr4 by getting(SpotlessTask::class) { mustRunAfter(spotlessKotlinGradle) }
  val spotlessPrettier by getting(SpotlessTask::class) { mustRunAfter(spotlessAntlr4) }

  val detektAll by creating(Detekt::class) { source = kotlinSourceFiles }

  withType<Detekt> {
    mustRunAfter(withType<SpotlessTask>())
    parallel = true
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(rootDir.resolve("detekt.yml"))
  }

  val releaseCli by creating {
    dependsOn(":graph-guard-cli:shadowDistTar")
    doLast {
      cli.layout.buildDirectory
          .file("distributions/${cli.name}-shadow-$version.tar")
          .map(RegularFile::getAsFile)
          .get()
          .copyTo(cliDist.map(RegularFile::getAsFile).get())
    }
  }

  withType<JReleaserFullReleaseTask> { dependsOn(releaseCli) }
}
