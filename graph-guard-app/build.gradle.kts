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

plugins {
  application
  alias(libs.plugins.jib)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.shadow)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlinx.rpc)
}

kotlin { compilerOptions.freeCompilerArgs.add("-Xnested-type-aliases") }

application { mainClass.set("io.github.cfraser.graphguard.app.Main") }

jib { from { image = "eclipse-temurin:21" } }

dependencies {
  implementation(project(":graph-guard"))
  implementation(project(":graph-guard-script"))
  implementation(project(":graph-guard-web"))
  implementation(libs.caffeine)
  implementation(libs.clikt)
  implementation(libs.jackson)
  implementation(libs.kotlinx.coroutines)
  implementation(libs.kotlinx.rpc.krpc.server)
  implementation(libs.kotlinx.rpc.krpc.ktor.server)
  implementation(libs.kotlinx.rpc.krpc.serialization.json)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.websockets)
  implementation(libs.logback.classic)
  runtimeOnly(libs.logback.encoder)

  testImplementation(libs.kotest.assertions)
  testImplementation(libs.kotest.runner)
  testImplementation(testFixtures(project(":graph-guard")))
  testImplementation(libs.neo4j.test.harness) { exclude(module = "neo4j-slf4j-provider") }
}

buildConfig {
  packageName(
    "${project.group}.${project.name}".reversed().replaceFirst("-", ".").reversed().replace("-", "")
  )
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

  val setupWeb by
    registering(Copy::class) {
      val buildWeb = project(":graph-guard-web").tasks.named("jsBrowserDistribution")
      dependsOn(buildWeb)
      from(buildWeb.map { it.outputs })
      into(layout.buildDirectory.dir("resources/main/static"))
    }

  processResources { dependsOn(setupWeb) }
}
