package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.system.captureStandardOut
import io.kotest.matchers.shouldBe

class ScriptTest : FunSpec() {

  init {
    test("single plugin in script") {
      val plugin = Script.evaluate(MESSAGE_PRINTER)
      val message = Bolt.Goodbye
      captureStandardOut { plugin.intercept(message) } shouldBe "$message"
    }

    test("multiple plugins in script") {
      val plugin =
          Script.evaluate(
              """
              $MESSAGE_PRINTER
              $EVENT_PRINTER
              """
                  .trimIndent())
      val message = Bolt.Goodbye
      val event = Server.Stopped
      captureStandardOut {
        plugin.intercept(message)
        plugin.observe(event)
      } shouldBe "$message$event"
    }
  }

  private companion object {

    const val MESSAGE_PRINTER = "plugin { intercept { message -> message.also(::print) } }"
    const val EVENT_PRINTER = "plugin { observe { event -> print(event) } }"
  }
}
