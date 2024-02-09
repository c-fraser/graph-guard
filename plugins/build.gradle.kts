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
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.dokka)
  antlr
  `java-library`
  `maven-publish`
  signing
}

dependencies {
  antlr(libs.antlr4)
  implementation(rootProject)
  implementation(kotlin("reflect"))
  implementation(libs.caffeine)
  implementation(libs.neo4j.cypher.parser)
  implementation(libs.slf4j.api)

  testImplementation(testFixtures(rootProject))
  testImplementation(libs.kotest.datatest)
  testRuntimeOnly(libs.slf4j.nop)
}

tasks {
  val grammarSrcDir =
      project.name.replaceFirst("-", "").replace("-", ".").removeSuffix("s").let {
        file("src/main/java/io/github/cfraser/$it")
      }

  val modifyGrammarSource by creating {
    mustRunAfter(withType<AntlrTask>())
    doLast {
      fileTree(grammarSrcDir) { include("*.java") }
          .forEach { file ->
            file.writeText(
                file
                    .readText()
                    // reduce visibility of generated types
                    .replace("public class", "class")
                    .replace("public interface", "interface"))
          }
    }
  }

  generateGrammarSource {
    finalizedBy(modifyGrammarSource)
    outputDirectory = grammarSrcDir
  }

  withType<KotlinCompile> {
    dependsOn(withType<AntlrTask>())
    kotlinOptions { freeCompilerArgs = freeCompilerArgs + listOf("-Xcontext-receivers") }
  }
  named("sourcesJar") { dependsOn(withType<AntlrTask>()) }
}
