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
