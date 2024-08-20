package io.github.cfraser.graphguard.validate

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class PatternsTest : FunSpec() {

  init {
    context("unlabeled entity") {
      withData(
          null with "MATCH (n) RETURN n" expect "n",
          Patterns.UnlabeledEntity(listOf(Regex("MATCH \\(n\\).+"))) with
              "MATCH (n) RETURN n" expect
              null,
          null with "MATCH (n:N)-->(z) RETURN n" expect "z",
          null with "MATCH (n:N)-[:R]->(z:Z)<--(n) RETURN n" expect null,
          null with "MATCH (n:N)-[r]->(z:Z) RETURN r" expect "r",
          Patterns.UnlabeledEntity(listOf(Regex("//\\s*graph-guard:exclude[\\w\\W]+"))) with
              """// graph-guard:exclude
              MATCH (n:N)-[r]->(z:Z) RETURN r""" expect
              null) { (validator, query, expected) ->
            (validator ?: Patterns.UnlabeledEntity()).validate(query, emptyMap()) shouldBe
                expected?.let { _ -> Rule.Violation("Entity '$expected' is unlabeled") }
          }
    }
  }

  @IsStableType private data class Data(val rule: Rule?, val query: String, val expected: String?)

  private companion object {

    infix fun Patterns.UnlabeledEntity?.with(query: String) = this to query

    infix fun Pair<Patterns.UnlabeledEntity?, String>.expect(symbolicName: String?) =
        Data(first, second, symbolicName)
  }
}
