package io.github.cfraser.graphguard.validate

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class PatternsTest : FunSpec() {

  init {
    context("unlabeled node") {
      withData(
          null with "MATCH (n) RETURN n" expect "n",
          Patterns.UnlabeledNode(listOf(Regex("MATCH \\(n\\).+"))) with
              "MATCH (n) RETURN n" expect
              null,
          null with "MATCH (n:N)-->(z) RETURN n" expect "z",
          null with "MATCH (n:N)-[:R]->(z:Z)<--(n) RETURN n" expect null) {
              (validator, query, symbolicName) ->
            (validator ?: Patterns.UnlabeledNode()).validate(query, emptyMap()) shouldBe
                symbolicName?.let { _ -> Rule.Violation("Node '$symbolicName' is unlabeled") }
          }
    }
  }

  private companion object {

    infix fun Patterns.UnlabeledNode?.with(query: String) = this to query

    infix fun Pair<Patterns.UnlabeledNode?, String>.expect(symbolicName: String?) =
        Triple(first, second, symbolicName)
  }
}
