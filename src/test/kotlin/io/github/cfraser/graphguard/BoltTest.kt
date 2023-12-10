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
package io.github.cfraser.graphguard

import io.github.cfraser.graphguard.Bolt.toMessage
import io.github.cfraser.graphguard.Bolt.toStructure
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class BoltTest : FunSpec() {

  init {
    context("convert message") {
      withData(
          Bolt.Begin(emptyMap()) to PackStream.Structure(0x11, listOf(emptyMap<String, Any?>())),
          Bolt.Commit to PackStream.Structure(0x12, emptyList()),
          Bolt.Discard(emptyMap()) to PackStream.Structure(0x2f, listOf(emptyMap<String, Any?>())),
          Bolt.Failure(emptyMap()) to PackStream.Structure(0x7f, listOf(emptyMap<String, Any?>())),
          Bolt.Goodbye to PackStream.Structure(0x02, emptyList()),
          Bolt.Hello(emptyMap()) to PackStream.Structure(0x01, listOf(emptyMap<String, Any?>())),
          Bolt.Ignored to PackStream.Structure(0x7e, emptyList()),
          Bolt.Logoff to PackStream.Structure(0x6b, emptyList()),
          Bolt.Logon(emptyMap()) to PackStream.Structure(0x6a, listOf(emptyMap<String, Any?>())),
          Bolt.Pull(emptyMap()) to PackStream.Structure(0x3f, listOf(emptyMap<String, Any?>())),
          Bolt.Record(listOf()) to PackStream.Structure(0x71, listOf(listOf<Any?>())),
          Bolt.Reset to PackStream.Structure(0x0f, emptyList()),
          Bolt.Rollback to PackStream.Structure(0x13, emptyList()),
          Bolt.Run("", emptyMap(), emptyMap()) to
              PackStream.Structure(
                  0x10, listOf("", emptyMap<String, Any?>(), emptyMap<String, Any?>())),
          Bolt.Success(emptyMap()) to PackStream.Structure(0x70, listOf(emptyMap<String, Any?>())),
          Bolt.Telemetry(0) to PackStream.Structure(0x54, listOf(0L))) { (message, structure) ->
            message.toStructure() shouldBe structure
            structure.toMessage() shouldBe message
          }
    }
  }
}
