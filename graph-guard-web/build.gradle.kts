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
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlinx.rpc)
}

kotlin {
  jvm()
  js {
    browser { commonWebpackConfig { cssSupport { enabled.set(true) } } }
    binaries.executable()
  }

  sourceSets {
    commonMain {
      dependencies {
        api(libs.kotlinx.rpc.krpc.core)
        implementation(libs.kotlinx.serialization.json)
      }
    }

    @Suppress("unused")
    val jsMain by getting {
      dependencies {
        implementation(libs.ktor.client.js)
        implementation(libs.ktor.client.websockets)
        implementation(libs.kotlinx.rpc.krpc.client)
        implementation(libs.kotlinx.rpc.krpc.ktor.client)
        implementation(libs.kotlinx.rpc.krpc.serialization.json)
        implementation(libs.fritz2.core)
        implementation(libs.fritz2.headless)
      }
    }
  }
}

tasks {
  val copyLogos by
    registering(Copy::class) {
      from(
        project.rootDir.resolve("docs/favicon.ico"),
        project.rootDir.resolve("docs/graph-guard.png"),
        project.rootDir.resolve("docs/graph-guard-dark.png"),
      )
      into(project.projectDir.resolve("src/jsMain/resources"))
    }

  val downloadAssets by registering {
    val fontAwesomeUrl = "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.7.2"
    val fontAwesomeCss = project.projectDir.resolve("src/jsMain/resources/font-awesome.min.css")
    val fontAwesomeFontsDir =
      project.projectDir.resolve("src/jsMain/resources/webfonts").also(File::mkdirs)
    val highlightUrl = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0"
    val highlightCss = project.projectDir.resolve("src/jsMain/resources/github-dark.min.css")
    val highlightJs = project.projectDir.resolve("src/jsMain/resources/highlight.min.js")
    val highlightCypherJs = project.projectDir.resolve("src/jsMain/resources/cypher.min.js")

    outputs.files(fontAwesomeCss, highlightCss, highlightJs, highlightCypherJs)
    outputs.dir(fontAwesomeFontsDir)

    doLast {
      buildList {
          this += "$fontAwesomeUrl/css/all.min.css" to fontAwesomeCss
          listOf(
              "fa-solid-900.woff2",
              "fa-solid-900.ttf",
              "fa-regular-400.woff2",
              "fa-regular-400.ttf",
              "fa-brands-400.woff2",
              "fa-brands-400.ttf",
            )
            .forEach { font ->
              this += "$fontAwesomeUrl/webfonts/$font" to fontAwesomeFontsDir.resolve(font)
            }
          this += "$highlightUrl/styles/github-dark.min.css" to highlightCss
          this += "$highlightUrl/highlight.min.js" to highlightJs
          this += "https://unpkg.com/highlightjs-cypher/dist/cypher.min.js" to highlightCypherJs
        }
        .forEach { (url, file) ->
          uri(url).toURL().openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
          }
        }
    }
  }

  @Suppress("unused")
  val jsProcessResources by
    getting(ProcessResources::class) { dependsOn(copyLogos, downloadAssets) }
}
