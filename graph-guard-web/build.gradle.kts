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
import com.github.gradle.node.npm.task.NpmTask

plugins {
  base
  alias(libs.plugins.node.gradle)
}

node {
  download = true
  version = "24.18.0"
}

tasks {
  val copyLogos by
    registering(Copy::class) {
      from(
        rootDir.resolve("docs/favicon.ico"),
        rootDir.resolve("docs/graph-guard.png"),
        rootDir.resolve("docs/graph-guard-dark.png"),
      )
      into(projectDir.resolve("src/public"))
    }

  val downloadAssets by registering {
    val fontAwesomeUrl = "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.7.2"
    val fontAwesomeCss = projectDir.resolve("src/public/font-awesome.min.css")
    val fontAwesomeFontsDir = projectDir.resolve("src/public/webfonts").also(File::mkdirs)

    outputs.files(fontAwesomeCss)
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
        }
        .forEach { (url, file) ->
          uri(url).toURL().openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
          }
        }
    }
  }

  @Suppress("unused")
  val generateTailwind by
    registering(NpmTask::class) {
      dependsOn(npmInstall)
      args = listOf("run", "generate")
      inputs.file("elm.json")
      inputs.file("package.json")
      inputs.file("tailwind.config.cjs")
      outputs.dir(projectDir.resolve("src/elm/Tailwind"))
    }

  val buildElm by
    registering(NpmTask::class) {
      dependsOn(npmInstall, copyLogos, downloadAssets)
      args = listOf("run", "build")
      inputs.dir("src")
      inputs.file("elm.json")
      inputs.file("package.json")
      inputs.file("vite.config.js")
      outputs.dir(layout.buildDirectory.dir("web"))
    }

  assemble { dependsOn(buildElm) }
}
