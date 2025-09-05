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
package io.github.cfraser.graphguard.cli

import com.github.ajalt.clikt.core.main
import io.github.cfraser.graphguard.driver
import io.github.cfraser.graphguard.runMoviesQueries
import io.github.cfraser.graphguard.withNeo4j
import io.kotest.core.spec.style.FunSpec
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class MainTest : FunSpec() {

  init {
    test("run app") {
      withNeo4j {
        val proxy = thread {
          try {
            Command().main(arrayOf("-g", "${boltURI()}"))
          } catch (_: InterruptedException) {}
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
        try {
          driver().use(::runMoviesQueries)
        } finally {
          proxy.interrupt()
        }
      }
    }
  }
}
