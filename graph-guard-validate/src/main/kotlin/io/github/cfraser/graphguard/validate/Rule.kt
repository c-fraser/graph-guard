package io.github.cfraser.graphguard.validate

/** [validate] a [Cypher](https://opencypher.org/) query. */
fun interface Rule {

  /**
   * Validate the [cypher] and [parameters].
   *
   * @param cypher a *Cypher* query
   * @param parameters the [cypher] parameters
   * @return [Violation] if the [cypher] and [parameters] violate the [Rule], otherwise `null`
   */
  fun validate(cypher: String, parameters: Map<String, Any?>): Violation?

  /**
   * Run `this` [Rule] then [that].
   *
   * @param that the [Rule] to chain with `this`
   * @return a [Rule] that invokes `this` then [that]
   */
  infix fun then(that: Rule): Rule {
    return Rule { cypher, parameters ->
      validate(cypher, parameters)
      that.validate(cypher, parameters)
    }
  }

  /**
   * Exclude the *Cypher* query from [Rule] validation if it matches any of the [regexes].
   *
   * @param regexes the [Collection] of [Regex] to exclude
   * @return a [Rule] excluding matching *Cypher* statements
   */
  infix fun excludes(regexes: Collection<Regex>): Rule {
    return Rule { cypher, parameters ->
      cypher
          .takeIf { _ -> regexes.none { exclude -> cypher.let(exclude::matches) } }
          ?.let { _ -> validate(cypher, parameters) }
    }
  }

  /**
   * An [Violation] describes why a *Cypher* query violates a [Rule].
   *
   * @property message the description of the [Rule] violation
   */
  @JvmInline value class Violation(val message: String)
}
