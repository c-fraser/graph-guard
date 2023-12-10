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
import io.github.cfraser.graphguard.knit.PLACES_SCHEMA
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZonedDateTime

class SchemaTest : FunSpec() {

  init {
    test("parse graph schema") {
      Schema(MOVIES_SCHEMA + PLACES_SCHEMA) shouldBe MOVIES_AND_PLACES_GRAPH_SCHEMA
    }

    test("render graph schema") { "$MOVIES_GRAPH" shouldBe MOVIES_SCHEMA.trim() }

    context("validate cypher queries") {
      withData(
          "" with emptyMap() expect null,
          "MATCH (person:Person)-[:ACTED_IN]->(tvShow:TVShow) RETURN person, tvShow" with
              emptyMap() expect
              Schema.InvalidQuery.Unknown(Schema.InvalidQuery.Entity.Node("TVShow")),
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
              Schema.InvalidQuery.UnknownProperty(PERSON, "fullName"),
          "MATCH (theater:Theater)-[:SHOWING]->(movie:Movie) RETURN theater, movie" with
              emptyMap() expect
              null) { (query, parameters, expected) ->
            MOVIES_AND_PLACES_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected
          }
    }

    context("validate nullable types") {
      val schema =
          """
          graph G {
            node A(a: List<Any?>);
            node B(b: List<Any>?);
          }
          """
              .trimIndent()
              .let(::Schema)
      withData(
          "CREATE (:A {a: []})" with emptyMap() expect null,
          "CREATE (:A {a: [1, '2', 3.0]})" with emptyMap() expect null,
          "CREATE (:A {a: [1, '2', 3.0, null]})" with emptyMap() expect null,
          "CREATE (:A {a: null})" with
              emptyMap() expect
              Schema.InvalidQuery.InvalidProperty(A, A_A, listOf(null)),
          "CREATE (:A {a: \$a})" with
              mapOf("a" to null) expect
              Schema.InvalidQuery.InvalidProperty(A, A_A, listOf(null)),
          "CREATE (:B {b: []})" with emptyMap() expect null,
          "CREATE (:B {b: [1, '2', 3.0]})" with emptyMap() expect null,
          "CREATE (:B {b: [null]]})" with emptyMap() expect null,
          "CREATE (:B {b: \$b})" with mapOf("b" to listOf(null)) expect null,
          "CREATE (:B {b: null})" with
              emptyMap() expect
              Schema.InvalidQuery.InvalidProperty(B, B_B, listOf(null)),
          "CREATE (:B {b: \$b})" with
              mapOf("b" to null) expect
              Schema.InvalidQuery.InvalidProperty(B, B_B, listOf(null))) {
              (query, parameters, expected) ->
            schema.validate(query, parameters) shouldBe expected
          }
    }

    context("validate temporal types") {
      val localDate = LocalDate.now()
      val offsetTime = OffsetTime.now()
      val localTime = LocalTime.now()
      val zonedDateTime = ZonedDateTime.now()
      val localDateTime = LocalDateTime.now()
      val duration = Duration.ofSeconds(1)
      val schema =
          """
          graph G {
            node C(c: Date);
            node D(d: Time);
            node E(e: LocalTime);
            node F(f: DateTime);
            node G(g: LocalDateTime);
            node H(h: Duration);
          }
          """
              .trimIndent()
              .let(::Schema)
      withData(
          "CREATE (:C {c: \$c})" with mapOf("c" to localDate) expect null,
          "CREATE (:C {c: \$c})" with
              mapOf("c" to offsetTime) expect
              Schema.InvalidQuery.InvalidProperty(C, C_C, listOf(offsetTime)),
          "CREATE (:D {d: \$d})" with mapOf("d" to offsetTime) expect null,
          "CREATE (:D {d: \$d})" with
              mapOf("d" to localTime) expect
              Schema.InvalidQuery.InvalidProperty(D, D_D, listOf(localTime)),
          "CREATE (:E {e: \$e})" with mapOf("e" to localTime) expect null,
          "CREATE (:E {e: \$e})" with
              mapOf("e" to zonedDateTime) expect
              Schema.InvalidQuery.InvalidProperty(E, E_E, listOf(zonedDateTime)),
          "CREATE (:F {f: \$f})" with mapOf("f" to zonedDateTime) expect null,
          "CREATE (:F {f: \$f})" with
              mapOf("f" to localDateTime) expect
              Schema.InvalidQuery.InvalidProperty(F, F_F, listOf(localDateTime)),
          "CREATE (:G {g: \$g})" with mapOf("g" to localDateTime) expect null,
          "CREATE (:G {g: \$g})" with
              mapOf("g" to localDate) expect
              Schema.InvalidQuery.InvalidProperty(G, G_G, listOf(localDate)),
          "CREATE (:H {h: \$h})" with mapOf("h" to duration) expect null,
          "CREATE (:H {h: \$h})" with
              mapOf("h" to "") expect
              Schema.InvalidQuery.InvalidProperty(H, H_H, listOf(""))) {
              (query, parameters, expected) ->
            schema.validate(query, parameters) shouldBe expected
          }
    }
  }

  @IsStableType
  private data class Data(
      val query: String,
      val parameters: Map<String, Any?>,
      val expected: Schema.InvalidQuery?
  )

  private companion object {

    infix fun String.with(parameters: Map<String, Any?>) = this to parameters

    infix fun Pair<String, Map<String, Any?>>.expect(expected: Schema.InvalidQuery?) =
        Data(first, second, expected)

    val PERSON = Schema.InvalidQuery.Entity.Node("Person")
    val NAME = Schema.Property("name", Schema.Property.Type.STRING)

    val A = Schema.InvalidQuery.Entity.Node("A")
    val A_A = Schema.Property("a", Schema.Property.Type.ANY, isList = true, allowsNullable = true)
    val B = Schema.InvalidQuery.Entity.Node("B")
    val B_B = Schema.Property("b", Schema.Property.Type.ANY, isList = true, isNullable = true)
    val C = Schema.InvalidQuery.Entity.Node("C")
    val C_C = Schema.Property("c", Schema.Property.Type.DATE)
    val D = Schema.InvalidQuery.Entity.Node("D")
    val D_D = Schema.Property("d", Schema.Property.Type.TIME)
    val E = Schema.InvalidQuery.Entity.Node("E")
    val E_E = Schema.Property("e", Schema.Property.Type.LOCAL_TIME)
    val F = Schema.InvalidQuery.Entity.Node("F")
    val F_F = Schema.Property("f", Schema.Property.Type.DATE_TIME)
    val G = Schema.InvalidQuery.Entity.Node("G")
    val G_G = Schema.Property("g", Schema.Property.Type.LOCAL_DATE_TIME)
    val H = Schema.InvalidQuery.Entity.Node("H")
    val H_H = Schema.Property("h", Schema.Property.Type.DURATION)
  }
}
