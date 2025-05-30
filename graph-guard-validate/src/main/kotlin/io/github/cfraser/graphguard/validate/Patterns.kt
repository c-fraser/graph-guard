package io.github.cfraser.graphguard.validate

import org.neo4j.cypherdsl.core.Literal
import org.neo4j.cypherdsl.parser.CypherParser

/**
 * [Rule] implementations enforcing or restricting certain
 * [patterns](https://neo4j.com/docs/cypher-manual/5/patterns/).
 */
object Patterns {

  /** A [Rule] that prevents *Cypher* statements with an [UnlabeledEntity]. */
  object UnlabeledEntity :
      Rule by Rule({ cypher, _ ->
        Query.parse(cypher)
            ?.entities
            ?.toList()
            ?.firstOrNull { (_, labels) -> labels.isEmpty() }
            ?.let { (symbolicName, _) -> Rule.Violation("Entity '$symbolicName' is unlabeled") }
      })

  /**
   * A [Rule] that prevents unparameterized queries, i.e. *Cypher* statements with *literal* values.
   * > Refer to the *AWS Neptune Analytics*
   * > [documentation](https://docs.aws.amazon.com/neptune-analytics/latest/userguide/best-practices-content.html#best-practices-content-2)
   * > for an explanation of the benefit of parameterized queries.
   */
  object UnparameterizedQuery :
      Rule by Rule({ cypher, _ ->
        try {
          val statement = CypherParser.parse(cypher)
          statement.catalog.literals
              .takeUnless(Collection<*>::isNullOrEmpty)
              ?.map(Literal<*>::asString)
              ?.let { literals -> Rule.Violation("Query has literals $literals") }
        } catch (_: Exception) {
          null
        }
      })
}
