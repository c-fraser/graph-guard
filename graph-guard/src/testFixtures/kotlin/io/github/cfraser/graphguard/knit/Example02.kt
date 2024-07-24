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
import io.github.cfraser.graphguard.validate.Schema
import io.github.cfraser.graphguard.plugin.Validator
import io.github.cfraser.graphguard.withNeo4j
import java.net.InetSocketAddress
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase

fun runExample02() {
  withNeo4j {

val plugin = Validator(Schema(MOVIES_SCHEMA))
val server = Server(boltURI(), plugin, InetSocketAddress("localhost", 8787))
server.use {
  GraphDatabase.driver("bolt://localhost:8787", Config.builder().withoutEncryption().build())
    .use(::runInvalidMoviesQueries)
}
  }
}