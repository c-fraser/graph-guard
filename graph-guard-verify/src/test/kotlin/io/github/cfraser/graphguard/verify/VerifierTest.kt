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
package io.github.cfraser.graphguard.verify

import io.github.cfraser.graphguard.driver
import io.github.cfraser.graphguard.utils.Internal
import io.github.cfraser.graphguard.validate.Schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders

@OptIn(Internal::class)
class VerifierTest : FunSpec() {

  init {
    val neo4j = Neo4jBuilders.newInProcessBuilder().build()

    beforeTest {
      neo4j.driver.use { d ->
        d.session().use { s -> s.run("MATCH (n) DETACH DELETE n").consume() }
      }
    }

    afterSpec { neo4j.close() }

    test("compliant graph has no violations") {
      neo4j.test("CREATE (:S1)-[:R]->(:S1T)", labels = listOf("S1")).shouldBeEmpty()
    }

    test("empty label batch has no violations") {
      neo4j.test(labels = emptyList()).shouldBeEmpty()
    }

    test("unknown label is reported") {
      neo4j.test(labels = listOf("Robot")).map { it.message } shouldBe listOf("Unknown node Robot")
    }

    test("unknown relationship is reported and deduplicated") {
      neo4j
        .test(
          "CREATE (:S1Q)-[:KNOWS]->(:S1QT)",
          "CREATE (:S1Q)-[:KNOWS]->(:S1QT)",
          labels = listOf("S1Q"),
        )
        .map { it.message } shouldBe listOf("Unknown relationship KNOWS from S1Q to S1QT")
    }

    test("Config.ignore drops matching violations") {
      neo4j
        .test(
          labels = listOf("Robot"),
          config = Verifier.Config(ignore = Regex(".*Robot.*")),
        )
        .shouldBeEmpty()
    }

    context("cardinality") {
      test("source side required (1) reports MIN and MAX violations") {
        neo4j
          .test(
            "CREATE (:S1)",
            "CREATE (:S1)-[:R]->(:S1T)",
            "CREATE (s:S1), (s)-[:R]->(:S1T), (s)-[:R]->(:S1T)",
            labels = listOf("S1"),
          )
          .map { it.message }
          .toSet() shouldBe
          setOf(
            "Cardinality violation: R SOURCE has 0 instances (MIN 1)",
            "Cardinality violation: R SOURCE has 2 instances (MAX 1)",
          )
      }

      test("source side optional (1?) reports only MAX violation") {
        neo4j
          .test(
            "CREATE (:S1Q)",
            "CREATE (:S1Q)-[:R]->(:S1QT)",
            "CREATE (s:S1Q), (s)-[:R]->(:S1QT), (s)-[:R]->(:S1QT)",
            labels = listOf("S1Q"),
          )
          .map { it.message } shouldBe
          listOf("Cardinality violation: R SOURCE has 2 instances (MAX 1)")
      }

      test("source side many (M) reports only MIN violation") {
        neo4j
          .test(
            "CREATE (:SM)",
            "CREATE (:SM)-[:R]->(:SMT)",
            "CREATE (s:SM), (s)-[:R]->(:SMT), (s)-[:R]->(:SMT)",
            labels = listOf("SM"),
          )
          .map { it.message } shouldBe
          listOf("Cardinality violation: R SOURCE has 0 instances (MIN 1)")
      }

      test("target side required (1) reports MIN and MAX violations") {
        neo4j
          .test(
            "CREATE (:T1T)",
            "CREATE (:T1)-[:R]->(:T1T)",
            "CREATE (t:T1T), (:T1)-[:R]->(t), (:T1)-[:R]->(t)",
            labels = listOf("T1T"),
          )
          .map { it.message }
          .toSet() shouldBe
          setOf(
            "Cardinality violation: R TARGET has 0 instances (MIN 1)",
            "Cardinality violation: R TARGET has 2 instances (MAX 1)",
          )
      }

      test("target side optional (1?) reports only MAX violation") {
        neo4j
          .test(
            "CREATE (:T1QT)",
            "CREATE (:T1Q)-[:R]->(:T1QT)",
            "CREATE (t:T1QT), (:T1Q)-[:R]->(t), (:T1Q)-[:R]->(t)",
            labels = listOf("T1QT"),
          )
          .map { it.message } shouldBe
          listOf("Cardinality violation: R TARGET has 2 instances (MAX 1)")
      }

      test("target side many (M) reports only MIN violation") {
        neo4j
          .test(
            "CREATE (:TMT)",
            "CREATE (:TM)-[:R]->(:TMT)",
            "CREATE (t:TMT), (:TM)-[:R]->(t), (:TM)-[:R]->(t)",
            labels = listOf("TMT"),
          )
          .map { it.message } shouldBe
          listOf("Cardinality violation: R TARGET has 0 instances (MIN 1)")
      }
    }
  }

  private companion object {

    val schema =
      Schema.init(
        """
        graph T {
          node S1: R [1..M?] -> S1T;
          node S1Q: R [1?..M?] -> S1QT;
          node SM: R [M..M?] -> SMT;
          node T1: R [M?..1] -> T1T;
          node T1Q: R [M?..1?] -> T1QT;
          node TM: R [M?..M] -> TMT;
          node S1T;
          node S1QT;
          node SMT;
          node T1T;
          node T1QT;
          node TMT;
        }
        """
      )

    /** Using the [Neo4j] instance, run the [cyphers], then [Verifier.verify] the [labels]. */
    fun Neo4j.test(
      vararg cyphers: String,
      labels: Collection<String>,
      config: Verifier.Config = Verifier.Config(),
    ): Collection<Verifier.Violation> = driver.use { d ->
      d.session().use { s -> cyphers.forEach { s.run(it).consume() } }
      runBlocking(Dispatchers.IO) { Verifier(d, schema, config).verify(labels) }
    }

    /** The [io.github.cfraser.graphguard.validate.Rule.Violation] message. */
    val Verifier.Violation.message: String
      get() = violation.violation.message
  }
}
