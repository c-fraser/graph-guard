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
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.runMoviesQueries
import io.github.cfraser.graphguard.withNeo4j
import java.net.URI
import kotlin.time.Duration.Companion.seconds

fun runExample09() {
  withNeo4j {

val script = """
@file:DependsOn(
    "io.github.resilience4j:resilience4j-ratelimiter:2.2.0",
    "io.github.resilience4j:resilience4j-kotlin:2.2.0")

import io.github.resilience4j.kotlin.ratelimiter.RateLimiterConfig
import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import java.util.concurrent.atomic.AtomicInteger

plugin {
  val rateLimiter = RateLimiter.of("message-limiter", RateLimiterConfig {})
  intercept { message -> rateLimiter.executeSuspendFunction { message } }
}

plugin { 
  val messages = AtomicInteger()
  intercept { message ->
    if (messages.getAndIncrement() == 0) println(message::class.simpleName)
    message
  }
}
"""
val plugin = Script.evaluate(script)
val server = Server(URI(boltUrl), plugin)
server.use(wait = 10.seconds) {
  runMoviesQueries(adminPassword)
}
  }
}
