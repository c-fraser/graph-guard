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
  `maven-publish`
  signing
}

dependencies {
  compileOnly(project(":graph-guard"))
  implementation(kotlin("scripting-common"))
  implementation(kotlin("scripting-jvm"))
  implementation(kotlin("scripting-jvm-host"))
  implementation(kotlin("scripting-dependencies"))
  implementation(kotlin("scripting-dependencies-maven"))
  implementation(libs.kotlinx.coroutines)
  implementation(libs.slf4j.api)

  testImplementation(testFixtures(project(":graph-guard")))
  testRuntimeOnly(libs.slf4j.nop)
}
