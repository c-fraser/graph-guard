package io.github.cfraser.graphguard.cli

import io.github.cfraser.graphguard.LOCAL
import io.github.cfraser.graphguard.runMoviesQueries
import io.github.cfraser.graphguard.withNeo4j
import io.kotest.core.spec.style.FunSpec
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class MainTest : FunSpec() {

  init {
    test("run app").config(tags = setOf(LOCAL)) {
      withNeo4j {
        val proxy = thread {
          try {
            Command().main(arrayOf("-g", boltUrl))
          } catch (_: InterruptedException) {}
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
        try {
          runMoviesQueries(adminPassword)
        } finally {
          proxy.interrupt()
        }
      }
    }
  }
}
