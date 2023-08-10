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
package io.github.cfraser.graphguard.knit.test

import io.github.cfraser.graphguard.LOCAL
import io.kotest.core.spec.style.FunSpec
import kotlinx.knit.test.captureOutput
import kotlinx.knit.test.verifyOutputLines

class Example02Test : FunSpec() {

  init {
    tags(LOCAL)
    test("Example02") {
    captureOutput("Example02") { io.github.cfraser.graphguard.knit.runExample02() }.verifyOutputLines(
        "Unknown node TVShow",
        "Unknown property 'budget' for node Movie",
        "Unknown relationship WATCHED from Person to Movie",
        "Unknown property 'studio' for relationship PRODUCED from Person to Movie",
        "Invalid query value(s) '09/02/1964' for property 'born: Integer' on node Person"
  )
    }
  }
}
