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
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  antlr
  `java-library`
  `maven-publish`
  signing
}

dependencies {
  antlr(libs.antlr4)
  implementation(kotlin("reflect"))
  implementation(libs.kotlinx.coroutines)
  implementation(libs.caffeine)
  implementation(libs.neo4j.cypher.parser)
  implementation(libs.slf4j.api)

  testImplementation(testFixtures(project(":graph-guard")))
  testImplementation(libs.neo4j.test.harness) { exclude(module = "neo4j-slf4j-provider") }
}

tasks {
  val modifyGrammarSource by registering {
    mustRunAfter(withType<AntlrTask>())
    doLast {
      fileTree(generateGrammarSource.get().outputDirectory) { include("*.java") }
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
    outputDirectory = file("src/main/java/io/github/cfraser/graphguard/validate")
  }

  withType<KotlinCompile> { dependsOn(withType<AntlrTask>()) }

  withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
  named("sourcesJar") { dependsOn(withType<AntlrTask>()) }

  withType<DokkaTask> { mustRunAfter(withType<AntlrTask>()) }
  withType<DokkaTaskPartial> { mustRunAfter(withType<AntlrTask>()) }
}
