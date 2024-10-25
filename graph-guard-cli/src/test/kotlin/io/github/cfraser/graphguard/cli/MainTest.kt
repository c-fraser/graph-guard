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
