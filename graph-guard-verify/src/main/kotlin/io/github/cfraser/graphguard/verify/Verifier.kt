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
package io.github.cfraser.graphguard.verify

import io.github.cfraser.graphguard.utils.Internal
import io.github.cfraser.graphguard.validate.Schema
import io.github.cfraser.graphguard.validate.Schema.Property.Type
import io.github.cfraser.graphguard.validate.Schema.Relationship
import io.github.cfraser.graphguard.validate.Schema.Violation.Entity
import io.github.cfraser.graphguard.validate.Schema.Violation.Entity.Node
import io.github.cfraser.graphguard.validate.Schema.Violation.Entity.Relationship as VRelationship
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality.Limit
import io.github.cfraser.graphguard.validate.Schema.Violation.MissingProperty
import io.github.cfraser.graphguard.validate.Schema.Violation.Unknown
import io.github.cfraser.graphguard.validate.Schema.Violation.UnknownProperty
import kotlinx.coroutines.future.await
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.Value
import org.neo4j.driver.async.AsyncSession
import org.slf4j.LoggerFactory

/**
 * [Verifier] uses the [driver] and [config] to verify that the entities in a
 * [Neo4j](https://neo4j.com/) graph conform to the [schema].
 *
 * [Verifier] is the client-side, data-introspecting analog of [Schema.validate]. Instead of
 * validating a *Cypher* query before it runs, it verifies the data already stored in the graph.
 *
 * > The [driver] is owned and managed by the caller; [Verifier] **doesn't** [Driver.close] it.
 *
 * @property driver the [Driver] used to query the graph
 * @property schema the [Schema] to verify the graph against
 * @property config the [Config] tuning [verify] behavior
 */
@Internal
class Verifier
@JvmOverloads
constructor(
  private val driver: Driver,
  private val schema: Schema,
  private val config: Config = Config(),
) {

  /**
   * Verify the nodes, including the incoming and outgoing relationships, with the given [labels].
   *
   * @param labels the labels of the nodes to verify
   * @return the [Violation]s found, empty when the graph is [schema] compliant
   */
  suspend fun verify(labels: Collection<String>): Collection<Violation> {
    LOGGER.debug("Verifying labels: {}", labels)
    val session =
      driver.session(
        AsyncSession::class.java,
        config.database?.let(SessionConfig::forDatabase) ?: SessionConfig.defaultConfig(),
      )
    return try {
      labels
        .toSet()
        .flatMap violations@{ label ->
          if (label !in schema.nodes) {
            return@violations listOf(Violation(Unknown(Node(label))))
          }
          session.verifyNodes(label) + session.verifyRelationships(label)
        }
        .filterNot { violation ->
          config.ignore?.containsMatchIn(violation.schemaViolation.ruleViolation.message) == true
        }
        .distinctBy { violation -> violation.schemaViolation.ruleViolation }
        .also { violations ->
          LOGGER.info("Found violations: {}", violations)
        }
    } finally {
      session.closeAsync().await()
    }
  }

  /** Check for [Violation]s on the nodes with the [label]. */
  private suspend fun AsyncSession.verifyNodes(label: String): Collection<Violation> {
    return run(
        """
        MATCH (n:${label.quoted()})
        RETURN elementId(n) AS id, labels(n) AS labels, keys(n) AS properties
        """
          .trimIndent()
      )
      .flatMap { record ->
        val id = record["id"].asString()
        val properties = record["properties"].asList(Value::asString).toSet()
        val nodeLabels =
          record["labels"].asList(Value::asString).filter { nodeLabel -> nodeLabel in schema.nodes }
        val missing = nodeLabels.flatMap { nodeLabel ->
          schema.nodes
            .getValue(nodeLabel)
            .properties
            .filterNot { property -> property.type is Type.Nullable }
            .filterNot { property -> property.name in properties }
            .map { property ->
              Violation(
                MissingProperty(
                  Node(nodeLabel),
                  property.name,
                ),
                id,
                Node(nodeLabel),
              )
            }
        }
        val known =
          nodeLabels.flatMapTo(mutableSetOf()) { schemaLabel ->
            schema.nodes.getValue(schemaLabel).properties.map(Schema.Property::name)
          }
        val unknown =
          properties
            .filterNot { property -> property in known }
            .map { property ->
              Violation(
                UnknownProperty(Node(label), property),
                id,
                Node(label),
              )
            }
        missing + unknown
      }
  }

  /** Check for [Violation]s on the relationships of the nodes with the [label]. */
  private suspend fun AsyncSession.verifyRelationships(label: String): Collection<Violation> {
    return run(
        $$"""
        MATCH (n:$${label.quoted()})
        OPTIONAL MATCH (n)-[out]->(ot)
        WITH n,
          collect(CASE WHEN out IS NOT NULL
            THEN {id: elementId(out), type: type(out), props: keys(out), other: labels(ot)}
            END) AS outgoing
        OPTIONAL MATCH (os)-[inc]->(n)
        WITH n, outgoing,
          collect(CASE WHEN inc IS NOT NULL
            THEN {id: elementId(inc), type: type(inc), props: keys(inc), other: labels(os)}
            END) AS incoming
        RETURN elementId(n) AS node, outgoing, incoming
        """
          .trimIndent()
      )
      .flatMap { record ->
        val nodeId = record["node"].asString()
        val outgoing = record["outgoing"].asList { value -> value }
        val incoming = record["incoming"].asList { value -> value }
        outgoing.flatMap { relationship -> verifyRelationship(label, relationship, true) } +
          incoming.flatMap { relationship -> verifyRelationship(label, relationship, false) } +
          verifyCardinality(label, nodeId, outgoing, incoming)
      }
  }

  /** Check the relationship [rel] for [VRelationship] [Violation]s. */
  private fun verifyRelationship(
    label: String,
    rel: Value,
    source: Boolean,
  ): Collection<Violation> {
    val type = rel["type"].asString()
    val other = rel["other"].asList(Value::asString)
    val id = rel["id"].asString()
    val schemaRelationship =
      if (source) {
        other.firstNotNullOfOrNull { target ->
          schema.relationships[Relationship.Id(type, label, target)]
        }
      } else {
        other.firstNotNullOfOrNull { src ->
          schema.relationships[Relationship.Id(type, src, label)]
        }
      }
    val (sources, targets) = if (source) listOf(label) to other else other to listOf(label)
    val entity =
      VRelationship(
        type,
        schemaRelationship?.let { relationship -> listOf(relationship.source) } ?: sources,
        schemaRelationship?.let { relationship -> listOf(relationship.target) } ?: targets,
      )
    if (schemaRelationship == null) return listOf(Violation(Unknown(entity), id, entity))
    val properties = rel["props"].asList(Value::asString).toSet()
    val missing =
      schemaRelationship.properties
        .filterNot { property -> property.type is Type.Nullable }
        .filterNot { property -> property.name in properties }
        .map { property -> Violation(MissingProperty(entity, property.name), id, entity) }
    val known = schemaRelationship.properties.mapTo(mutableSetOf(), Schema.Property::name)
    val unknown =
      properties
        .filterNot { property -> property in known }
        .map { property -> Violation(UnknownProperty(entity, property), id, entity) }
    return missing + unknown
  }

  /** Check [outgoing] and [incoming] relationships for [InvalidCardinality] on the [label] node. */
  private fun verifyCardinality(
    label: String,
    nodeId: String,
    outgoing: List<Value>,
    incoming: List<Value>,
  ): Collection<Violation> {
    fun Relationship.verify(
      range: Relationship.Cardinality.Range?,
      nLabel: String,
      otherLabel: String,
      relationships: List<Value>,
    ): Violation? {
      if (range == null || nLabel != label) return null
      val count = relationships.count { rel ->
        rel["type"].asString() == name && otherLabel in rel["other"].asList(Value::asString)
      }
      val max = range.max
      val (limit, bound) =
        when {
          count < range.min -> Limit.MIN to range.min
          max != null && count > max -> Limit.MAX to max
          else -> return null
        }
      return Violation(
        InvalidCardinality(
          VRelationship(name, listOf(source), listOf(target)),
          count,
          limit,
          bound,
        ),
        nodeId,
        Node(nLabel),
      )
    }
    return schema.relationships.values.flatMap { relationship ->
      listOfNotNull(
        relationship.verify(
          relationship.cardinality?.source,
          relationship.source,
          relationship.target,
          outgoing,
        ),
        relationship.verify(
          relationship.cardinality?.target,
          relationship.target,
          relationship.source,
          incoming,
        ),
      )
    }
  }

  /**
   * Configuration for [Verifier.verify].
   *
   * @property database if not `null`, the database to verify, otherwise, the default database for
   *   the authenticated user
   * @property ignore if not `null`, [Verifier.verify] ignores [Violation.schemaViolation]s that
   *   match the [Regex]
   */
  @JvmRecord
  data class Config
  @JvmOverloads
  constructor(
    val database: String? = null,
    val ignore: Regex? = null,
  )

  /**
   * A [schemaViolation] found for a specific node or relationship in the graph.
   *
   * @property schemaViolation the [Schema.Violation]
   * @property elementId the
   *   [element id](https://neo4j.com/docs/cypher-manual/current/functions/scalar/#functions-elementid)
   *   of the offending node or relationship
   * @property entity the [Entity] the [schemaViolation] corresponds to
   */
  @JvmRecord
  data class Violation(
    val schemaViolation: Schema.Violation,
    val elementId: String? = null,
    val entity: Entity? = null,
  )

  private companion object {

    val LOGGER = LoggerFactory.getLogger(Verifier::class.java)!!

    /** Run the [cypher] with the [parameters] in a read transaction then collect the [Record]s. */
    suspend fun AsyncSession.run(
      cypher: String,
      parameters: Map<String, Any?> = emptyMap(),
    ): Collection<Record> {
      LOGGER.debug("Running query: {} {}", parameters, cypher)
      return try {
        executeReadAsync { tx ->
            tx.runAsync(cypher, parameters).thenCompose { cursor -> cursor.listAsync() }
          }
          .await()
      } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        LOGGER.error("Failed to run query: {} {}", parameters, cypher, e)
        emptyList()
      }
    }

    /** Backtick-quote `this` label/type, escaping any backticks it contains. */
    fun String.quoted(): String = "`${replace("`", "``")}`"
  }
}
