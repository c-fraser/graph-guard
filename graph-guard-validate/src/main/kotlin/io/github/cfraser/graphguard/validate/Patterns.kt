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

  /**
   * A [Rule] that prevents *Cypher* statements with unparameterized values.
   * > Refer to the *AWS Neptune Analytics*
   * > [documentation](https://docs.aws.amazon.com/neptune-analytics/latest/userguide/best-practices-content.html#best-practices-content-2)
   * > for an explanation of the benefit of parameterized queries.
   */
  object UnparameterizedValue :
      Rule by Rule({ cypher, parameters -> TODO(cypher + "$parameters") })
}
