package io.github.cfraser.graphguard.validate

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class PatternsTest : FunSpec() {

  init {
    context("validate patterns") {
      withData(
          Patterns.UnlabeledEntity with "MATCH (n) RETURN n" expect "n",
          Patterns.UnlabeledEntity excludes
              listOf(Regex("MATCH \\(n\\).+")) with
              "MATCH (n) RETURN n" expect
              null,
          Patterns.UnlabeledEntity with "MATCH (n:N)-->(z) RETURN n" expect "z",
          Patterns.UnlabeledEntity with "MATCH (n:N)-[:R]->(z:Z)<--(n) RETURN n" expect null,
          Patterns.UnlabeledEntity with "MATCH (n:N)-[r]->(z:Z) RETURN r" expect "r",
          Patterns.UnlabeledEntity excludes
              listOf(Regex("//\\s*graph-guard:exclude[\\w\\W]+")) with
              """// graph-guard:exclude
              MATCH (n:N)-[r]->(z:Z) RETURN r""" expect
              null) { (rule, query, expected) ->
            rule.validate(query, emptyMap()) shouldBe
                expected?.let { _ -> Rule.Violation("Entity '$expected' is unlabeled") }
          }
    }
  }

  @IsStableType private data class Data(val rule: Rule, val query: String, val expected: String?)

  private companion object {

    infix fun Rule.with(query: String) = this to query

    infix fun Pair<Rule, String>.expect(symbolicName: String?) = Data(first, second, symbolicName)
  }
}
