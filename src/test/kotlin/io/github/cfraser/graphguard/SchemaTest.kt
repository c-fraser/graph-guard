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

import io.github.cfraser.graphguard.knit.MOVIES_SCHEMA
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class SchemaTest : FunSpec() {

  init {
    test("parse graph schema") { Schema.parse(MOVIES_SCHEMA) shouldBe MOVIES_GRAPH_SCHEMA }

    test("render graph schema") { MOVIES_GRAPH.render() shouldBe MOVIES_SCHEMA.trim() }

    context("validate cypher queries") {
      @IsStableType
      data class Data(
          val query: String,
          val parameters: Map<String, Any?>,
          val expected: Schema.InvalidQuery?
      )
      infix fun String.with(parameters: Map<String, Any?>) = this to parameters
      infix fun Pair<String, Map<String, Any?>>.expect(expected: Schema.InvalidQuery?) =
          Data(first, second, expected)
      withData(
          "" with emptyMap() expect null,
          "MATCH (theater:Theater)-[:SHOWING]->(movie:Movie) RETURN theater, movie" with
              emptyMap() expect
              Schema.InvalidQuery.Unknown(Schema.InvalidQuery.Entity.Node("Theater")),
          "MATCH (TheMatrix:Movie {title:'The Matrix'}) SET TheMatrix.budget = 63000000" with
              emptyMap() expect
              Schema.InvalidQuery.UnknownProperty(
                  Schema.InvalidQuery.Entity.Node("Movie"), "budget"),
          "MATCH (person:Person)-[:WATCHED]->(movie:Movie) RETURN person, movie" with
              emptyMap() expect
              Schema.InvalidQuery.Unknown(
                  Schema.InvalidQuery.Entity.Relationship("WATCHED", "Person", "Movie")),
          """MATCH (:Person)-[produced:PRODUCED]->(:Movie {title:'The Matrix'})
              |SET produced.company = 'Warner Bros.'"""
              .trimMargin() with
              emptyMap() expect
              Schema.InvalidQuery.UnknownProperty(
                  Schema.InvalidQuery.Entity.Relationship("PRODUCED", "Person", "Movie"),
                  "company"),
          MoviesGraph.CREATE.last() with emptyMap() expect null,
          MoviesGraph.MATCH_TOM_HANKS with emptyMap() expect null,
          MoviesGraph.MATCH_CLOUD_ATLAS with emptyMap() expect null,
          MoviesGraph.MATCH_10_PEOPLE with emptyMap() expect null,
          MoviesGraph.MATCH_NINETIES_MOVIES with emptyMap() expect null,
          MoviesGraph.MATCH_TOM_HANKS_MOVIES with emptyMap() expect null,
          MoviesGraph.MATCH_CLOUD_ATLAS_DIRECTOR with emptyMap() expect null,
          MoviesGraph.MATCH_TOM_HANKS_CO_ACTORS with emptyMap() expect null,
          MoviesGraph.MATCH_CLOUD_ATLAS_PEOPLE with emptyMap() expect null,
          MoviesGraph.MATCH_SIX_DEGREES_OF_KEVIN_BACON with emptyMap() expect null,
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with emptyMap() expect null,
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with mapOf("name" to "Tom Hanks") expect null,
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("fullName" to "Thomas Jeffrey Hanks") expect
              null,
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("name" to null) expect
              Schema.InvalidQuery.InvalidProperty(PERSON, NAME, listOf("Kevin Bacon", null)),
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("name" to 123) expect
              Schema.InvalidQuery.InvalidProperty(PERSON, NAME, listOf("Kevin Bacon", 123)),
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("name" to listOf("Tom Hanks")) expect
              Schema.InvalidQuery.InvalidProperty(
                  PERSON, NAME, listOf("Kevin Bacon", listOf("Tom Hanks"))),
          MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS with emptyMap() expect null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to "Keanu Reeves") expect
              null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("fullName" to "Keanu Charles Reeves") expect
              null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to null) expect
              Schema.InvalidQuery.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", null)),
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to 123) expect
              Schema.InvalidQuery.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", 123)),
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to listOf("Keanu Reeves")) expect
              Schema.InvalidQuery.InvalidProperty(
                  PERSON, NAME, listOf("Tom Hanks", listOf("Keanu Reeves"))),
          MoviesGraph.MERGE_KEANU with emptyMap() expect null,
          MoviesGraph.MERGE_KEANU with mapOf("properties" to mapOf("born" to 1963L)) expect null,
          MoviesGraph.MERGE_KEANU with
              mapOf("properties" to mapOf("fullName" to "Keanu Charles Reeves")) expect
              Schema.InvalidQuery.UnknownProperty(PERSON, "fullName")) {
              (query, parameters, expected) ->
            MOVIES_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected
          }
    }
  }

  private companion object {

    val PERSON = Schema.InvalidQuery.Entity.Node("Person")
    val NAME = Schema.Property("name", Schema.Property.Type.STRING)
  }
}
