package io.github.cfraser.graphguard.app

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
            App().main(arrayOf("-g", boltUrl))
          } catch (_: InterruptedException) {}
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
        try {
          runMoviesQueries()
        } finally {
          proxy.interrupt()
        }
      }
    }
  }
}
