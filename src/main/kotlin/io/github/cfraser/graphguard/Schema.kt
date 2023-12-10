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

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.github.cfraser.graphguard.SchemaParser.GraphContext
import io.github.cfraser.graphguard.SchemaParser.ListContext
import io.github.cfraser.graphguard.SchemaParser.NodeContext
import io.github.cfraser.graphguard.SchemaParser.PropertiesContext
import io.github.cfraser.graphguard.SchemaParser.RelationshipContext
import io.github.cfraser.graphguard.SchemaParser.ValueContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZonedDateTime
import kotlin.properties.Delegates.notNull
import kotlin.reflect.KClass
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.RuleNode
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory

/**
 * A [Schema] describes the nodes and relationships in a [Neo4j](https://neo4j.com/) database via
 * the (potentially) interconnected [graphs].
 *
 * @property graphs the [Schema.Graph]s defining the [Schema.Node]s and [Schema.Relationship]s
 */
data class Schema internal constructor(val graphs: Set<Graph>) {

  /**
   * Parse the [text] as a [Schema].
   *
   * @throws IllegalArgumentException if the [text] or [Schema] is invalid
   */
  constructor(
      text: String
  ) : this(
      CharStreams.fromString(text)
          .let(::SchemaLexer)
          .let(::CommonTokenStream)
          .let(::SchemaParser)
          .let { parser ->
            Collector()
                .also { collector -> ParseTreeWalker.DEFAULT.walk(collector, parser.start()) }
                .graphs
          })

  /** The [Schema.Node]s in the [graphs] indexed by [Schema.Node.name]. */
  private val nodes: Map<String, Node> = graphs.flatMap(Graph::nodes).associateBy(Node::name)

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
  @VisibleForTesting
  @Suppress("CyclomaticComplexMethod", "ReturnCount")
  internal fun validate(cypher: String, parameters: Map<String, Any?>): InvalidQuery? {
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
  private fun String.validate(): String {
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
  data class Graph internal constructor(val name: String, val nodes: Set<Node>) {

    override fun toString(): String {
      return "graph $name {\n${nodes.joinToString("\n\n", transform = Node::toString)}\n}"
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
      val name: String,
      val properties: Set<Property>,
      val relationships: Set<Relationship>
  ) {

    override fun toString(): String {
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
      val name: String,
      val source: String,
      val target: String,
      val isDirected: Boolean,
      val properties: Set<Property>
  ) {

    override fun toString(): String {
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
      val name: String,
      val type: Type,
      val isList: Boolean = false,
      val isNullable: Boolean = false,
      val allowsNullable: Boolean = false
  ) {

    override fun toString(): String {
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
    enum class Type(internal val clazz: KClass<*>) {
      ANY(Any::class),
      BOOLEAN(Boolean::class),
      DATE(LocalDate::class),
      DATE_TIME(ZonedDateTime::class),
      DURATION(Duration::class),
      FLOAT(Float::class),
      INTEGER(Long::class),
      LOCAL_DATE_TIME(LocalDateTime::class),
      LOCAL_TIME(LocalTime::class),
      STRING(String::class),
      TIME(OffsetTime::class);

      override fun toString(): String {
        return name.lowercase().replaceFirstChar(Char::uppercase)
      }
    }
  }

  /**
   * [Schema.Validator] is a [Server.Handler] that validates the [Bolt.Run.query] and
   * [Bolt.Run.parameters] in a [Bolt.Run] message. If the data in the [PackStream.Structure] is
   * invalid, according to the [Schema], then a [Bolt.Failure] is returned.
   *
   * @param cacheSize the maximum entries in the cache of validated queries
   */
  inner class Validator(cacheSize: Long? = null) : Server.Handler {

    /** A [LoadingCache] of validated *Cypher* queries. */
    private val cache =
        Caffeine.newBuilder().maximumSize(cacheSize ?: 1024).build<
            Pair<String, Map<String, Any?>>, InvalidQuery?> { (query, parameters) ->
          validate(query, parameters)
        }

    override suspend fun handle(message: Bolt.Message): Bolt.Message {
      if (message !is Bolt.Run) return message
      val invalid = cache[message.query to message.parameters] ?: return message
      LOGGER.warn("Cypher query '{}' is invalid: {}", message.query, invalid.message)
      return Bolt.Failure(mapOf("code" to "GraphGuard.Invalid.Query", "message" to invalid.message))
    }
  }

  /** An [InvalidQuery] describes a *Cypher* query with a [Schema] violation. */
  internal sealed class InvalidQuery(val message: String) {

    sealed class Entity(val name: String) {

      class Node(label: String) : Entity("node $label")

      class Relationship(label: String, source: String?, target: String?) :
          Entity("relationship $label from $source to $target")
    }

    class Unknown(entity: Entity) : InvalidQuery("Unknown ${entity.name}")

    class UnknownProperty(entity: Entity, property: String) :
        InvalidQuery("Unknown property '$property' for ${entity.name}")

    class InvalidProperty(entity: Entity, property: Property, values: List<Any?>) :
        InvalidQuery(
            "Invalid query value(s) '${values.joinToString()}' for property '$property' on ${entity.name}")

    override fun equals(other: Any?): Boolean {
      return when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> message == (other as? InvalidQuery)?.message
      }
    }

    override fun hashCode(): Int {
      return message.hashCode()
    }

    override fun toString(): String {
      return "${InvalidQuery::class.simpleName}(message='$message')"
    }
  }

  private companion object {

    val LOGGER = LoggerFactory.getLogger(Validator::class.java)!!

    /** Parenthesize the [Set] of properties. */
    fun Set<Property>.parenthesize(): String {
      return if (isEmpty()) "" else "(${joinToString(transform = Property::toString)})"
    }

    /** Filter the properties in the [Query] with the [label]. */
    fun Query.properties(label: String): Collection<Query.Property> {
      return properties.filter { label == it.owner }
    }

    /**
     * Filter the mutated properties in the [Query] with the [label], and use the resolved
     * [parameters] to transform each into a [Query.Property].
     */
    fun Query.mutatedProperties(
        label: String,
        parameters: Map<String, Any?>
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
                        checkNotNull(name as? String) { "Unexpected parameter name" },
                        setOf(
                            when (value) {
                              is Boolean,
                              is Duration,
                              is LocalDate,
                              is LocalDateTime,
                              is LocalTime,
                              is Number,
                              is OffsetTime,
                              is String,
                              is ZonedDateTime,
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
        parameters: Map<String, Any?>
    ): InvalidQuery? {
      fun List<Any?>.filterNullIf(exclude: Boolean) = if (exclude) filterNotNull() else this
      fun List<Any?>.isValid(): Boolean {
        if (isList && filterNullIf(allowsNullable).any { it !is MutableList<*> }) return false
        if (!isList && filterNotNull().any { it is MutableList<*> }) return false
        return flatMap { if (it is MutableList<*>) it.filterNullIf(allowsNullable) else listOf(it) }
            .filterNullIf(isNullable)
            .all(type.clazz::isInstance)
      }
      val values =
          property.values
              .map {
                when (it) {
                  is Query.Property.Type.Value -> it.value
                  is Query.Property.Type.Container -> it.values
                  else -> Unit
                }
              }
              .filter { it !is Unit } + property.resolve(parameters)
      return if (values.isValid()) null else InvalidQuery.InvalidProperty(entity, this, values)
    }

    /**
     * Resolve the [Query.Property.Type.Resolvable] values of the [parameters] in the
     * [Query.Property].
     */
    private fun Query.Property.resolve(parameters: Map<String, Any?>): Set<Any?> {
      return values
          .filterIsInstance<Query.Property.Type.Resolvable>()
          .map { resolvable -> resolvable.name.resolve(parameters) }
          .filter { it !is Unit }
          .toSet()
    }

    /** Resolve the value of the name in the [parameters]. */
    private fun String.resolve(parameters: Map<String, Any?>): Any? {
      return split(".").foldRight<String, Any?>(parameters) { name, parameter ->
        if (parameter is Map<*, *> && name in parameter) parameter[name] else Unit
      }
    }
  }

  /**
   * [Collector] is a [io.github.cfraser.graphguard.SchemaListener] that collects [graphs] while
   * walking the parse tree.
   */
  private class Collector(val graphs: MutableSet<Graph> = mutableSetOf()) : SchemaBaseListener() {

    private var graph by notNull<Graph>()
    private var node by notNull<Node>()

    override fun enterGraph(ctx: GraphContext) {
      graph = Graph(+ctx.name(), emptySet())
    }

    override fun enterNode(ctx: NodeContext) {
      val name = +ctx.name()
      val properties = +ctx.properties()
      node = Node(name, properties, emptySet())
    }

    override fun enterRelationship(ctx: RelationshipContext) {
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

    override fun exitNode(ctx: NodeContext?) {
      graph = graph.copy(nodes = graph.nodes + node)
    }

    override fun exitGraph(ctx: GraphContext) {
      graphs += graph
    }

    private companion object {

      /** Get the name from the [RuleNode]. */
      operator fun RuleNode?.unaryPlus(): String {
        return checkNotNull(this?.text?.takeUnless { it.isBlank() })
      }

      /** Get the properties from the [PropertiesContext]. */
      operator fun PropertiesContext?.unaryPlus(): Set<Property> {
        return this?.property()
            ?.map { ctx ->
              val name = +ctx.name()
              val type =
                  when (val type = ctx.type()?.run { value() ?: list() }) {
                        is ValueContext -> +type
                        is ListContext -> +type.value()
                        else -> error("Unknown property type")
                      }
                      .replace(Regex("([a-z])([A-Z]+)")) {
                        "${it.groupValues[1]}_${it.groupValues[2]}"
                      }
                      .uppercase()
                      .runCatching(Property.Type::valueOf)
                      .getOrNull()
                      .let(::checkNotNull)
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
