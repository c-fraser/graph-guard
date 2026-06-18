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
import io.github.cfraser.graphguard.validate.Schema.Violation.Entity.Node
import io.github.cfraser.graphguard.validate.Schema.Violation.Entity.Relationship as VRelationship
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality.Limit
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality.Side
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality.Side.SOURCE
import io.github.cfraser.graphguard.validate.Schema.Violation.InvalidCardinality.Side.TARGET
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
          session.verifyNodes(label) +
            session.verifyRelationships(label, SOURCE) +
            session.verifyRelationships(label, TARGET)
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
  private suspend fun AsyncSession.verifyNodes(label: String): Collection<Violation> =
    run(
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
              )
            }
        missing + unknown
      }

  /**
   * Check for [Violation]s on the [SOURCE] or [TARGET] relationships, on the nodes with the
   * [label].
   */
  private suspend fun AsyncSession.verifyRelationships(
    label: String,
    side: Side,
  ): Collection<Violation> {
    val records =
      run(
        when (side) {
          SOURCE ->
            $$"""
        MATCH (s:$${label.quoted()})
        OPTIONAL MATCH (s)-[r]->(t)
        RETURN elementId(s) AS node,
          elementId(r) AS id,
          type(r) AS type,
          keys(r) AS properties,
          labels(t) AS other
        """
              .trimIndent()
          TARGET ->
            $$"""
        MATCH (t:$${label.quoted()})
        OPTIONAL MATCH (s)-[r]->(t)
        RETURN elementId(t) AS node,
          elementId(r) AS id,
          type(r) AS type,
          keys(r) AS properties,
          labels(s) AS other
        """
              .trimIndent()
        }
      )
    return records
      .filterNot { record -> record["id"].isNull }
      .flatMap { record ->
        verifyRelationship(label, side, record)
      } + verifyRelationships(label, side, records)
  }

  /** Check the [record] for [VRelationship] [Violation]s. */
  private fun verifyRelationship(label: String, side: Side, record: Record): Collection<Violation> {
    val type = record["type"].asString()
    val other = record["other"].asList(Value::asString)
    val schemaRelationship =
      when (side) {
        SOURCE ->
          other.firstNotNullOfOrNull { target ->
            schema.relationships[Relationship.Id(type, label, target)]
          }
        TARGET ->
          other.firstNotNullOfOrNull { source ->
            schema.relationships[Relationship.Id(type, source, label)]
          }
      }
    val (sources, targets) =
      when (side) {
        SOURCE -> listOf(label) to other
        TARGET -> other to listOf(label)
      }
    val id = record["id"].asString()
    val properties = record["properties"].asList(Value::asString).toSet()
    val entity =
      VRelationship(
        type,
        schemaRelationship?.let { relationship -> listOf(relationship.source) } ?: sources,
        schemaRelationship?.let { relationship -> listOf(relationship.target) } ?: targets,
      )
    if (schemaRelationship == null) {
      return listOf(Violation(Unknown(entity), id))
    }
    val missing =
      schemaRelationship.properties
        .filterNot { property -> property.type is Type.Nullable }
        .filterNot { property -> property.name in properties }
        .map { property ->
          Violation(MissingProperty(entity, property.name), id)
        }
    val known = schemaRelationship.properties.mapTo(mutableSetOf(), Schema.Property::name)
    val unknown =
      properties
        .filterNot { property -> property in known }
        .map { property ->
          Violation(UnknownProperty(entity, property), id)
        }
    return missing + unknown
  }

  /** Check the [records] for [InvalidCardinality]. */
  private fun verifyRelationships(
    label: String,
    side: Side,
    records: Collection<Record>,
  ): Collection<Violation> {
    fun Relationship.range() = if (side == SOURCE) cardinality?.source else cardinality?.target
    fun Relationship.sideLabel() = if (side == SOURCE) source else target
    fun Relationship.otherLabel() = if (side == SOURCE) target else source
    val byNode = records.groupBy { record -> record["node"].asString() }
    return schema.relationships.values
      .filter { relationship ->
        relationship.sideLabel() == label && relationship.range() != null
      }
      .flatMap { relationship ->
        val range = relationship.range()!!
        byNode.mapNotNull violation@{ (node, rows) ->
          val count = rows.count { row ->
            !row["id"].isNull &&
              row["type"].asString() == relationship.name &&
              relationship.otherLabel() in row["other"].asList(Value::asString)
          }
          val max = range.max
          val (limit, bound) =
            when {
              count < range.min -> Limit.MIN to range.min
              max != null && count > max -> Limit.MAX to max
              else -> return@violation null
            }
          Violation(
            InvalidCardinality(relationship.name, side, count, limit, bound),
            node,
          )
        }
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
   */
  @JvmRecord
  data class Violation(val schemaViolation: Schema.Violation, val elementId: String? = null)

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
      } catch (e: Exception) {
        LOGGER.error("Failed to run query: {} {}", parameters, cypher, e)
        emptyList()
      }
    }

    /** Backtick-quote `this` label/type, escaping any backticks it contains. */
    fun String.quoted(): String = "`${replace("`", "``")}`"
  }
}
