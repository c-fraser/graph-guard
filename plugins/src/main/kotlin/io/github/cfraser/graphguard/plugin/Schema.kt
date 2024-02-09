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

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.RuleNode
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetTime
import java.time.ZonedDateTime
import kotlin.Any
import kotlin.properties.Delegates.notNull
import kotlin.reflect.KClass
import java.time.Duration as JDuration
import java.time.LocalDate as JLocalDate
import java.time.LocalDateTime as JLocalDateTime
import java.time.LocalTime as JLocalTime
import java.time.ZonedDateTime as JZonedDateTime
import kotlin.Any as KAny
import kotlin.Boolean as KBoolean
import kotlin.String as KString

/**
 * A [Schema] describes the nodes and relationships in a [Neo4j](https://neo4j.com/) database via
 * the (potentially) interconnected [graphs].
 *
 * @property graphs the [Schema.Graph]s defining the [Schema.Node]s and [Schema.Relationship]s
 */
data class Schema internal constructor(val graphs: Set<Graph>) {

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

  /** The [Schema.Node]s in the [graphs] indexed by [Schema.Node.name]. */
  private val nodes: Map<KString, Node> = graphs.flatMap(Graph::nodes).associateBy(Node::name)

  /** The [Schema.Relationship]s in the [graphs] indexed by [Query.Relationship]. */
  private val relationships: Map<Query.Relationship, Relationship> = buildMap {
    graphs.flatMap(Graph::nodes).flatMap(Node::relationships).forEach { relationship ->
      val key =
          Query.Relationship(
              relationship.name, relationship.source.validate(), relationship.target.validate())
      require(key !in this) {
        "Duplicate relationship ${key.label} from ${key.source} to ${key.target}"
      }
      this[key] = relationship
    }
  }

  /**
   * Validate that the *Cypher* [Query] and [parameters] adhere to the [nodes] and [relationships].
   *
   * Returns an [InvalidQuery] with [Bolt.Failure.metadata] if the [cypher] is invalid.
   */
  /*@VisibleForTesting*/
  @Suppress("CyclomaticComplexMethod", "ReturnCount")
  internal fun validate(cypher: KString, parameters: Map<KString, KAny?>): InvalidQuery? {
    val query = Query.parse(cypher) ?: return null
    for (queryNode in query.nodes) {
      val entity = InvalidQuery.Entity.Node(queryNode)
      val schemaNode = nodes[queryNode] ?: return InvalidQuery.Unknown(entity)
      for (queryProperty in
          query.properties(queryNode) + query.mutatedProperties(queryNode, parameters)) {
        val schemaProperty =
            schemaNode.properties.matches(queryProperty)
                ?: return InvalidQuery.UnknownProperty(entity, queryProperty.name)
        return schemaProperty.validate(entity, queryProperty, parameters) ?: continue
      }
    }
    for (queryRelationship in query.relationships) {
      val (label, source, target) = queryRelationship
      if (source == null || target == null) continue
      val entity = InvalidQuery.Entity.Relationship(label, source, target)
      val schemaRelationship =
          relationships[queryRelationship] ?: return InvalidQuery.Unknown(entity)
      for (queryProperty in query.properties(label)) {
        val schemaProperty =
            schemaRelationship.properties.matches(queryProperty)
                ?: return InvalidQuery.UnknownProperty(entity, queryProperty.name)
        return schemaProperty.validate(entity, queryProperty, parameters) ?: continue
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
    } else apply { require(this in nodes) { "Invalid node reference '$this'" } }
  }

  /**
   * A [Graph] is a [Set] of [nodes] that specify the expected entities in a *Neo4j* database.
   *
   * @property name the name of the graph
   * @property nodes the nodes and relationships in the graph
   */
  data class Graph internal constructor(val name: KString, val nodes: Set<Node>) {

    override fun toString(): KString {
      return "graph $name {\n${nodes.joinToString("\n", transform = Node::toString)}\n}"
    }
  }

  /**
   * A [Node] in the graph.
   *
   * @property name the name of the node
   * @property properties the node properties
   */
  data class Node
  internal constructor(
      val name: KString,
      val properties: Set<Property>,
      val relationships: Set<Relationship>
  ) {

    override fun toString(): KString {
      val relationships =
          ":\n${relationships.joinToString(",\n", transform = Relationship::toString)}"
              .takeUnless { relationships.isEmpty() }
              .orEmpty()
      return "  node $name${properties.parenthesize()}$relationships;"
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
   */
  data class Relationship
  internal constructor(
      val name: KString,
      val source: KString,
      val target: KString,
      val isDirected: KBoolean,
      val properties: Set<Property>
  ) {

    override fun toString(): KString {
      val direction = if (isDirected) "->" else "--"
      return "    $name${properties.parenthesize()} $direction $target"
    }
  }

  /**
   * A [Property] on a [Node] or [Relationship].
   *
   * @property name the name of the property
   * @property type the property type
   * @property isList whether the [Property] is a
   *   [List](https://neo4j.com/docs/cypher-manual/5/values-and-types/lists/) of the [type]
   * @property isNullable whether the [Property] value is nullable
   * @property allowsNullable whether the [Property] (container) allows nullable values
   */
  data class Property
  internal constructor(
      val name: KString,
      val type: Type,
      val isList: KBoolean = false,
      val isNullable: KBoolean = false,
      val allowsNullable: KBoolean = false
  ) {

    override fun toString(): KString {
      val type =
          if (isList) {
            val nullable = if (allowsNullable) "?" else ""
            "List<$type$nullable>"
          } else "$type"
      val nullable = if (isNullable) "?" else ""
      return "$name: $type$nullable"
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

      override fun toString(): KString {
        return "${this::class.simpleName}"
      }
    }
  }

  /**
   * [Schema.Validator] is a [Server.Plugin] that validates the [Bolt.Run.query] and
   * [Bolt.Run.parameters] in a [Bolt.Run] message. If the data in the [Bolt.Message] is invalid,
   * according to the [Schema], then a [Bolt.Failure] is returned.
   *
   * @param cacheSize the maximum entries in the cache of validated queries
   */
  inner class Validator(cacheSize: Long? = null) : Server.Plugin {

    /** A [LoadingCache] of validated *Cypher* queries. */
    private val cache =
        Caffeine.newBuilder().maximumSize(cacheSize ?: 1024).build<
            Pair<KString, Map<KString, KAny?>>, InvalidQuery?> { (query, parameters) ->
          validate(query, parameters)
        }

    override suspend fun intercept(message: Bolt.Message): Bolt.Message {
      if (message !is Bolt.Run) return message
      val invalid = cache[message.query to message.parameters] ?: return message
      LOGGER.info("Cypher query '{}' is invalid: {}", message.query, invalid.message)
      return Bolt.Failure(mapOf("code" to "GraphGuard.Invalid.Query", "message" to invalid.message))
    }
  }

  /** An [InvalidQuery] describes a *Cypher* query with a [Schema] violation. */
  internal sealed class InvalidQuery(val message: KString) {

    sealed class Entity(val name: KString) {

      class Node(label: KString) : Entity("node $label")

      class Relationship(label: KString, source: KString?, target: KString?) :
          Entity("relationship $label from $source to $target")
    }

    class Unknown(entity: Entity) : InvalidQuery("Unknown ${entity.name}")

    class UnknownProperty(entity: Entity, property: KString) :
        InvalidQuery("Unknown property '$property' for ${entity.name}")

    class InvalidProperty(entity: Entity, property: Property, values: List<KAny?>) :
        InvalidQuery(
            @Suppress("MaxLineLength")
            "Invalid query value(s) '${values.sortedBy { "$it" }.joinToString()}' for property '$property' on ${entity.name}")

    @Suppress("WrongEqualsTypeParameter")
    override fun equals(other: KAny?): KBoolean {
      return when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> message == (other as? InvalidQuery)?.message
      }
    }

    override fun hashCode(): Int {
      return message.hashCode()
    }

    override fun toString(): KString {
      return "${InvalidQuery::class.simpleName}(message='$message')"
    }
  }

  private companion object {

    val LOGGER = LoggerFactory.getLogger(Validator::class.java)!!

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

    /** Parenthesize the [Set] of properties. */
    fun Set<Property>.parenthesize(): KString {
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
                              is List<*> -> Query.Property.Type.Container(value)
                              else -> error("Unexpected parameter value")
                            }))
                  }
              else -> emptyList()
            }
          }
    }

    /** Find the [Property] that matches the [Query.Property]. */
    fun Set<Property>.matches(property: Query.Property): Property? {
      return find { property.name == it.name }
    }

    /** Validate the [property] of the [entity] per the schema [Property] and [parameters]. */
    fun Property.validate(
        entity: InvalidQuery.Entity,
        property: Query.Property,
        parameters: Map<KString, KAny?>
    ): InvalidQuery? {
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
          InvalidQuery.InvalidProperty(
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

    /** Determine if `this` [List] of [Property] value(s) is valid. */
    context(Property)
    fun List<KAny?>.isValid(): KBoolean {
      fun List<KAny?>.filterNullIf(exclude: KBoolean) = if (exclude) filterNotNull() else this
      if (isList && filterNullIf(allowsNullable).any { it !is MutableList<*> }) return false
      if (!isList && filterNotNull().any { it is MutableList<*> }) return false
      return flatMap { if (it is MutableList<*>) it.filterNullIf(allowsNullable) else listOf(it) }
          .filterNullIf(isNullable)
          .all(type.clazz::isInstance)
    }
  }

  /**
   * [Collector] is a [io.github.cfraser.graphguard.plugin.SchemaListener] that collects [graphs]
   * while walking the parse tree.
   */
  private class Collector(val graphs: MutableSet<Graph> = mutableSetOf()) : SchemaBaseListener() {

    private var graph by notNull<Graph>()
    private var node by notNull<Node>()

    override fun enterGraph(ctx: SchemaParser.GraphContext) {
      graph = Graph(+ctx.name(), emptySet())
    }

    override fun enterNode(ctx: SchemaParser.NodeContext) {
      val name = +ctx.name()
      val properties = +ctx.properties()
      node = Node(name, properties, emptySet())
    }

    override fun enterRelationship(ctx: SchemaParser.RelationshipContext) {
      val name = +ctx.name()
      val source = node.name
      val target = +ctx.target()
      val directed = ctx.DIRECTED() != null
      val properties = +ctx.properties()
      node =
          node.copy(
              relationships =
                  node.relationships + Relationship(name, source, target, directed, properties))
    }

    override fun exitNode(ctx: SchemaParser.NodeContext?) {
      graph = graph.copy(nodes = graph.nodes + node)
    }

    override fun exitGraph(ctx: SchemaParser.GraphContext) {
      graphs += graph
    }

    private companion object {

      /** Get the name from the [RuleNode]. */
      operator fun RuleNode?.unaryPlus(): KString {
        return checkNotNull(this?.text?.takeUnless { it.isBlank() })
      }

      /** Get the properties from the [PropertiesContext]. */
      @Suppress("CyclomaticComplexMethod")
      operator fun SchemaParser.PropertiesContext?.unaryPlus(): Set<Property> {
        return this?.property()
            ?.map { ctx ->
              val name = +ctx.name()
              val type =
                  when (val type =
                      when (val type = ctx.type()?.run { value() ?: list() }) {
                        is SchemaParser.ValueContext -> +type
                        is SchemaParser.ListContext -> +type.value()
                        else -> error("Unknown property type")
                      }) {
                    "Any" -> Property.Type.Any
                    "Boolean" -> Property.Type.Boolean
                    "Date" -> Property.Type.Date
                    "DateTime" -> Property.Type.DateTime
                    "Duration" -> Property.Type.Duration
                    "Float" -> Property.Type.Float
                    "Integer" -> Property.Type.Integer
                    "LocalDateTime" -> Property.Type.LocalDateTime
                    "LocalTime" -> Property.Type.LocalTime
                    "String" -> Property.Type.String
                    "Time" -> Property.Type.Time
                    else -> error("Unexpected property type '$type'")
                  }
              val isList = ctx.type().list() != null
              val isNullable = ctx.type().QM() != null
              val allowsNullable = ctx.type().list()?.QM() != null
              Property(name, type, isList, isNullable, allowsNullable)
            }
            ?.toSet() ?: emptySet()
      }
    }
  }
}
