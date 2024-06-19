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

package io.github.cfraser.graphguard.plugin

import java.time.Duration as JDuration
import java.time.LocalDate as JLocalDate
import java.time.LocalDate
import java.time.LocalDateTime as JLocalDateTime
import java.time.LocalTime as JLocalTime
import java.time.OffsetTime
import java.time.ZonedDateTime as JZonedDateTime
import java.time.ZonedDateTime
import kotlin.Any as KAny
import kotlin.Any
import kotlin.Boolean as KBoolean
import kotlin.String as KString
import kotlin.collections.List as KList
import kotlin.properties.Delegates.notNull
import kotlin.reflect.KClass
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.RuleNode

/**
 * A [Schema] describes the *nodes* and *relationships* in a [Neo4j](https://neo4j.com/) database
 * via the (potentially) interconnected [graphs].
 *
 * [Schema] implements [Validator.Rule], thus has the capability to validate that a *Cypher* query
 * adheres to the *nodes* and *relationships* defined in the [graphs].
 *
 * @property graphs the [Schema.Graph]s defining the [Schema.Node]s and [Schema.Relationship]s
 */
@JvmRecord
@Suppress("MemberVisibilityCanBePrivate")
data class Schema internal constructor(val graphs: KList<Graph>) : Validator.Rule {

  /**
   * Initialize a [Schema] from the [schemaText].
   *
   * @throws IllegalArgumentException if the [schemaText] or [Schema] is invalid
   */
  constructor(
      schemaText: KString
  ) : this(
      CharStreams.fromString(schemaText)
          .let(::SchemaLexer)
          .let(::CommonTokenStream)
          .let(::SchemaParser)
          .let { parser ->
            Collector()
                .also { collector -> ParseTreeWalker.DEFAULT.walk(collector, parser.start()) }
                .graphs
          })

  init {
    // Initialize/validate graph entities
    val (_, _) = nodes.value to relationships.value
  }

  /** The [Node]s in the [graphs] indexed by [Node.name]. */
  private val nodes: Lazy<Map<KString, Node>>
    get() = lazy { graphs.flatMap(Graph::nodes).associateBy(Node::name) }

  /** The [Relationship]s in the [graphs] indexed by [Relationship.Id]. */
  private val relationships: Lazy<Map<Relationship.Id, Relationship>>
    get() = lazy {
      buildMap {
        graphs.flatMap(Graph::nodes).flatMap(Node::relationships).forEach { relationship ->
          val key =
              Relationship.Id(
                  relationship.name, relationship.source.validate(), relationship.target.validate())
          require(key !in this) {
            "Duplicate relationship ${key.name} from ${key.source} to ${key.target}"
          }
          this[key] = relationship
        }
      }
    }

  @Suppress("CyclomaticComplexMethod", "ReturnCount")
  override fun validate(
      cypher: KString,
      parameters: Map<KString, KAny?>
  ): Validator.Rule.Violation? {
    val query = Query.parse(cypher) ?: return null
    for (queryNode in query.nodes) {
      val entity = Violation.Entity.Node(queryNode)
      val schemaNode = nodes.value[queryNode] ?: return Violation.Unknown(entity).violation
      for (queryProperty in
          query.properties(queryNode) + query.mutatedProperties(queryNode, parameters)) {
        val schemaProperty =
            schemaNode.properties.matches(queryProperty)
                ?: return Violation.UnknownProperty(entity, queryProperty.name).violation
        return schemaProperty.validate(entity, queryProperty, parameters)?.violation ?: continue
      }
    }
    for (queryRelationship in query.relationships) {
      val (label, source, target) = queryRelationship
      if (source == null || target == null) continue
      val entity = Violation.Entity.Relationship(label, source, target)
      val schemaRelationship =
          relationships.value[Relationship.Id(label, source, target)]
              ?: return Violation.Unknown(entity).violation
      for (queryProperty in query.properties(label)) {
        val schemaProperty =
            schemaRelationship.properties.matches(queryProperty)
                ?: return Violation.UnknownProperty(entity, queryProperty.name).violation
        return schemaProperty.validate(entity, queryProperty, parameters)?.violation ?: continue
      }
    }
    return null
  }

  /** Validate the entity label and return the unqualified reference. */
  private fun KString.validate(): KString {
    return if ("." in this) {
      val (graph, label) =
          requireNotNull(split(".").takeIf { it.size == 2 }) { "Invalid node reference '$this'" }
      val node =
          requireNotNull(graphs.find { it.name == graph }?.nodes?.find { it.name == label }) {
            "Invalid graph reference '$graph.$label'"
          }
      node.name
    } else apply { require(this in nodes.value) { "Invalid node reference '$this'" } }
  }

  /** [DSL] to build a [Schema]. */
  object DSL {

    /**
     * Build a [Schema] with the [builder] function.
     *
     * @param builder the function that builds the [Schema]
     * @return the built [Schema]
     */
    fun schema(builder: Builder.() -> Unit): Schema {
      return Builder().apply(builder).build()
    }

    /**
     * Build then add the [graph] to the [Schema.graphs].
     *
     * @param name the [Graph.Builder.name]
     * @param builder the function that builds the [Schema.Graph] to add
     * @return `this` [Schema.Builder]
     */
    fun Builder.graph(name: KString, builder: Graph.Builder.() -> Unit): Builder {
      return graph(Graph.Builder().name(name).apply(builder).build())
    }

    /**
     * Build then add the [node] to the [Graph.Builder.nodes].
     *
     * @param name the [Node.Builder.name]
     * @param builder the function that builds the [Node] to add
     * @return `this` [Graph.Builder]
     */
    fun Graph.Builder.node(name: KString, builder: Node.Builder.() -> Unit): Graph.Builder {
      return node(Node.Builder().name(name).apply(builder).build())
    }
  }

  /** [Schema.Builder] builds a [Schema]. */
  class Builder {

    private val graphs = mutableListOf<Graph>()

    /**
     * Add the [graph] to the [Schema.graphs].
     *
     * @param graph the [Graph] to add
     * @return `this` [Schema.Builder]
     */
    fun graph(graph: Graph): Builder = apply { graphs += graph }

    /**
     * Build the [Schema].
     *
     * @return the built [Schema]
     */
    fun build(): Schema {
      return Schema(graphs)
    }
  }

  /**
   * A [Graph] is a [KList] of [nodes] that specify the expected entities in a *Neo4j* database.
   *
   * @property name the name of the graph
   * @property nodes the nodes and relationships in the graph
   */
  @JvmRecord
  data class Graph internal constructor(val name: KString, val nodes: KList<Node>) {

    override fun toString(): KString {
      return "graph $name {\n${nodes.joinToString("\n", transform = Node::toString)}\n}"
    }

    /** [Graph.Builder] builds a [Graph]. */
    class Builder {

      private var name: KString? = null
      private val nodes = mutableListOf<Node>()

      /**
       * Set the [Graph.name].
       *
       * @param name the name of the [Graph]
       * @return `this` [Graph.Builder]
       * @throws IllegalStateException if [Graph.name] has already been set
       */
      fun name(name: KString): Builder = apply {
        check(this.name.isNullOrBlank()) { "Graph has name '${this.name}'" }
        this.name = name
      }

      /**
       * Add the [node] to the [Graph.nodes].
       *
       * @param node the [Node] to add
       * @return `this` [Graph.Builder]
       */
      fun node(node: Node): Builder = apply { nodes += node }

      /**
       * Build the [Graph].
       *
       * @return the built [Graph]
       * @throws IllegalStateException if [Graph.name] has not been set
       */
      fun build(): Graph {
        return Graph(checkNotNull(name) { "Graph name is required" }, nodes)
      }
    }
  }

  /**
   * A [Node] in the graph.
   *
   * @property name the name of the node
   * @property properties the node properties
   * @property relationships the relationships to other nodes
   * @property metadata the node metadata
   */
  @JvmRecord
  data class Node
  internal constructor(
      val name: KString,
      val properties: KList<Property>,
      val relationships: KList<Relationship>,
      val metadata: KList<Metadata>
  ) {

    override fun toString(): KString {
      val relationships =
          ":\n${relationships.joinToString(",\n", transform = Relationship::toString)}"
              .takeUnless { relationships.isEmpty() }
              .orEmpty()
      return "  node $name${properties.parenthesize()}$relationships;"
    }

    /** [Node.Builder] builds a [Node]. */
    class Builder {

      private var name: KString? = null
      private val properties = mutableListOf<Property>()
      private val relationships = mutableListOf<Relationship>()
      private val metadata = mutableListOf<Metadata>()

      /**
       * Set the [Node.name].
       *
       * @param name the name of the [Node]
       * @return `this` [Node.Builder]
       * @throws IllegalStateException if [Node.name] has already been set
       */
      fun name(name: KString): Builder = apply {
        check(this.name.isNullOrBlank()) { "Node has name '${this.name}'" }
        this.name = name
      }

      /**
       * Add the [property] to the [Node.properties].
       *
       * @param property the [Property] to add
       * @return `this` [Node.Builder]
       */
      fun property(property: Property): Builder = apply { properties += property }

      /**
       * Add the [relationship] to the [Node.relationships].
       *
       * @param relationship the [Relationship] to add
       * @return `this` [Node.Builder]
       */
      fun relationship(relationship: Relationship): Builder = apply {
        relationships += relationship
      }

      /**
       * Add the [metadata] to the [Node.metadata].
       *
       * @param metadata the [Metadata] to add
       * @return `this` [Node.Builder]
       */
      fun metadata(metadata: Metadata): Builder = apply { this.metadata += metadata }

      /**
       * Build the [Node].
       *
       * @return the built [Node]
       * @throws IllegalStateException if [Node.name] has not been set
       */
      fun build(): Node {
        return Node(
            checkNotNull(name) { "Node name is required" }, properties, relationships, metadata)
      }
    }
  }

  /**
   * A [Relationship] in the graph.
   *
   * @property name the name of the relationship
   * @property source the name of the node the relationship comes from
   * @property target the name of the node the relationship goes to
   * @property isDirected whether the relationship is directed
   * @property properties the relationship properties
   * @property metadata the relationship metadata
   */
  @JvmRecord
  data class Relationship
  internal constructor(
      val name: KString,
      val source: KString,
      val target: KString,
      val isDirected: KBoolean,
      val properties: KList<Property>,
      val metadata: KList<Metadata>
  ) {

    /**
     * [Relationship.Id] uniquely identifies a [Relationship].
     *
     * @property name the name of the relationship
     * @property source the name of the node the relationship comes from
     * @property target the name of the node the relationship goes to
     */
    @JvmRecord internal data class Id(val name: KString, val source: KString, val target: KString)

    override fun toString(): KString {
      val direction = if (isDirected) "->" else "--"
      return "    $name${properties.parenthesize()} $direction $target"
    }

    /** [Relationship.Builder] builds a [Relationship]. */
    class Builder {

      private var name: KString? = null
      private var source: KString? = null
      private var target: KString? = null
      private var isDirected: KBoolean? = null
      private val properties = mutableListOf<Property>()
      private val metadata = mutableListOf<Metadata>()

      /**
       * Set the [Relationship.name].
       *
       * @param name the name of the [Relationship]
       * @return `this` [Relationship.Builder]
       * @throws IllegalStateException if [Relationship.name] has already been set
       */
      fun name(name: KString): Builder = apply {
        check(this.name.isNullOrBlank()) { "Relationship has name '${this.name}'" }
        this.name = name
      }

      /**
       * Set the [Relationship.source].
       *
       * @param source the source of the [Relationship]
       * @return `this` [Relationship.Builder]
       * @throws IllegalStateException if [Relationship.source] has already been set
       */
      fun source(source: KString): Builder = apply {
        check(this.source.isNullOrBlank()) { "Relationship has source '${this.source}'" }
        this.source = source
      }

      /**
       * Set the [Relationship.target].
       *
       * @param target the target of the [Relationship]
       * @return `this` [Relationship.Builder]
       * @throws IllegalStateException if [Relationship.target] has already been set
       */
      fun target(target: KString): Builder = apply {
        check(this.target.isNullOrBlank()) { "Relationship has target '${this.target}'" }
        this.target = target
      }

      /**
       * Set whether the [Relationship.isDirected].
       *
       * @param isDirected whether the [Relationship] is directed
       * @return `this` [Relationship.Builder]
       * @throws IllegalStateException if [Relationship.isDirected] has already been set
       */
      fun isDirected(isDirected: KBoolean): Builder = apply {
        check(this.isDirected == null) {
          "Relationship is${this.isDirected?.toString()?.let { _ -> "n't" }.orEmpty()} directed"
        }
        this.isDirected = isDirected
      }

      /**
       * Add the [property] to the [Relationship.properties].
       *
       * @param property the [Property] to add
       * @return `this` [Relationship.Builder]
       */
      fun property(property: Property): Builder = apply { properties += property }

      /**
       * Add the [metadata] to the [Relationship.metadata].
       *
       * @param metadata the [Metadata] to add
       * @return `this` [Relationship.Builder]
       */
      fun metadata(metadata: Metadata): Builder = apply { this.metadata += metadata }

      /**
       * Build the [Relationship].
       * > The built [Relationship] [isDirected], unless specified otherwise.
       *
       * @return the built [Relationship]
       * @throws IllegalStateException if [Relationship.name], [Relationship.source], or
       *   [Relationship.target] has not been set
       */
      fun build(): Relationship {
        return Relationship(
            checkNotNull(name) { "Relationship name is required" },
            checkNotNull(source) { "Relationship source is required" },
            checkNotNull(target) { "Relationship target is required" },
            isDirected ?: true,
            properties,
            metadata)
      }
    }
  }

  /**
   * A [Property] on a [Node] or [Relationship].
   *
   * @property name the name of the property
   * @property type the property type
   * @property metadata the property metadata
   */
  @JvmRecord
  data class Property
  internal constructor(val name: KString, val type: Type, val metadata: KList<Metadata>) {

    override fun toString(): KString {
      return "$name: $type"
    }

    /**
     * The [Type] of the [Property].
     *
     * Refer to the [Neo4j documentation](https://neo4j.com/docs/cypher-manual/5/values-and-types/)
     * for details about the supported types.
     *
     * @property clazz the [KClass] of the value for the [Type]
     */
    sealed class Type(internal val clazz: KClass<*>) {

      data object Any : Type(KAny::class)

      data object Boolean : Type(KBoolean::class)

      data object Date : Type(LocalDate::class)

      data object DateTime : Type(ZonedDateTime::class)

      data object Duration : Type(JDuration::class)

      data object Float : Type(Double::class)

      data object Integer : Type(Long::class)

      data object LocalDateTime : Type(JLocalDateTime::class)

      data object LocalTime : Type(JLocalTime::class)

      data object String : Type(KString::class)

      data object Time : Type(OffsetTime::class)

      data class List(val type: Type) : Type(Unit::class) {

        override fun toString(): KString {
          return "List<$type>"
        }
      }

      data class LiteralString(val value: KString) : Type(KString::class) {

        override fun toString(): KString {
          return "\"$value\""
        }
      }

      /** A [Nullable] [Type]. */
      sealed class Nullable(clazz: KClass<*>) : Type(clazz) {

        override fun toString(): KString {
          return "$this?"
        }

        data object Any : Nullable(KAny::class)

        data object Boolean : Nullable(KBoolean::class)

        data object Date : Nullable(LocalDate::class)

        data object DateTime : Nullable(ZonedDateTime::class)

        data object Duration : Nullable(JDuration::class)

        data object Float : Nullable(Double::class)

        data object Integer : Nullable(Long::class)

        data object LocalDateTime : Nullable(JLocalDateTime::class)

        data object LocalTime : Nullable(JLocalTime::class)

        data object String : Nullable(KString::class)

        data object Time : Nullable(OffsetTime::class)

        data class List(val type: Type) : Nullable(Unit::class) {

          override fun toString(): KString {
            return "List<$type>?"
          }
        }

        data class LiteralString(val value: KString) : Nullable(KString::class) {

          override fun toString(): KString {
            return "\"$value\""
          }
        }
      }

      /** A [Type.Union] of [types]. */
      data class Union(val types: KList<Type>) : Type(Unit::class) {

        override fun toString(): KString {
          return types.joinToString(" | ")
        }
      }
    }

    /** [Property.Builder] builds a [Property]. */
    class Builder {

      private var name: KString? = null
      private var type: Type? = null
      private val metadata = mutableListOf<Metadata>()

      /**
       * Set the [Property.name].
       *
       * @param name the name of the [Property]
       * @return `this` [Property.Builder]
       * @throws IllegalStateException if [Property.name] has already been set
       */
      fun name(name: KString): Builder = apply {
        check(this.name.isNullOrBlank()) { "Property has name '${this.name}'" }
        this.name = name
      }

      /**
       * Set the [Property.type].
       *
       * @param type the type of the [Property]
       * @return `this` [Property.Builder]
       * @throws IllegalStateException if [Property.type] has already been set
       */
      fun type(type: Type): Builder = apply {
        check(this.type == null) { "Property has type '${this.type}'" }
        this.type = type
      }

      /**
       * Add the [metadata] to the [Property.metadata].
       *
       * @param metadata the [Metadata] to add
       * @return `this` [Property.Builder]
       */
      fun metadata(metadata: Metadata): Builder = apply { this.metadata += metadata }

      /**
       * Build the [Property].
       *
       * @return the built [Property]
       * @throws IllegalStateException if [Property.name] has not been set
       */
      fun build(): Property {
        return Property(
            checkNotNull(name) { "Property name is required" },
            checkNotNull(type) { "Property type is required" },
            metadata)
      }
    }
  }

  /**
   * [Graph] entity [Metadata].
   *
   * @property name the name of the metadata
   * @property value the optional value of the metadata
   */
  @JvmRecord data class Metadata internal constructor(val name: KString, val value: KString?)

  /** An [Violation] describes a *Cypher* query with a [Schema] [violation]. */
  internal sealed class Violation(val violation: Validator.Rule.Violation) {

    sealed class Entity(val name: KString) {

      class Node(label: KString) : Entity("node $label")

      class Relationship(label: KString, source: KString?, target: KString?) :
          Entity("relationship $label from $source to $target")
    }

    class Unknown(entity: Entity) : Violation(Validator.Rule.Violation("Unknown ${entity.name}"))

    class UnknownProperty(entity: Entity, property: KString) :
        Violation(Validator.Rule.Violation("Unknown property '$property' for ${entity.name}"))

    class InvalidProperty(entity: Entity, property: Property, values: KList<KAny?>) :
        Violation(
            Validator.Rule.Violation(
                @Suppress("MaxLineLength")
                "Invalid query value(s) '${values.sortedBy { "$it" }.joinToString()}' for property '$property' on ${entity.name}"))
  }

  private companion object {

    /**
     * [Map] of the [Query.Property.Type.Resolvable.name] of an invoked function to a synthetic
     * value.
     * > Includes [scalar](https://neo4j.com/docs/cypher-manual/current/functions/scalar/), [mathematical](https://neo4j.com/docs/cypher-manual/current/functions/mathematical-numeric/),
     * > [string](https://neo4j.com/docs/cypher-manual/current/functions/string/), and
     * > [temporal](https://neo4j.com/docs/cypher-manual/current/functions/temporal/) functions.
     */
    val RESOLVABLE_FUNCTIONS: Map<KString, Any> = buildMap {
      val boolean = false
      val integer = 0L
      val float = 0.0
      val string = ""
      val date = LocalDate.now()
      val datetime = ZonedDateTime.now()
      val localdatetime = JLocalDateTime.now()
      val localtime = JLocalTime.now()
      val time = OffsetTime.now()
      val duration = JDuration.ZERO

      this += "char_length()" to integer
      this += "character_length()" to integer
      this += "elementId()" to string
      this += "id()" to integer
      this += "length()" to integer
      this += "randomUUID()" to string
      this += "size()" to integer
      this += "timestamp()" to integer
      this += "toBoolean()" to boolean
      this += "toFloat()" to float
      this += "toInteger()" to integer
      this += "type()" to string
      this += "valueType()" to string
      this += "abs()" to integer
      this += "ceil()" to float
      this += "floor()" to float
      this += "isNaN()" to boolean
      this += "rand()" to float
      this += "round()" to float
      this += "sign()" to integer
      this += "e()" to float
      this += "exp()" to float
      this += "log()" to float
      this += "log10()" to float
      this += "sqrt()" to float
      this += "acos()" to float
      this += "asin()" to float
      this += "atan()" to float
      this += "atan2()" to float
      this += "cos()" to float
      this += "cot()" to float
      this += "degrees()" to float
      this += "haversin()" to float
      this += "pi()" to float
      this += "radians()" to float
      this += "sin()" to float
      this += "tan()" to float
      this += "left()" to string
      this += "ltrim()" to string
      this += "replace()" to string
      this += "reverse()" to string
      this += "right()" to string
      this += "rtrim()" to string
      this += "split()" to listOf(string)
      this += "substring()" to string
      this += "toLower()" to string
      this += "toString()" to string
      this += "toUpper()" to string
      this += "trim()" to string
      arrayOf("", ".transaction", ".statement", ".realtime").forEach { clock ->
        this +=
            listOf(
                "date$clock()" to date,
                "datetime$clock()" to datetime,
                "localdatetime$clock()" to localdatetime,
                "localtime$clock()" to localtime,
                "time$clock()" to time)
      }
      this += "duration()" to duration
      this += "duration.between()" to duration
      this += "duration.inMonths()" to duration
      this += "duration.inDays()" to duration
      this += "duration.inSeconds()" to duration
    }

    /** Parenthesize the [KList] of properties. */
    fun KList<Property>.parenthesize(): KString {
      return if (isEmpty()) "" else "(${joinToString(transform = Property::toString)})"
    }

    /** Filter the properties in the [Query] with the [label]. */
    fun Query.properties(label: KString): Collection<Query.Property> {
      return properties.filter { label == it.owner }
    }

    /**
     * Filter the mutated properties in the [Query] with the [label], and use the resolved
     * [parameters] to transform each into a [Query.Property].
     */
    fun Query.mutatedProperties(
        label: KString,
        parameters: Map<KString, KAny?>
    ): Collection<Query.Property> {
      return mutatedProperties
          .filter { label == it.owner }
          .mapNotNull { (_, parameter) -> parameter.resolve(parameters).takeUnless { it is Unit } }
          .flatMap { properties ->
            when (properties) {
              is Map<*, *> ->
                  properties.map { (name, value) ->
                    Query.Property(
                        label,
                        checkNotNull(name as? KString) { "Unexpected parameter name" },
                        setOf(
                            when (value) {
                              is KBoolean,
                              is JDuration,
                              is JLocalDate,
                              is JLocalDateTime,
                              is JLocalTime,
                              is Number,
                              is OffsetTime,
                              is KString,
                              is JZonedDateTime,
                              null -> Query.Property.Type.Value(value)
                              is KList<*> -> Query.Property.Type.Container(value)
                              else -> error("Unexpected parameter value")
                            }))
                  }
              else -> emptyList()
            }
          }
    }

    /** Find the [Property] that matches the [Query.Property]. */
    fun KList<Property>.matches(property: Query.Property): Property? {
      return find { property.name == it.name }
    }

    /** Validate the [property] of the [entity] per the schema [Property] and [parameters]. */
    fun Property.validate(
        entity: Violation.Entity,
        property: Query.Property,
        parameters: Map<KString, KAny?>
    ): Violation? {
      val resolvable = parameters + RESOLVABLE_FUNCTIONS
      // retain the unresolved value so function invocations can be returned as received
      val values =
          property.values
              .map {
                when (it) {
                  is Query.Property.Type.Value -> it to it.value
                  is Query.Property.Type.Container -> it to it.values
                  is Query.Property.Type.Resolvable -> it to it.name.resolve(resolvable)
                }
              }
              .filter { (_, value) -> value !is Unit }
              .distinct()
      return if (values.map { (_, value) -> value }.isValid()) null
      else
          Violation.InvalidProperty(
              entity,
              this,
              // return the unresolved function invocation so the synthetic value remains internal
              values.map { (unresolved, resolved) ->
                if (unresolved is Query.Property.Type.Resolvable && "(" in unresolved.name)
                    unresolved.name
                else resolved
              })
    }

    /**
     * Resolve the value of the [Query.Property.Type.Resolvable.name] (*expected*) in the
     * [resolvable] map.
     * > [Unit] is returned if `this` property isn't [resolvable].
     */
    fun KString.resolve(resolvable: Map<KString, KAny?>): KAny? {
      return split(".").foldRight<KString, KAny?>(resolvable) { name, parameter ->
        if (parameter is Map<*, *> && name in parameter) parameter[name] else Unit
      }
    }

    /** Determine if `this` [KList] of [Property] value(s) is valid. */
    context(Property)
    fun KList<KAny?>.isValid(): KBoolean {
      fun KList<KAny?>.filterNullIf(exclude: KBoolean) = if (exclude) filterNotNull() else this
      val isList = type is Property.Type.List || type is Property.Type.Nullable.List
      val allowsNullable =
          isList &&
              when (type) {
                is Property.Type.List -> type.type is Property.Type.Nullable
                is Property.Type.Nullable.List -> type.type is Property.Type.Nullable
                else -> false
              }
      if (isList && filterNullIf(allowsNullable).any { it !is MutableList<*> }) return false
      if (!isList && filterNotNull().any { it is MutableList<*> }) return false
      val isNullable = type is Property.Type.Nullable
      return flatMap { if (it is MutableList<*>) it.filterNullIf(allowsNullable) else listOf(it) }
          .filterNullIf(isNullable)
          .all { value ->
            when (type) {
              is Property.Type.List -> type.type.clazz.isInstance(value)
              is Property.Type.Nullable.List -> type.type.clazz.isInstance(value)
              is Property.Type.LiteralString -> type.value == value
              is Property.Type.Nullable.LiteralString -> type.value == value
              is Property.Type.Union ->
                  type.types.any { t ->
                    if (t is Property.Type.LiteralString) t.value == value
                    else t.clazz.isInstance(value)
                  }
              else -> type.clazz.isInstance(value)
            }
          }
    }
  }

  /**
   * [Collector] is a [io.github.cfraser.graphguard.plugin.SchemaListener] that collects [graphs]
   * while walking the parse tree.
   */
  private class Collector(val graphs: MutableList<Graph> = mutableListOf()) : SchemaBaseListener() {

    private var graph by notNull<Graph>()
    private var node by notNull<Node>()

    override fun enterGraph(ctx: SchemaParser.GraphContext) {
      graph = Graph(ctx.name().get(), emptyList())
    }

    override fun enterNode(ctx: SchemaParser.NodeContext) {
      val name = ctx.name().get()
      val properties = ctx.properties().get()
      val metadata = ctx.metadata().get()
      node = Node(name, properties, emptyList(), metadata)
    }

    override fun enterRelationship(ctx: SchemaParser.RelationshipContext) {
      val name = ctx.name().get()
      val source = node.name
      val target = ctx.target().get()
      val directed = ctx.DIRECTED() != null
      val properties = ctx.properties().get()
      val metadata = ctx.metadata().get()
      node =
          node.copy(
              relationships =
                  node.relationships +
                      Relationship(name, source, target, directed, properties, metadata))
    }

    override fun exitNode(ctx: SchemaParser.NodeContext?) {
      graph = graph.copy(nodes = graph.nodes + node)
    }

    override fun exitGraph(ctx: SchemaParser.GraphContext) {
      graphs += graph
    }

    private companion object {

      /** Get the name from the [RuleNode]. */
      fun RuleNode?.get(): KString {
        return checkNotNull(this?.text?.takeUnless { it.isBlank() })
      }

      /** Get the [Metadata] from the [SchemaParser.MetadataContext]. */
      tailrec fun SchemaParser.MetadataContext?.get(
          collected: MutableList<Metadata> = mutableListOf()
      ): KList<Metadata> {
        return if (this == null) collected
        else {
          val name = name().get()
          val value = metadataValue()?.name()?.get()
          collected += Metadata(name, value)
          metadata().get(collected)
        }
      }

      /** Get the properties from the [SchemaParser.PropertiesContext]. */
      fun SchemaParser.PropertiesContext?.get(): KList<Property> {
        return this?.property()?.map { ctx ->
          val name = ctx.name().get()
          val innerType =
              when (val type = ctx.type() ?: ctx.union()) {
                is SchemaParser.TypeContext -> type.get()
                is SchemaParser.UnionContext -> type.get()
                else -> error("Unknown type")
              }
          val metadata = ctx.metadata().get()
          val isList = ctx.type()?.list() != null
          val isNullable = ctx.type()?.QM() != null
          val type =
              if (isList) {
                if (isNullable) Property.Type.Nullable.List(innerType)
                else Property.Type.List(innerType)
              } else innerType
          Property(name, type, metadata)
        } ?: emptyList()
      }

      /** Get the [Property.Type] from the [SchemaParser.TypeContext]. */
      @Suppress("CyclomaticComplexMethod")
      fun SchemaParser.TypeContext.get(): Property.Type {
        val value =
            when (val ctx = typeValue() ?: list() ?: stringLiteral()) {
              is SchemaParser.TypeValueContext,
              is SchemaParser.StringLiteralContext -> ctx.get()
              is SchemaParser.ListContext -> ctx.typeValue().get()
              else -> error("Unknown type value")
            }
        // If a list, check the nullability of the inner type
        val isNullable = if (list() != null) list()?.QM() != null else QM() != null
        return when (value) {
          "Any" -> if (isNullable) Property.Type.Nullable.Any else Property.Type.Any
          "Boolean" -> if (isNullable) Property.Type.Nullable.Boolean else Property.Type.Boolean
          "Date" -> if (isNullable) Property.Type.Nullable.Date else Property.Type.Date
          "DateTime" -> if (isNullable) Property.Type.Nullable.DateTime else Property.Type.DateTime
          "Duration" -> if (isNullable) Property.Type.Nullable.Duration else Property.Type.Duration
          "Float" -> if (isNullable) Property.Type.Nullable.Float else Property.Type.Float
          "Integer" -> if (isNullable) Property.Type.Nullable.Integer else Property.Type.Integer
          "LocalDateTime" ->
              if (isNullable) Property.Type.Nullable.LocalDateTime else Property.Type.LocalDateTime
          "LocalTime" ->
              if (isNullable) Property.Type.Nullable.LocalTime else Property.Type.LocalTime
          "String" -> if (isNullable) Property.Type.Nullable.String else Property.Type.String
          "Time" -> if (isNullable) Property.Type.Nullable.Time else Property.Type.Time
          else -> {
            val literalValue = value.drop(1).dropLast(1)
            if (isNullable) Property.Type.Nullable.LiteralString(literalValue)
            else Property.Type.LiteralString(literalValue)
          }
        }
      }

      /** Get the [Property.Type.Union] type from the [SchemaParser.UnionContext]. */
      fun SchemaParser.UnionContext.get(): Property.Type.Union {
        val types = type().map { type -> type.get() }
        return Property.Type.Union(types)
      }
    }
  }
}
