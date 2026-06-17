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

import io.github.cfraser.graphguard.utils.Internal
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
import kotlin.String
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
 * [Schema] implements [Rule], thus has the capability to [Rule.validate] that a *Cypher* query
 * adheres to the *nodes* and *relationships* defined in the [graphs].
 *
 * @property graphs the [Schema.Graph]s defining the [Schema.Node]s and [Schema.Relationship]s
 * @property nodes the [Node]s in the [graphs] indexed by [Node.name]
 * @property relationships the [Relationship]s in the [graphs] indexed by [Relationship.Id]
 */
@JvmRecord
@ConsistentCopyVisibility
@OptIn(Internal::class)
data class Schema
internal constructor(
  val graphs: KList<Graph>,
  @Internal val nodes: Map<KString, Node>,
  @Internal val relationships: Map<Relationship.Id, Relationship>,
) : Rule {

  @Suppress("CyclomaticComplexMethod", "ReturnCount", "DuplicatedCode")
  override fun validate(cypher: KString, parameters: Map<KString, KAny?>): Rule.Violation? {
    val query = Query.parse(cypher) ?: return null
    for (queryNode in query.nodes) {
      val entity = Violation.Entity.Node(queryNode)
      val schemaNode = nodes[queryNode] ?: return Violation.Unknown(entity).violation
      for (queryProperty in
        query.properties(queryNode) + query.mutatedProperties(queryNode, parameters)) {
        val schemaProperty =
          schemaNode.properties.matches(queryProperty)
            ?: if (queryProperty.isRemoved(query.removedProperties)) continue
            else return Violation.UnknownProperty(entity, queryProperty.name).violation
        return schemaProperty.validate(entity, queryProperty, parameters)?.violation ?: continue
      }
    }
    for (queryRelationship in query.relationships) {
      val (label, sources, targets) = queryRelationship
      if (sources == null || targets == null) continue
      val entity = Violation.Entity.Relationship(label, sources, targets)
      val schemaRelationship =
        sources
          .flatMap { source -> targets.map { target -> source to target } }
          .firstNotNullOfOrNull { (source, target) ->
            relationships[Relationship.Id(label, source, target)]
          } ?: return Violation.Unknown(entity).violation
      for (queryProperty in query.properties(label) + query.mutatedProperties(label, parameters)) {
        val schemaProperty =
          schemaRelationship.properties.matches(queryProperty)
            ?: if (queryProperty.isRemoved(query.removedProperties)) continue
            else return Violation.UnknownProperty(entity, queryProperty.name).violation
        return schemaProperty.validate(entity, queryProperty, parameters)?.violation ?: continue
      }
    }
    return null
  }

  override fun toString(): String =
    graphs.joinToString("${System.lineSeparator()}${System.lineSeparator()}")

  /**
   * A [Graph] is a [KList] of [nodes] that specify the expected entities in a *Neo4j* database.
   *
   * @property name the name of the graph
   * @property nodes the nodes and relationships in the graph
   */
  @JvmRecord
  @ConsistentCopyVisibility
  data class Graph internal constructor(val name: KString, val nodes: KList<Node>) {

    override fun toString(): KString {
      return buildString {
        append("graph ")
        append(name)
        append(" {")
        append(System.lineSeparator())
        append(
          nodes.joinToString(
            "${System.lineSeparator()}${System.lineSeparator()}",
            transform = Node::toString,
          )
        )
        append(System.lineSeparator())
        append("}")
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
  @ConsistentCopyVisibility
  data class Node
  internal constructor(
    val name: KString,
    val properties: KList<Property>,
    val relationships: KList<Relationship>,
    val metadata: KList<Metadata>,
  ) {

    override fun toString(): KString {
      return buildString {
        append("  node ")
        this += metadata
        append(name)
        append(properties.parenthesize("    "))
        if (relationships.isNotEmpty()) {
          append(":")
          append(System.lineSeparator())
          append(
            relationships.joinToString(
              ",${System.lineSeparator()}",
              transform = Relationship::toString,
            )
          )
        }
        append(";")
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
   * @property cardinality the cardinality constraint on this relationship
   * @property properties the relationship properties
   * @property metadata the relationship metadata
   */
  @JvmRecord
  @ConsistentCopyVisibility
  data class Relationship
  internal constructor(
    val name: KString,
    val source: KString,
    val target: KString,
    val isDirected: KBoolean,
    val cardinality: Cardinality?,
    val properties: KList<Property>,
    val metadata: KList<Metadata>,
  ) {

    /**
     * [Relationship.Id] uniquely identifies a [Relationship].
     *
     * @property name the name of the relationship
     * @property source the name of the node the relationship comes from
     * @property target the name of the node the relationship goes to
     */
    @JvmRecord @Internal data class Id(val name: KString, val source: KString, val target: KString)

    /**
     * The cardinality of a [Relationship].
     *
     * @property source the source node's outgoing [Relationship.Cardinality.Range]
     * @property target the target node's incoming [Relationship.Cardinality.Range]
     */
    @JvmRecord
    @ConsistentCopyVisibility
    data class Cardinality internal constructor(val source: Range?, val target: Range?) {

      /**
       * The [Relationship.Cardinality] range.
       *
       * @property min the minimum number of relationships (`0` = optional, `1` = required)
       * @property max the maximum number of relationships (`null` = unbounded)
       */
      @JvmRecord
      @ConsistentCopyVisibility
      data class Range internal constructor(val min: Int, val max: Int?) {

        override fun toString(): KString {
          return when {
            max == null -> if (min == 0) "M?" else "M"
            min == 0 -> "1?"
            else -> "1"
          }
        }
      }
    }

    override fun toString(): KString {
      return buildString {
        append("      ")
        this += metadata
        append(name)
        cardinality?.let { c ->
          append(" [")
          c.source?.let { r -> append("$r") }
          append("..")
          c.target?.let { r -> append("$r") }
          append("]")
        }
        append(properties.parenthesize("        "))
        append(" ")
        append(if (isDirected) "->" else "--")
        append(" ")
        append(target)
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
  @ConsistentCopyVisibility
  data class Property
  internal constructor(val name: KString, val type: Type, val metadata: KList<Metadata>) {

    override fun toString(): KString {
      return buildString {
        this += metadata
        append(name)
        append(": ")
        append(type)
        if (type is Type.Nullable) append("?")
      }
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

        override fun toString(): KString = "List<$type>"
      }

      data class LiteralString(val value: KString) : Type(KString::class) {

        override fun toString(): KString = "\"$value\""
      }

      /** A [Nullable] [Type]. */
      sealed class Nullable(clazz: KClass<*>) : Type(clazz) {

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

          override fun toString(): KString = "List<$type>"
        }

        data class LiteralString(val value: KString) : Nullable(KString::class) {

          override fun toString(): KString = "\"$value\""
        }
      }

      /** A [Type.Union] of [types]. */
      data class Union(val types: KList<Type>) : Type(Unit::class) {

        override fun toString(): KString = types.joinToString(" | ")
      }
    }
  }

  /**
   * [Graph] entity [Metadata].
   *
   * @property name the name of the metadata
   * @property value the optional value of the metadata
   */
  @JvmRecord
  @ConsistentCopyVisibility
  data class Metadata internal constructor(val name: KString, val value: KString?)

  /** A [Schema] [Violation] caused by a *Cypher* query, or entities in the *Neo4j* graph. */
  @Internal
  sealed class Violation(val violation: Rule.Violation) {

    sealed class Entity(val name: KString) {

      class Node(label: KString) : Entity("node $label")

      class Relationship(
        label: KString,
        sources: Collection<KString>?,
        targets: Collection<KString>?,
      ) : Entity("relationship $label from ${sources.asString()} to ${targets.asString()}")
    }

    class Unknown(entity: Entity) : Violation(Rule.Violation("Unknown ${entity.name}"))

    class UnknownProperty(entity: Entity, property: KString) :
      Violation(Rule.Violation("Unknown property '$property' for ${entity.name}"))

    class MissingProperty(entity: Entity, property: KString) :
      Violation(Rule.Violation("Missing required property '$property' for ${entity.name}"))

    class InvalidProperty(entity: Entity, property: Property, values: KList<KAny?>) :
      Violation(
        Rule.Violation(
          @Suppress("MaxLineLength")
          "Invalid query value(s) '${values.sortedBy { "$it" }.joinToString()}' for property '$property' on ${entity.name}"
        )
      )

    class InvalidCardinality(
      relationship: KString,
      side: Side,
      count: Int,
      type: Limit,
      limit: Int,
    ) :
      Violation(
        Rule.Violation(
          "Cardinality violation: $relationship $side has $count instances ($type $limit)"
        )
      ) {

      enum class Side {

        SOURCE,
        TARGET,
      }

      enum class Limit {

        MIN,
        MAX,
      }
    }

    private companion object {

      /**
       * If the [Collection] has a single element, then return it. Otherwise, return
       * [Collection.toString].
       */
      fun Collection<KString>?.asString(): String = if (this?.size == 1) first() else "$this"
    }
  }

  companion object {

    /**
     * Initialize a [Schema] from the [schemaText].
     *
     * @param schemaText the [schema](https://github.com/c-fraser/graph-guard#schema) text
     * @throws IllegalArgumentException if the [schemaText] or [Schema] is invalid
     */
    @JvmStatic
    fun init(schemaText: String): Schema {
      val graphs =
        CharStreams.fromString(schemaText)
          .let(::SchemaLexer)
          .let(::CommonTokenStream)
          .let(::SchemaParser)
          .let { parser ->
            Collector()
              .also { collector -> ParseTreeWalker.DEFAULT.walk(collector, parser.start()) }
              .graphs
          }
      val nodes = graphs.flatMap(Graph::nodes).associateBy(Node::name)
      /** Validate the entity label and return the unqualified reference. */
      fun KString.validate(): KString {
        return if ("." in this) {
          val (graph, label) =
            requireNotNull(split(".").takeIf { it.size == 2 }) { "Invalid node reference '$this'" }
          val node =
            requireNotNull(graphs.find { it.name == graph }?.nodes?.find { it.name == label }) {
              "Invalid graph reference '$graph.$label'"
            }
          node.name
        } else apply { require(this in nodes) { "Invalid node reference '$this'" } }
      }
      val relationships = buildMap {
        graphs.flatMap(Graph::nodes).flatMap(Node::relationships).forEach { relationship ->
          val key =
            Relationship.Id(
              relationship.name,
              relationship.source.validate(),
              relationship.target.validate(),
            )
          require(key !in this) {
            "Duplicate relationship ${key.name} from ${key.source} to ${key.target}"
          }
          this[key] = relationship
        }
      }
      return Schema(graphs, nodes, relationships)
    }

    @Suppress("MaxLineLength")
    /**
     * [Map] of the [Query.Property.Type.Resolvable.name] of an invoked function to a synthetic
     * value.
     * > Includes [scalar](https://neo4j.com/docs/cypher-manual/current/functions/scalar/), [mathematical](https://neo4j.com/docs/cypher-manual/current/functions/mathematical-numeric/),
     * > [string](https://neo4j.com/docs/cypher-manual/current/functions/string/), and
     * > [temporal](https://neo4j.com/docs/cypher-manual/current/functions/temporal/) functions.
     */
    private val RESOLVABLE_FUNCTIONS: Map<KString, Any> = buildMap {
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
            "time$clock()" to time,
          )
      }
      this += "duration()" to duration
      this += "duration.between()" to duration
      this += "duration.inMonths()" to duration
      this += "duration.inDays()" to duration
      this += "duration.inSeconds()" to duration
    }

    /** Parenthesize the [KList] of properties. */
    private fun KList<Property>.parenthesize(indent: String): KString {
      if (isEmpty()) return ""
      val formatted = buildString {
        append("(")
        append(System.lineSeparator())
        append(indent)
        append(joinToString(",${System.lineSeparator()}$indent", transform = Property::toString))
        append(")")
      }
      // keep multiline formatted properties if line length is greater than ~100
      return if (formatted.length > (88 + (indent.length * size))) formatted
      else
      // format properties on single line
      formatted.replace(System.lineSeparator(), "").replace("($indent", "(").replace(indent, " ")
    }

    /** Append the [metadata] to the [StringBuilder]. */
    private operator fun StringBuilder.plusAssign(metadata: KList<Metadata>) {
      metadata.forEach { (name, value) ->
        append("@$name")
        if (value.isNullOrBlank()) append(" ")
        else {
          append("(\"")
          append(value)
          append("\") ")
        }
      }
    }

    /** Filter the properties in the [Query] with the [label]. */
    private fun Query.properties(label: KString): Collection<Query.Property> = properties.filter {
      label == it.owner
    }

    /**
     * Filter the mutated properties in the [Query] with the [label], and use the resolved
     * [parameters] to transform each into a [Query.Property].
     */
    private fun Query.mutatedProperties(
      label: KString,
      parameters: Map<KString, KAny?>,
    ): Collection<Query.Property> {
      return mutatedProperties
        .filter { label == it.owner }
        .mapNotNull { (_, parameter) -> parameter.resolve(parameters).takeUnless { it is Unit } }
        .flatMap { properties ->
          if (properties is Map<*, *>)
            properties.mapNotNull properties@{ (name, value) ->
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
                    is JZonedDateTime -> Query.Property.Type.Value(value)
                    is KList<*> -> Query.Property.Type.Container(value)
                    null -> return@properties null // ignore `n += {unknown: null}`
                    else -> error("Unexpected parameter value")
                  }
                ),
              )
            }
          else emptyList()
        }
    }

    /** Find the [Property] that matches the [Query.Property]. */
    private fun KList<Property>.matches(property: Query.Property): Property? = find {
      property.name == it.name
    }

    /** Check if the [Query.Property] is within the [removedProperties]. */
    private fun Query.Property.isRemoved(removedProperties: Set<Query.RemovedProperty>): KBoolean =
      removedProperties.find { removedProperty ->
        owner == removedProperty.owner && name == removedProperty.property
      } != null

    /** Validate the [property] of the [entity] per the schema [Property] and [parameters]. */
    private fun Property.validate(
      entity: Violation.Entity,
      property: Query.Property,
      parameters: Map<KString, KAny?>,
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
      return if (values.map { (_, value) -> value }.isValid(this)) null
      else
        Violation.InvalidProperty(
          entity,
          this,
          // return the unresolved function invocation so the synthetic value remains internal
          values.map { (unresolved, resolved) ->
            if (unresolved is Query.Property.Type.Resolvable && "(" in unresolved.name)
              unresolved.name
            else resolved
          },
        )
    }

    /**
     * Resolve the value of the [Query.Property.Type.Resolvable.name] (*expected*) in the
     * [resolvable] map.
     * > [Unit] is returned if `this` property isn't [resolvable].
     */
    private fun KString.resolve(resolvable: Map<KString, KAny?>): KAny? =
      split(".").foldRight<KString, KAny?>(resolvable) { name, parameter ->
        if (parameter is Map<*, *> && name in parameter) parameter[name] else Unit
      }

    /** Determine if `this` [KList] of [property] value(s) is valid. */
    @Suppress("CyclomaticComplexMethod")
    private fun KList<KAny?>.isValid(property: Property): KBoolean {
      fun KList<KAny?>.filterNullIf(exclude: KBoolean) = if (exclude) filterNotNull() else this
      val isAny = property.type is Property.Type.Any || property.type is Property.Type.Nullable.Any
      val isList =
        property.type is Property.Type.List || property.type is Property.Type.Nullable.List
      val allowsNullable =
        isList &&
          when (property.type) {
            is Property.Type.List -> property.type.type is Property.Type.Nullable
            is Property.Type.Nullable.List -> property.type.type is Property.Type.Nullable
            else -> false
          }
      if (isList && filterNullIf(allowsNullable).any { it !is KList<*> }) return false
      if (!isAny && !isList && filterNotNull().any { it is KList<*> }) return false
      val isNullable = property.type is Property.Type.Nullable
      return flatMap { if (it is KList<*>) it.filterNullIf(allowsNullable) else listOf(it) }
        .filterNullIf(isNullable)
        .all { value ->
          when (property.type) {
            is Property.Type.List -> property.type.type.clazz.isInstance(value)
            is Property.Type.Nullable.List -> property.type.type.clazz.isInstance(value)
            is Property.Type.LiteralString -> property.type.value == value
            is Property.Type.Nullable.LiteralString -> property.type.value == value
            is Property.Type.Union ->
              property.type.types.any { t ->
                if (t is Property.Type.LiteralString) t.value == value
                else t.clazz.isInstance(value)
              }
            else -> property.type.clazz.isInstance(value)
          }
        }
    }
  }

  /**
   * [Collector] is a [io.github.cfraser.graphguard.validate.SchemaListener] that collects [graphs]
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
      val cardinality = ctx.cardinality().get()
      val properties = ctx.properties().get()
      val metadata = ctx.metadata().get()
      node =
        node.copy(
          relationships =
            node.relationships +
              Relationship(name, source, target, directed, cardinality, properties, metadata)
        )
    }

    override fun exitNode(ctx: SchemaParser.NodeContext?) {
      graph = graph.copy(nodes = graph.nodes + node)
    }

    override fun exitGraph(ctx: SchemaParser.GraphContext) {
      graphs += graph
    }

    private companion object {

      /** Get the name from the [RuleNode]. */
      fun RuleNode?.get(): KString = checkNotNull(this?.text?.takeUnless { it.isBlank() })

      /** Get the [Metadata] from the [SchemaParser.MetadataContext]. */
      tailrec fun SchemaParser.MetadataContext?.get(
        collected: MutableList<Metadata> = mutableListOf()
      ): KList<Metadata> {
        return if (this == null) collected
        else {
          val name = name().get()
          val value = metadataValue()?.stringLiteral()?.get()?.drop(1)?.dropLast(1)
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
              null -> Property.Type.Nullable.Any
              else -> error("Unexpected type")
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
            else -> error("Unexpected type value")
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

      /** Get the [Relationship.Cardinality] from the [SchemaParser.CardinalityContext]. */
      fun SchemaParser.CardinalityContext?.get(): Relationship.Cardinality? {
        this ?: return null
        val values = cardinalityValue()
        return if (values.size == 2)
          Relationship.Cardinality(values[0].toRange(), values[1].toRange())
        else error("Cardinality '$text' must specify both source and target")
      }

      /** Convert a [SchemaParser.CardinalityValueContext] to a [Relationship.Cardinality.Range]. */
      fun SchemaParser.CardinalityValueContext.toRange(): Relationship.Cardinality.Range {
        val nameToken = NAME()
        require(nameToken == null || nameToken.text == "M") {
          "Invalid cardinality symbol '${text}'; expected '1', '1?', 'M', or 'M?'"
        }
        val isMany = nameToken != null
        val isOptional = QM() != null
        return when {
          isMany && isOptional -> Relationship.Cardinality.Range(0, null)
          isMany -> Relationship.Cardinality.Range(1, null)
          isOptional -> Relationship.Cardinality.Range(0, 1)
          else -> Relationship.Cardinality.Range(1, 1)
        }
      }
    }
  }
}
