package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.LOCAL
import io.github.cfraser.graphguard.Server
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.system.captureStandardOut
import io.kotest.matchers.shouldBe

class ScriptTest : FunSpec() {

  init {
    test("single plugin in script") {
      val plugin = Script.evaluate(MESSAGE_PRINTER)
      val message = Bolt.Goodbye
      captureStandardOut { plugin.intercept(Bolt.Session(""), message) } shouldBe "$message"
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
        plugin.intercept(Bolt.Session(""), message)
        plugin.observe(event)
      } shouldBe "$message$event"
    }

    test("script with dependencies").config(tags = setOf(LOCAL)) {
      val plugin =
          Script.evaluate(
              """
              @file:DependsOn("io.arrow-kt:arrow-core:1.2.0")
              
              import arrow.core.Either
              import arrow.core.raise.either
              import arrow.core.right
                
              plugin {
                fun process(message: Bolt.Message): Either<Nothing, Bolt.Message> = message.right().onRight(::print)
                intercept { _, message -> either { process(message).bind() }.getOrNull().let(::checkNotNull) }
              }
              """
                  .trimIndent())
      val message = Bolt.Hello(emptyMap())
      captureStandardOut { plugin.intercept(Bolt.Session(""), message) } shouldBe "$message"
    }
  }

  private companion object {

    const val MESSAGE_PRINTER = "plugin { intercept { _, message -> message.also(::print) } }"
    const val EVENT_PRINTER = "plugin { observe { event -> print(event) } }"
  }
}
