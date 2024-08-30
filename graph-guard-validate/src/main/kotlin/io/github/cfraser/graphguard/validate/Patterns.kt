package io.github.cfraser.graphguard.validate

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
}
