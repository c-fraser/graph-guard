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

// This file was automatically generated by https://github.com/Kotlin/kotlinx-knit. DO NOT EDIT.
package io.github.cfraser.graphguard.knit

import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.runMoviesQueries
import io.github.cfraser.graphguard.withNeo4j
import java.net.URI

fun runExample08() {
  withNeo4j {

Server(
  URI(boltUrl),
  plugin { // define plugin using DSL
    intercept { message -> message.also(::println) }
    observe { event -> println(event) }
  })
  .use { TODO("interact with the running server") }
  }
}