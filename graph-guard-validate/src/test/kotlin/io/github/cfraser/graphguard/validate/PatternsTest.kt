package io.github.cfraser.graphguard.validate

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class PatternsTest : FunSpec() {

  init {
    context("validate patterns") {
      withData(
          Patterns.UnlabeledEntity with
              "MATCH (n) RETURN n" expect
              Rule.Violation("Entity 'n' is unlabeled"),
          Patterns.UnlabeledEntity excludes
              listOf(Regex("MATCH \\(n\\).+")) with
              "MATCH (n) RETURN n" expect
              null,
          Patterns.UnlabeledEntity with
              "MATCH (n:N)-->(z) RETURN n" expect
              Rule.Violation("Entity 'z' is unlabeled"),
          Patterns.UnlabeledEntity with "MATCH (n:N)-[:R]->(z:Z)<--(n) RETURN n" expect null,
          Patterns.UnlabeledEntity with
              "MATCH (n:N)-[r]->(z:Z) RETURN r" expect
              Rule.Violation("Entity 'r' is unlabeled"),
          Patterns.UnlabeledEntity excludes
              listOf(Regex("//\\s*graph-guard:exclude[\\w\\W]+")) with
              """// graph-guard:exclude
              MATCH (n:N)-[r]->(z:Z) RETURN r""" expect
              null,
          Patterns.UnparameterizedQuery with "MATCH (n) WHERE id(n) = \$id RETURN n" expect null,
          Patterns.UnparameterizedQuery with
              "MATCH (n) WHERE id(n) = \"n\" RETURN n" expect
              Rule.Violation("Query has literals ['n']"),
          Patterns.UnlabeledEntity with
              """
              MATCH (a:A)-[:R]->(b:B {b: 'b'}) 
              OPTIONAL MATCH (c:C)-->(b)
              SET c.c = 'c'
              RETURN b
              """
                  .trimIndent() expect
              null) { (rule, query, expected) ->
            rule.validate(query, emptyMap()) shouldBe expected
          }
    }
  }

  @IsStableType
  private data class Data(val rule: Rule, val query: String, val expected: Rule.Violation?)

  private companion object {

    infix fun Rule.with(query: String) = this to query

    infix fun Pair<Rule, String>.expect(violation: Rule.Violation?) = Data(first, second, violation)
  }
}
