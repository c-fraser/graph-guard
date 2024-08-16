package io.github.cfraser.graphguard.validate

/**
 * [Rule] implementations enforcing or restricting certain
 * [patterns](https://neo4j.com/docs/cypher-manual/5/patterns/).
 */
object Patterns {

  /**
   * A [Rule] that prevents *Cypher* statements with an [UnlabeledNode].
   *
   * @param excludes do **not** [Rule.validate] for [UnlabeledNode] if the *Cypher* statement
   *   matches any of the [Regex]es
   */
  class UnlabeledNode(excludes: Collection<Regex> = emptyList()) :
      Rule by Rule({ cypher, _ ->
        cypher
            .takeUnless { _ -> excludes.any { exclude -> exclude.matches(cypher) } }
            ?.let { _ -> Query.parse(cypher) }
            ?.entities
            ?.toList()
            ?.firstOrNull { (_, labels) -> labels.isEmpty() }
            ?.let { (symbolicName, _) -> Rule.Violation("Node '$symbolicName' is unlabeled") }
      })
}
