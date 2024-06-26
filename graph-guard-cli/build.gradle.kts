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
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.shadow)
}

application { mainClass.set("io.github.cfraser.graphguard.cli.Main") }

dependencies {
  implementation(project(":graph-guard"))
  implementation(project(":graph-guard-script"))
  implementation(libs.caffeine)
  implementation(libs.clikt)
  implementation(libs.jackson)
  implementation(libs.kotlinx.coroutines)
  implementation(libs.logback.classic)
  runtimeOnly(libs.logback.encoder)

  testImplementation(testFixtures(project(":graph-guard")))
  testImplementation(libs.neo4j.test.harness) { exclude(module = "neo4j-slf4j-provider") }
}

buildConfig {
  packageName(
      "${project.group}.${project.name}"
          .reversed()
          .replaceFirst("-", ".")
          .reversed()
          .replace("-", ""))
  buildConfigField(String::class.simpleName!!, "VERSION", "\"${project.version}\"")
}

tasks {
  withType<Test> {
    systemProperties =
        System.getProperties().asIterable().associate { it.key.toString() to it.value }
    testLogging { showStandardStreams = true }
  }

  val shadowJar =
      withType<ShadowJar> {
        archiveBaseName.set(null as String?)
        archiveClassifier.set(null as String?)
        archiveVersion.set(null as String?)
      }
  distZip { mustRunAfter(shadowJar) }
  distTar { mustRunAfter(shadowJar) }
  startScripts { mustRunAfter(shadowJar, ":spotlessKotlin") }
  startShadowScripts { mustRunAfter(jar, ":spotlessKotlin") }
}
