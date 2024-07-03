package io.github.cfraser.graphguard.validate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RuleTest : FunSpec() {

  init {
    test("validate rule chain") {
      val expected = Rule.Violation("invalid")
      val rule = Rule { _, _ -> null } then Rule { _, _ -> expected }
      rule.validate("", emptyMap()) shouldBe expected
    }
  }
}
