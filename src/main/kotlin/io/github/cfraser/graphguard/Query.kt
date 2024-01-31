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
package io.github.cfraser.graphguard

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.FunctionInvocation
import org.neo4j.cypherdsl.core.KeyValueMapEntry
import org.neo4j.cypherdsl.core.ListExpression
import org.neo4j.cypherdsl.core.Literal
import org.neo4j.cypherdsl.core.NodeBase
import org.neo4j.cypherdsl.core.NodeLabel
import org.neo4j.cypherdsl.core.NullLiteral
import org.neo4j.cypherdsl.core.Operation
import org.neo4j.cypherdsl.core.Operator
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.cypherdsl.core.PatternElement
import org.neo4j.cypherdsl.core.RelationshipBase
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementCatalog
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.cypherdsl.core.ast.Visitable
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.ExpressionCreatedEventType
import org.neo4j.cypherdsl.parser.Options
import org.neo4j.cypherdsl.parser.PatternElementCreatedEventType
import kotlin.jvm.optionals.getOrNull

/**
 * A [Cypher](https://neo4j.com/docs/cypher-manual/current/introduction/) [Query].
 *
 * @property nodes the node labels in the [Query]
 * @property relationships the relationships in the [Query]
 * @property properties the properties in the [Query]
 * @property mutatedProperties the mutated properties in the [Query]
 */
internal data class Query(
    val nodes: Set<String>,
    val relationships: Set<Relationship>,
    val properties: Set<Property>,
    val mutatedProperties: Set<MutatedProperty>
) {

  /** A [Relationship] with the [label] from the [source] to the [target]. */
  data class Relationship(val label: String, val source: String?, val target: String?)

  /** A [Property] of the [owner] with the [name] and [values]. */
  data class Property(val owner: String?, val name: String, val values: Set<Type>) {

    /** A type of [Property] value. */
    sealed interface Type {

      /** The [cypher] [value] of a [Property]. */
      data class Value(val value: Any?, val cypher: String? = null) : Type

      /** The [values] in a [Property] [Container]. */
      data class Container(val values: List<Any?>) : Type

      /** A [Type] that is [Resolvable] via the [name]. */
      data class Resolvable(val name: String) : Type
    }
  }

  /** A [MutatedProperty] of the [owner] with the [properties]. */
  data class MutatedProperty(val owner: String, val properties: String)

  companion object {

    /** Parse the [cypher] as a [Query]. */
    fun parse(cypher: String): Query? {
      val nodes = mutableMapOf<String, Set<String>>()
      val mutatedProperties = mutableSetOf<MutatedProperty>()
      val options =
          Options.newOptions()
              .withCallback(PatternElementCreatedEventType.ON_MATCH) { nodes.collect(it) }
              .withCallback(PatternElementCreatedEventType.ON_CREATE) { nodes.collect(it) }
              .withCallback(PatternElementCreatedEventType.ON_MERGE) { nodes.collect(it) }
              .withCallback(
                  ExpressionCreatedEventType.ON_ADD_AND_SET_VARIABLE, Operation::class.java) {
                    mutatedProperties.collect(it)
                  }
              .build()
      return try {
        val statement = CypherParser.parse(cypher, options)
        Query(
            statement.nodes,
            statement.relationships,
            statement.properties,
            mutatedProperties
                .mapNotNull { mutatedProperty ->
                  nodes[mutatedProperty.owner]?.firstOrNull()?.let { label ->
                    mutatedProperty.copy(owner = label)
                  }
                }
                .toSet())
      } catch (_: Throwable) {
        null
      }
    }

    /** Collect the symbolic name to node label(s) mapping from the [patternElement]. */
    private fun MutableMap<String, Set<String>>.collect(
        patternElement: PatternElement
    ): PatternElement {
      fun collect(node: NodeBase<*>) {
        val symbolicName = node.symbolicName.getOrNull()?.value ?: return
        val labels =
            node.labels.takeUnless(List<*>::isEmpty)?.map(NodeLabel::getValue)?.toSet() ?: return
        this += symbolicName to labels
      }
      when (patternElement) {
        is NodeBase<*> -> collect(patternElement)
        is RelationshipBase<*, *, *> ->
            listOf(patternElement.left, patternElement.right)
                .filterIsInstance<NodeBase<*>>()
                .forEach(::collect)
      }
      return patternElement
    }

    /** Collect the [MutatedProperty] from the [expression]. */
    private fun MutableSet<MutatedProperty>.collect(expression: Expression): Operation {
      val operation = expression as Operation
      var left: SymbolicName? = null
      var operator: Operator? = null
      var right: Parameter<*>? = null
      operation.accept { visitable ->
        when {
          visitable is Operation -> {}
          left == null && visitable is SymbolicName -> left = visitable
          operator == null && visitable is Operator -> operator = visitable
          right == null && visitable is Parameter<*> -> right = visitable
        }
      }
      try {
        check(operator == Operator.MUTATE)
        this += MutatedProperty(checkNotNull(left?.cypher()), checkNotNull(right?.name))
      } catch (_: IllegalStateException) {}
      return operation
    }

    /** A [Regex] to capture the *Cypher* of a rendered [org.neo4j.cypherdsl.core] type. */
    private val RENDERED_DSL = Regex("\\w+\\{cypher=(.+)}")

    /** Extract the rendered *Cypher* from the [Visitable] to circumvent inaccessible data. */
    private fun Visitable.cypher(): String? {
      return RENDERED_DSL.find("$this")?.groups?.get(1)?.value
    }

    /** Get the labels of the nodes in the *Cypher* [Statement].. */
    private val Statement.nodes: Set<String>
      get() {
        return catalog.nodeLabels.map(StatementCatalog.Token::value).toSet()
      }

    /** Get the [Relationship]s in the *Cypher* [Statement]. */
    private val Statement.relationships: Set<Relationship>
      get() {
        return catalog.relationshipTypes
            .flatMap { type ->
              fun Collection<StatementCatalog.Token>?.orEmptyToken():
                  Collection<StatementCatalog.Token> =
                  takeUnless { it.isNullOrEmpty() } ?: listOf(StatementCatalog.Token.label(""))
              val sources = catalog.getSourceNodes(type).orEmptyToken()
              val targets = catalog.getTargetNodes(type).orEmptyToken()
              sources
                  .flatMap { source -> targets.map { target -> source to target } }
                  .map { (source, target) ->
                    Relationship(
                        type.value,
                        source.value.takeIf { type in catalog.getOutgoingRelations(source) },
                        target.value.takeIf { type in catalog.getIncomingRelations(target) })
                  }
            }
            .toSet()
      }

    /** Get the node/relationship properties in the *Cypher* [Statement]. */
    private val Statement.properties: Set<Property>
      get() {
        val options = mutableMapOf<String, Set<String?>>()
        val propertyTypes =
            catalog.allPropertyFilters
                .map { (property, filters) ->
                  "${property.owningToken.firstOrNull()?.value.orEmpty()}.${property.name}" to
                      filters.collectOptions(options).mapTypes().toSet()
                }
                .toMap()
        return catalog.properties
            .mapNotNull { property ->
              val owner = property.owningToken.firstOrNull()?.value
              val name = property.name
              val types = propertyTypes.getOrDefault("$owner.$name", emptySet())
              val values = options.getOrDefault(name, emptySet())
              if (types
                  .filterIsInstance<Property.Type.Value>()
                  .mapNotNull(Property.Type.Value::cypher)
                  .any { value -> value in values })
                  null
              else Property(owner, name, types)
            }
            .toSet()
      }

    /**
     * Collect the [options] used in [FunctionInvocation]s within the
     * [StatementCatalog.PropertyFilter]s.
     */
    private fun Collection<StatementCatalog.PropertyFilter>.collectOptions(
        options: MutableMap<String, Set<String?>>
    ): Collection<StatementCatalog.PropertyFilter> {
      return onEach { filter ->
        when (val expression = filter.right) {
          is FunctionInvocation -> {
            expression.accept { visitable ->
              when (visitable) {
                is KeyValueMapEntry -> {
                  options.compute(visitable.key) { _, values ->
                    (values ?: emptySet()) + visitable.value.cypher()
                  }
                }
              }
            }
          }
        }
      }
    }

    /** Convert each [StatementCatalog.PropertyFilter] to a [Property.Type]. */
    private fun Collection<StatementCatalog.PropertyFilter>.mapTypes(): List<Property.Type> {
      return mapNotNull { filter ->
        when (val expression = filter.right) {
          is NullLiteral -> Property.Type.Value(null, expression.cypher())
          is Literal<*> -> Property.Type.Value(expression.content, expression.cypher())
          is ListExpression ->
              buildList {
                    expression.accept { visitable ->
                      when (visitable) {
                        is NullLiteral -> this += null
                        is Literal<*> -> this += visitable.content
                      }
                    }
                  }
                  .let { Property.Type.Container(it) }
          is Parameter<*> -> Property.Type.Resolvable(expression.name)
          is FunctionInvocation ->
              Property.Type.Resolvable(expression.cypher() ?: "${expression.functionName}()")
          else -> null
        }
      }
    }
  }
}
