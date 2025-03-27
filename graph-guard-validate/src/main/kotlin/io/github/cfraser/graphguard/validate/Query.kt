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
package io.github.cfraser.graphguard.validate

import kotlin.collections.Set
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.jvm.optionals.getOrNull
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.FunctionInvocation
import org.neo4j.cypherdsl.core.KeyValueMapEntry
import org.neo4j.cypherdsl.core.LabelExpression
import org.neo4j.cypherdsl.core.ListExpression
import org.neo4j.cypherdsl.core.Literal
import org.neo4j.cypherdsl.core.NodeBase
import org.neo4j.cypherdsl.core.NodeLabel
import org.neo4j.cypherdsl.core.NullLiteral
import org.neo4j.cypherdsl.core.Operation
import org.neo4j.cypherdsl.core.Operator
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.cypherdsl.core.PatternElement
import org.neo4j.cypherdsl.core.Property as CypherProperty
import org.neo4j.cypherdsl.core.PropertyLookup
import org.neo4j.cypherdsl.core.RelationshipBase
import org.neo4j.cypherdsl.core.RelationshipChain
import org.neo4j.cypherdsl.core.Set as CypherSet
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementCatalog
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.cypherdsl.core.ast.Visitable
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.ExpressionCreatedEventType
import org.neo4j.cypherdsl.parser.Options
import org.neo4j.cypherdsl.parser.PatternElementCreatedEventType

/**
 * A *Cypher* [Query].
 *
 * @property nodes the node labels in the [Query]
 * @property relationships the relationships in the [Query]
 * @property properties the properties in the [Query]
 * @property mutatedProperties the mutated properties in the [Query]
 * @property removedProperties the removed properties in the [Query]
 * @property entities a map of symbolic name to entity labels
 */
internal data class Query(
    val nodes: Set<String>,
    val relationships: Set<Relationship>,
    val properties: Set<Property>,
    val mutatedProperties: Set<MutatedProperty>,
    val removedProperties: Set<RemovedProperty>,
    val entities: Map<String, Set<String>>
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

  /** An [Owned] entity/operation/expression has an [owner]. */
  sealed interface Owned<T> {

    /** The [owner] of the entity/operation/expression. */
    val owner: String

    /** Assign the [owner] to [T]. */
    fun assign(owner: String): T
  }

  /** A [MutatedProperty] of the [owner] with the [properties]. */
  data class MutatedProperty(override val owner: String, val properties: String) :
      Owned<MutatedProperty> {

    override fun assign(owner: String): MutatedProperty = copy(owner = owner)
  }

  /**
   * A [property] [REMOVE](https://neo4j.com/docs/cypher-manual/5/clauses/remove/)d from the
   * [owner].
   */
  data class RemovedProperty(override val owner: String, val property: String) :
      Owned<RemovedProperty> {

    override fun assign(owner: String): RemovedProperty = copy(owner = owner)
  }

  @Suppress("TooManyFunctions")
  companion object {

    /** Parse the [cypher] as a [Query]. */
    fun parse(cypher: String): Query? {
      val entities = mutableMapOf<String, Set<String>>()
      val mutatedProperties = mutableSetOf<MutatedProperty>()
      val removedProperties = mutableSetOf<RemovedProperty>()
      val options =
          Options.newOptions()
              .withCallback(PatternElementCreatedEventType.ON_MATCH) { entities.collect(it) }
              .withCallback(PatternElementCreatedEventType.ON_CREATE) { entities.collect(it) }
              .withCallback(PatternElementCreatedEventType.ON_MERGE) { entities.collect(it) }
              .withCallback(
                  ExpressionCreatedEventType.ON_ADD_AND_SET_VARIABLE, Operation::class.java) {
                    mutatedProperties.collect(it)
                  }
              .withCallback(ExpressionCreatedEventType.ON_REMOVE_PROPERTY, Expression::class.java) {
                removedProperties.collect(it)
              }
              .build()
      return try {
        val statement = CypherParser.parse(cypher, options)
        Query(
            statement.nodes,
            statement.relationships,
            statement.properties,
            mutatedProperties.mapToLabel(entities),
            removedProperties.mapToLabel(entities),
            entities)
      } catch (_: Throwable) {
        null
      }
    }

    /** Collect the symbolic name to entity label(s) mapping from the [patternElement]. */
    private fun MutableMap<String, Set<String>>.collect(
        patternElement: PatternElement
    ): PatternElement {
      when (patternElement) {
        is NodeBase<*> -> collectNode(patternElement)
        is RelationshipBase<*, *, *> -> {
          listOf(patternElement.left, patternElement.right)
              .filterIsInstance<NodeBase<*>>()
              .forEach { node -> collectNode(node) }
          collectRelationship(patternElement)
        }
        is RelationshipChain -> {
          patternElement.accept { visitable ->
            when (visitable) {
              is NodeBase<*> -> collect(visitable)
              is RelationshipBase<*, *, *> -> {
                listOf(visitable.left, visitable.right).filterIsInstance<NodeBase<*>>().forEach {
                    node ->
                  collectNode(node)
                }
                collectRelationship(visitable)
              }
            }
          }
        }
      }
      return patternElement
    }

    /** Collect the symbolic name to entity label(s) mapping from the [node]. */
    private fun MutableMap<String, Set<String>>.collectNode(node: NodeBase<*>) {
      val symbolicName = node.symbolicName.getOrNull()?.value ?: return
      val labels =
          node.labels
              .takeUnless(List<*>::isEmpty)
              ?.map(NodeLabel::getValue)
              ?.toSet()
              .orEmpty()
              .toMutableSet()
      fun add(vararg labelExpressions: LabelExpression) =
          labelExpressions.forEach { labelExpression ->
            if (labelExpression.type == LabelExpression.Type.LEAF)
                labels += labelExpression.value.orEmpty()
          }
      node.accept { visitable ->
        if (visitable is LabelExpression && visitable.type == LabelExpression.Type.DISJUNCTION)
            add(visitable.lhs, visitable.rhs)
      }
      compute(symbolicName) { _, previousLabels -> previousLabels.orEmpty() + labels }
    }

    /** Collect the symbolic name to entity label(s) mapping from the [relationship]. */
    private fun MutableMap<String, Set<String>>.collectRelationship(
        relationship: RelationshipBase<*, *, *>
    ) {
      var value: String? = null
      relationship.details.accept { visitable ->
        if (visitable is SymbolicName) value = visitable.value
      }
      val symbolicName = value ?: return
      val labels = relationship.details.types.takeUnless(List<*>::isEmpty)?.toSet().orEmpty()
      compute(symbolicName) { _, previousLabels -> previousLabels.orEmpty() + labels }
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

    /** Collect the [RemovedProperty] from the [expression]. */
    private fun MutableSet<RemovedProperty>.collect(expression: Expression): Expression {
      var symbolicName: SymbolicName? = null
      var propertyLookup: PropertyLookup? = null
      expression.accept { visitable ->
        when {
          symbolicName == null && visitable is SymbolicName -> symbolicName = visitable
          propertyLookup == null && visitable is PropertyLookup -> propertyLookup = visitable
        }
      }
      try {
        this +=
            RemovedProperty(
                checkNotNull(symbolicName?.cypher()),
                checkNotNull(propertyLookup?.cypher()?.removePrefix(".")))
      } catch (_: IllegalStateException) {}
      return expression
    }

    /** Map the [Owned] symbolic name to a label from the collected [entities]. */
    private fun <T, O : Owned<T>> MutableSet<O>.mapToLabel(
        entities: Map<String, Set<String>>
    ): Set<T> =
        mapNotNull { owned -> entities[owned.owner]?.firstOrNull()?.let(owned::assign) }.toSet()

    /** A [Regex] to capture the *Cypher* of a rendered [org.neo4j.cypherdsl.core] type. */
    private val RENDERED_DSL = Regex("\\w+\\{cypher=(.+)}")

    /** Extract the rendered *Cypher* from the [Visitable] to circumvent inaccessible data. */
    private fun Visitable.cypher(): String? = RENDERED_DSL.find("$this")?.groups?.get(1)?.value

    /** Get the labels of the nodes in the *Cypher* [Statement].. */
    private val Statement.nodes: Set<String>
      get() = catalog.nodeLabels.map(StatementCatalog.Token::value).toSet()

    /** Get the [Relationship]s in the *Cypher* [Statement]. */
    private val Statement.relationships: Set<Relationship>
      get() =
          catalog.relationshipTypes
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

    /** Get the node/relationship properties in the *Cypher* [Statement]. */
    private val Statement.properties: Set<Property>
      get() {
        val options = mutableMapOf<String, Set<String?>>()
        val propertyTypes = buildMap {
          catalog.allPropertyFilters.forEach { (property, filters) ->
            this +=
                "${property.owningToken.firstOrNull()?.value.orEmpty()}.${property.name}" to
                    filters.collectOptions(options).mapPropertyType().toSet()
          }
          val properties =
              catalog.properties
                  .mapNotNull { property ->
                    property.owningToken.firstOrNull()?.value?.let { owner ->
                      property.name to owner
                    }
                  }
                  .toMap()
          val entities = mutableMapOf<String, Collection<String>>()
          accept { visitable ->
            when (visitable) {
              is NodeBase<*> -> {
                val name = visitable.symbolicName.getOrNull()?.value ?: return@accept
                entities += name to visitable.labels.map(NodeLabel::getValue)
              }
              is CypherSet ->
                  for ((left, operator, right) in visitable.getOperations()) {
                    if (operator != Operator.SET) continue
                    val reference = left.containerReference.cypher() ?: continue
                    val label =
                        entities[reference]?.firstOrNull { label -> label == properties[left.name] }
                            ?: continue
                    this += "$label.${left.name}" to setOf(right)
                  }
            }
          }
        }
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

    /**
     * Get the [CypherProperty], [Operator], and [Property.Type] for each of the [Operation]s in the
     * [CypherSet].
     */
    private fun CypherSet.getOperations(): Set<Triple<CypherProperty, Operator, Property.Type>> {
      return buildSet {
        accept { visitable ->
          if (visitable !is Operation) return@accept
          var left: CypherProperty? = null
          var operator: Operator? = null
          var right: Property.Type? = null
          visitable.accept { component ->
            when {
              component is Operation -> {}
              left == null && component is CypherProperty -> left = component
              operator == null && component is Operator -> operator = component
              right == null && component is Expression -> right = component.toPropertyType()
            }
          }
          this += Triple(left ?: return@accept, operator ?: return@accept, right ?: return@accept)
        }
      }
    }

    /** Convert each [StatementCatalog.PropertyFilter] to a [Property.Type]. */
    private fun Collection<StatementCatalog.PropertyFilter>.mapPropertyType(): List<Property.Type> =
        mapNotNull { filter ->
          filter.right?.toPropertyType()
        }

    /** Convert the [Expression] to a [Property.Type]. */
    private fun Expression.toPropertyType(): Property.Type? {
      return when (val expression = this) {
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
