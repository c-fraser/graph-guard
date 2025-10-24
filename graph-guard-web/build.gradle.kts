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
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlinx.rpc)
}

kotlin {
  js {
    browser { commonWebpackConfig { cssSupport { enabled.set(true) } } }
    binaries.executable()
  }

  sourceSets {
    @Suppress("unused")
    val jsMain by getting {
      dependencies {
        implementation(project(":graph-guard-rpc"))
        implementation(libs.ktor.client.js)
        implementation(libs.ktor.client.websockets)
        implementation(libs.kotlinx.rpc.krpc.client)
        implementation(libs.kotlinx.rpc.krpc.ktor.client)
        implementation(libs.kotlinx.rpc.krpc.serialization.json)
        implementation(libs.kotlin.js)
        implementation(libs.kotlin.react)
        implementation(libs.kotlin.react.dom)
      }
    }
  }
}

tasks {
  val copyLogos by
    registering(Copy::class) {
      from(
        project.rootDir.resolve("docs/favicon.ico"),
        project.rootDir.resolve("docs/graph-guard-dark.png"),
        project.rootDir.resolve("docs/graph-guard-light.png"),
      )
      into("${project.projectDir}/src/jsMain/resources")
    }

  @Suppress("unused")
  val jsProcessResources by getting(ProcessResources::class) { dependsOn(copyLogos) }
}
