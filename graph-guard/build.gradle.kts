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
  `java-library`
  `java-test-fixtures`
  `maven-publish`
  signing
}

dependencies {
  api(project(":graph-guard-validate"))
  implementation(kotlin("reflect"))
  implementation(libs.kotlinx.coroutines)
  implementation(libs.kotlinx.coroutines.slf4j)
  implementation(libs.ktor.network)
  implementation(libs.caffeine)
  implementation(libs.slf4j.api)

  testImplementation(libs.kotest.datatest)
  testImplementation(libs.knit.test)
  testRuntimeOnly(libs.neo4j.test.harness) { exclude(module = "neo4j-slf4j-provider") }
  testRuntimeOnly(libs.slf4j.nop)

  testFixturesApi(project(":graph-guard-script"))
  testFixturesApi(libs.neo4j.java.driver)
  testFixturesCompileOnly(libs.neo4j.test.harness)
  testFixturesImplementation(libs.kotest.runner)
}

tasks {
  withType<KotlinCompile> { compilerOptions { freeCompilerArgs.add("-Xcontext-receivers") } }
}
