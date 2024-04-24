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
plugins {
  `java-library`
  `java-test-fixtures`
  `maven-publish`
  signing
}

dependencies {
  implementation(libs.kotlinx.coroutines)
  implementation(libs.kotlinx.coroutines.slf4j)
  implementation(libs.ktor.network)
  implementation(libs.slf4j.api)

  testImplementation(libs.kotest.datatest)
  testImplementation(libs.knit.test)
  testRuntimeOnly(libs.slf4j.nop)

  testFixturesApi(project(":graph-guard-schema"))
  testFixturesApi(project(":graph-guard-script"))
  testFixturesApi(libs.neo4j.java.driver)
  testFixturesApi(libs.testcontainers)
  testFixturesApi(libs.testcontainers.neo4j)
  testFixturesImplementation(libs.kotest.runner)
}
