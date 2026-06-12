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
@file:OptIn(io.github.cfraser.graphguard.utils.Internal::class)

package io.github.cfraser.graphguard.validate

import io.github.cfraser.graphguard.MoviesGraph
import io.github.cfraser.graphguard.knit.METADATA_SCHEMA
import io.github.cfraser.graphguard.knit.MOVIES_SCHEMA
import io.github.cfraser.graphguard.knit.PLACES_SCHEMA
import io.github.cfraser.graphguard.knit.UNION_SCHEMA
import io.github.cfraser.graphguard.validate.Schema.Graph
import io.github.cfraser.graphguard.validate.Schema.Metadata
import io.github.cfraser.graphguard.validate.Schema.Node
import io.github.cfraser.graphguard.validate.Schema.Property
import io.github.cfraser.graphguard.validate.Schema.Property.Type
import io.github.cfraser.graphguard.validate.Schema.Relationship
import io.github.cfraser.graphguard.validate.Schema.Relationship.Cardinality
import io.github.cfraser.graphguard.validate.Schema.Violation
import io.github.cfraser.graphguard.validate.SchemaTest.Companion.MOVIES_GRAPH
import io.github.cfraser.graphguard.validate.SchemaTest.Companion.PLACES_GRAPH
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.stable.IsStableType
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZonedDateTime

class SchemaTest : FunSpec() {

  init {
    test("parse movies and places schema") {
      Schema.init(MOVIES_SCHEMA + PLACES_SCHEMA).graphs shouldBe listOf(MOVIES_GRAPH, PLACES_GRAPH)
    }

    test("parse metadata schema") { Schema.init(METADATA_SCHEMA) shouldBe METADATA_GRAPH_SCHEMA }

    test("parse union schema") { Schema.init(UNION_SCHEMA) shouldBe UNION_GRAPH_SCHEMA }

    test("render movies and places schema") { "$MOVIES_GRAPH" shouldBe MOVIES_SCHEMA.trim() }

    test("render union schema") {
      "${UNION_GRAPH_SCHEMA.graphs.first()}" shouldBe UNION_SCHEMA.trim()
    }

    test("render metadata schema") { "$METADATA_GRAPH_SCHEMA" shouldBe METADATA_SCHEMA.trim() }

    context("parse and render cardinality") {
      withData(
        "[1..1]" to Cardinality(Cardinality.Range(1, 1), Cardinality.Range(1, 1)),
        "[1?..1?]" to Cardinality(Cardinality.Range(0, 1), Cardinality.Range(0, 1)),
        "[M..M]" to Cardinality(Cardinality.Range(1, null), Cardinality.Range(1, null)),
        "[M?..M?]" to Cardinality(Cardinality.Range(0, null), Cardinality.Range(0, null)),
        "[M?..1]" to Cardinality(Cardinality.Range(0, null), Cardinality.Range(1, 1)),
      ) { (dsl, expected) ->
        val schema =
          Schema.init(
            """
            graph G {
              node A: R $dsl -> B;
              node B;
            }
            """
              .trimIndent()
          )
        val rel = schema.graphs.first().nodes.first().relationships.first()
        rel.cardinality shouldBe expected
        "${schema.graphs.first()}" shouldBe
          """
          graph G {
            node A:
                R $dsl -> B;

            node B;
          }
          """
            .trimIndent()
      }
    }

    context("validate cypher queries") {
      val dateTime = LocalDateTime.now()
      withData(
        "" with emptyMap() expect null,
        "MATCH (person:Person)-[:ACTED_IN]->(tvShow:TVShow) RETURN person, tvShow" with
          emptyMap() expect
          Violation.Unknown(Violation.Entity.Node("TVShow")),
        "MATCH (TheMatrix:Movie {title:'The Matrix'}) SET TheMatrix.budget = 63000000" with
          emptyMap() expect
          Violation.UnknownProperty(Violation.Entity.Node("Movie"), "budget"),
        "MATCH (person:Person)-[:WATCHED]->(movie:Movie) RETURN person, movie" with
          emptyMap() expect
          Violation.Unknown(
            Violation.Entity.Relationship("WATCHED", setOf("Person"), setOf("Movie"))
          ),
        """
        |MATCH (:Person)-[produced:PRODUCED]->(:Movie {title:'The Matrix'})
        |SET produced.company = 'Warner Bros.'
        """
          .trimMargin() with
          emptyMap() expect
          Violation.UnknownProperty(
            Violation.Entity.Relationship("PRODUCED", setOf("Person"), setOf("Movie")),
            "company",
          ),
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
          Violation.InvalidProperty(PERSON, NAME, listOf(null, "Kevin Bacon")),
        MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
          mapOf("name" to 123) expect
          Violation.InvalidProperty(PERSON, NAME, listOf(123, "Kevin Bacon")),
        MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
          mapOf("name" to listOf("Tom Hanks")) expect
          Violation.InvalidProperty(PERSON, NAME, listOf(listOf("Tom Hanks"), "Kevin Bacon")),
        MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS with emptyMap() expect null,
        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
          mapOf("name" to "Keanu Reeves") expect
          null,
        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
          mapOf("fullName" to "Keanu Charles Reeves") expect
          null,
        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
          mapOf("name" to null) expect
          Violation.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", null)),
        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
          mapOf("name" to 123) expect
          Violation.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", 123)),
        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
          mapOf("name" to listOf("Keanu Reeves")) expect
          Violation.InvalidProperty(PERSON, NAME, listOf(listOf("Keanu Reeves"), "Tom Hanks")),
        MoviesGraph.MERGE_KEANU with emptyMap() expect null,
        MoviesGraph.MERGE_KEANU with mapOf("properties" to mapOf("born" to 1963L)) expect null,
        MoviesGraph.MERGE_KEANU with
          mapOf("properties" to mapOf("fullName" to "Keanu Charles Reeves")) expect
          Violation.UnknownProperty(PERSON, "fullName"),
        MoviesGraph.MERGE_KEANU with mapOf("properties" to mapOf("fullName" to null)) expect null,
        "MATCH (theater:Theater)-[:SHOWING]->(movie:Movie) RETURN theater, movie" with
          emptyMap() expect
          null,
        @Suppress("MaxLineLength")
        "MATCH (person:Person {name: 'Keanu Reeves'}) SET person.name = 'Keanu Charles Reeves', person.born = '09/02/1964' RETURN person" with
          emptyMap() expect
          Violation.InvalidProperty(PERSON, BORN, listOf("09/02/1964")),
        MoviesGraph.CREATE_MATRIX_SHOWING with emptyMap() expect null,
        MoviesGraph.CREATE_MATRIX_SHOWING with
          mapOf("properties" to mapOf("times" to listOf(Instant.now().toEpochMilli()))) expect
          null,
        MoviesGraph.CREATE_MATRIX_SHOWING with
          mapOf("properties" to mapOf("times" to listOf("$dateTime"))) expect
          Violation.InvalidProperty(SHOWING, TIMES, listOf(listOf("$dateTime"))),
        MoviesGraph.CREATE_MATRIX_SHOWING with
          mapOf("properties" to mapOf("times" to emptyList<Long>(), "capacity" to 100)) expect
          Violation.UnknownProperty(SHOWING, "capacity"),
      ) { (query, parameters, expected) ->
        MOVIES_AND_PLACES_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected?.violation
      }
    }

    context("removal of unknown properties") {
      withData(
        "MATCH (person:Person) REMOVE person.ssn" with emptyMap() expect null,
        $$"CREATE (person:Person) SET person += $keanu" with
          mapOf(
            "keanu" to mapOf("name" to "Keanu Reeves", "born" to 1964L, "ssn" to "123-45-6789")
          ) expect
          Violation.UnknownProperty(PERSON, "ssn"),
        $$"CREATE (person:Person) SET person += $person REMOVE person.ssn" with
          mapOf(
            "person" to mapOf("name" to "Keanu Reeves", "born" to 1964L, "ssn" to "123-45-6789")
          ) expect
          null,
        "MATCH (:Theater)-[showing:SHOWING]->(:Movie) REMOVE showing.price" with
          emptyMap() expect
          null,
        "MATCH (:Theater)-[showing:SHOWING]->(:Movie) RETURN showing.price AS price" with
          emptyMap() expect
          Violation.UnknownProperty(SHOWING, "price"),
      ) { (query, parameters, expected) ->
        MOVIES_AND_PLACES_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected?.violation
      }
    }

    context("validate union types") {
      fun invalidProperty(vararg values: Any?) =
        Violation.InvalidProperty(Violation.Entity.Node("N"), UNION_PROPERTY, values.toList())
      withData(
        "CREATE (:N {p: true})" with emptyMap() expect null,
        "CREATE (:N {p: false})" with emptyMap() expect null,
        "CREATE (:N {p: 'true'})" with emptyMap() expect null,
        "CREATE (:N {p: 'false'})" with emptyMap() expect null,
        $$"CREATE (:N {p: $p})" with mapOf("p" to true) expect null,
        $$"CREATE (:N {p: $p})" with mapOf("p" to false) expect null,
        $$"CREATE (:N {p: $p})" with mapOf("p" to "true") expect null,
        $$"CREATE (:N {p: $p})" with mapOf("p" to "false") expect null,
        "CREATE (:N {p: ''})" with emptyMap() expect invalidProperty(""),
        $$"CREATE (:N {p: $p})" with mapOf("p" to "") expect invalidProperty(""),
        $$"CREATE (:N {p: $p})" with mapOf("p" to null) expect invalidProperty(null),
        "CREATE (:N {p: 'TRUE'})" with emptyMap() expect invalidProperty("TRUE"),
        $$"CREATE (:N {p: $p})" with mapOf("p" to "FALSE") expect invalidProperty("FALSE"),
      ) { (query, parameters, expected) ->
        UNION_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected?.violation
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
          .let(Schema::init)
      withData(
        "CREATE (:A {a: []})" with emptyMap() expect null,
        "CREATE (:A {a: [1, '2', 3.0]})" with emptyMap() expect null,
        "CREATE (:A {a: [1, '2', 3.0, null]})" with emptyMap() expect null,
        "CREATE (:A {a: null})" with
          emptyMap() expect
          Violation.InvalidProperty(A, A_A, listOf(null)),
        $$"CREATE (:A {a: $a})" with
          mapOf("a" to null) expect
          Violation.InvalidProperty(A, A_A, listOf(null)),
        "CREATE (:B {b: []})" with emptyMap() expect null,
        "CREATE (:B {b: [1, '2', 3.0]})" with emptyMap() expect null,
        "CREATE (:B {b: [null]]})" with emptyMap() expect null,
        $$"CREATE (:B {b: $b})" with mapOf("b" to listOf(null)) expect null,
        "CREATE (:B {b: null})" with
          emptyMap() expect
          Violation.InvalidProperty(B, B_B, listOf(null)),
        $$"CREATE (:B {b: $b})" with
          mapOf("b" to null) expect
          Violation.InvalidProperty(B, B_B, listOf(null)),
      ) { (query, parameters, expected) ->
        schema.validate(query, parameters) shouldBe expected?.violation
      }
    }

    context("validate temporal types") {
      val localDate = LocalDate.now()
      val offsetTime = OffsetTime.now()
      val localTime = LocalTime.now()
      val zonedDateTime = ZonedDateTime.now()
      val localDateTime = LocalDateTime.now()
      val duration = Duration.ZERO
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
          .let(Schema::init)
      withData(
        $$"CREATE (:C {c: $c})" with mapOf("c" to localDate) expect null,
        "CREATE (:C {c: time()})" with
          emptyMap() expect
          Violation.InvalidProperty(C, C_C, listOf("time()")),
        $$"CREATE (:C {c: $c})" with
          mapOf("c" to offsetTime) expect
          Violation.InvalidProperty(C, C_C, listOf(offsetTime)),
        $$"CREATE (:D {d: $d})" with mapOf("d" to offsetTime) expect null,
        "CREATE (:D {d: localtime()})" with
          emptyMap() expect
          Violation.InvalidProperty(D, D_D, listOf("localtime()")),
        $$"CREATE (:D {d: $d})" with
          mapOf("d" to localTime) expect
          Violation.InvalidProperty(D, D_D, listOf(localTime)),
        $$"CREATE (:E {e: $e})" with mapOf("e" to localTime) expect null,
        "CREATE (:E {e: datetime()})" with
          emptyMap() expect
          Violation.InvalidProperty(E, E_E, listOf("datetime()")),
        $$"CREATE (:E {e: $e})" with
          mapOf("e" to zonedDateTime) expect
          Violation.InvalidProperty(E, E_E, listOf(zonedDateTime)),
        $$"CREATE (:F {f: $f})" with mapOf("f" to zonedDateTime) expect null,
        "CREATE (:F {f: localdatetime()})" with
          emptyMap() expect
          Violation.InvalidProperty(F, F_F, listOf("localdatetime()")),
        $$"CREATE (:F {f: $f})" with
          mapOf("f" to localDateTime) expect
          Violation.InvalidProperty(F, F_F, listOf(localDateTime)),
        $$"CREATE (:G {g: $g})" with mapOf("g" to localDateTime) expect null,
        "CREATE (:G {g: date()})" with
          emptyMap() expect
          Violation.InvalidProperty(G, G_G, listOf("date()")),
        $$"CREATE (:G {g: $g})" with
          mapOf("g" to localDate) expect
          Violation.InvalidProperty(G, G_G, listOf(localDate)),
        $$"CREATE (:H {h: $h})" with mapOf("h" to duration) expect null,
        "CREATE (:H {h: date()})" with
          emptyMap() expect
          Violation.InvalidProperty(H, H_H, listOf("date()")),
        $$"CREATE (:H {h: $h})" with
          mapOf("h" to "") expect
          Violation.InvalidProperty(H, H_H, listOf("")),
        *listOf(
            "C" to "date",
            "D" to "time",
            "E" to "localtime",
            "F" to "datetime",
            "G" to "localdatetime",
            "H" to "duration",
          )
          .flatMap { (id, fn) ->
            QueryTest.TIMES.filter { time -> time.startsWith("$fn(") }
              .map { time ->
                "CREATE (:$id {${id.lowercase()}: $time})" with emptyMap() expect null
              }
          }
          .toTypedArray(),
      ) { (query, parameters, expected) ->
        schema.validate(query, parameters) shouldBe expected?.violation
      }
    }

    context("validate scalar, mathematical, and string functions") {
      val schema =
        """
        graph G {
          node I(i: Boolean);
          node J(j: Integer);
          node K(k: Float);
          node L(l: String);
          node M(m: List<String>);
        }
        """
          .trimIndent()
          .let(Schema::init)
      withData(
        """
          CREATE (:I {i: toBoolean('true')})
          CREATE (:I {i: isNaN(0/0.0)})
          CREATE (:J {j: char_length('')})
          CREATE (:J {j: character_length('')})
          MATCH (n) CREATE (:J {j: id(n)})
          MATCH p = (a)-->(b)-->(c) CREATE (:J {j: length(p)})
          CREATE (:J {j: size([])})
          CREATE (:J {j: timestamp()})
          CREATE (:J {j: toInteger('0')})
          CREATE (:J {j: abs(5-7)})
          CREATE (:J {j: sign(7)})
          CREATE (:K {k: toFloat('0.0')})
          CREATE (:K {k: ceil(0.1)})
          CREATE (:K {k: floor(0.9)})
          CREATE (:K {k: rand()})
          CREATE (:K {k: round(3.141592)})
          CREATE (:K {k: e()})
          CREATE (:K {k: exp(2)})
          CREATE (:K {k: log(27)})
          CREATE (:K {k: log10(27)})
          CREATE (:K {k: sqrt(256)})
          CREATE (:K {k: acos(0.5)})
          CREATE (:K {k: asin(0.5)})
          CREATE (:K {k: atan(0.5)})
          CREATE (:K {k: atan2(0.5)})
          CREATE (:K {k: cos(0.5)})
          CREATE (:K {k: cot(0.5)})
          CREATE (:K {k: degrees(3.14159)})
          CREATE (:K {k: haversin(0.5)})
          CREATE (:K {k: pi()})
          CREATE (:K {k: radians(180)})
          CREATE (:K {k: sin(0.5)})
          CREATE (:K {k: tan(0.5)})
          MATCH (n) CREATE (:L {l: elementId(n)})
          CREATE (:L {l: randomUUID()})
          MATCH (n)-[r]->() CREATE (:L {l: type(r)})
          CREATE (:L {l: valueType(0)})
          CREATE (:L {l: left('')})
          CREATE (:L {l: ltrim('')})
          CREATE (:L {l: replace('abc', 'b', 'c')})
          CREATE (:L {l: reverse('')})
          CREATE (:L {l: right('')})
          CREATE (:L {l: rtrim('')})
          CREATE (:L {l: substring('abc', 1, 2)})
          CREATE (:L {l: toLower('')})
          CREATE (:L {l: toString(0)})
          CREATE (:L {l: toUpper('')})
          CREATE (:L {l: trim('')})
          CREATE (:M {m: split('a,b,c', ',')})
          """
          .lines()
          .map(String::trim)
          .filterNot(String::isBlank)
      ) { query ->
        schema.validate(query, emptyMap()) shouldBe null
      }
    }

    context("validate any types") {
      val schema =
        """
        graph G {
          node I(i: Any);
          node J(j: Any?);
        }
        """
          .trimIndent()
          .let(Schema::init)
      withData(
        "CREATE (:I {i: ''})" with emptyMap() expect null,
        "CREATE (:I {i: 1})" with emptyMap() expect null,
        "CREATE (:I {i: []})" with emptyMap() expect null,
        "CREATE (:I {i: ['']})" with emptyMap() expect null,
        "CREATE (:I {i: null})" with
          emptyMap() expect
          Violation.InvalidProperty(I, I_I, listOf(null)),
        "CREATE (:J {j: ''})" with emptyMap() expect null,
        "CREATE (:J {j: 1})" with emptyMap() expect null,
        "CREATE (:J {j: []})" with emptyMap() expect null,
        "CREATE (:J {j: ['']})" with emptyMap() expect null,
        "CREATE (:J {j: null})" with emptyMap() expect null,
      ) { (query, parameters, expected) ->
        schema.validate(query, parameters) shouldBe expected?.violation
      }
    }

    test("default to nullable any type") {
      val schema =
        """
        graph G {
          node A(b, c, d: Integer): E(@f g, h: List<Any>, i: String?) -> J;
          node J;
        }
        """
          .trimIndent()
          .let(Schema::init)
      schema shouldBe
        buildSchema(
          Graph(
            name = "G",
            nodes =
              listOf(
                Node(
                  name = "A",
                  properties =
                    listOf(
                      Property(name = "b", type = Type.Nullable.Any, metadata = emptyList()),
                      Property(name = "c", type = Type.Nullable.Any, metadata = emptyList()),
                      Property(name = "d", type = Type.Integer, metadata = emptyList()),
                    ),
                  relationships =
                    listOf(
                      Relationship(
                        name = "E",
                        source = "A",
                        target = "J",
                        isDirected = true,
                        cardinality = null,
                        properties =
                          listOf(
                            Property(
                              name = "g",
                              type = Type.Nullable.Any,
                              metadata = listOf(Metadata("f", null)),
                            ),
                            Property(
                              name = "h",
                              type = Type.List(Type.Any),
                              metadata = emptyList(),
                            ),
                            Property(
                              name = "i",
                              type = Type.Nullable.String,
                              metadata = emptyList(),
                            ),
                          ),
                        metadata = emptyList(),
                      )
                    ),
                  metadata = emptyList(),
                ),
                Node(
                  name = "J",
                  properties = emptyList(),
                  relationships = emptyList(),
                  metadata = emptyList(),
                ),
              ),
          )
        )
      "$schema" shouldBe
        """
        |graph G {
        |  node A(b: Any?, c: Any?, d: Integer):
        |      E(@f g: Any?, h: List<Any>, i: String?) -> J;
        |
        |  node J;
        |}
        """
          .trimMargin()
    }

    test("target node and relationship intersection") {
      val schema =
        Schema.init(
          """
          |graph G {
          |  node A: AB -> B, AC -> C;
          |  node B;
          |  node C;
          |}
          """
            .trimMargin()
        )
      schema.validate("MATCH (a:A)-[r:AB|AC]->(n:B|C) RETURN a, r, n", emptyMap()) shouldBe null
    }
  }

  @IsStableType
  private data class Data(
    val query: String,
    val parameters: Map<String, Any?>,
    val expected: Violation?,
  )

  private companion object {

    /** The [Schema.Graph] for the [MOVIES_SCHEMA]. */
    val MOVIES_GRAPH =
      Graph(
        name = "Movies",
        nodes =
          listOf(
            Node(
              name = "Person",
              properties =
                listOf(
                  Property("name", Type.String, emptyList()),
                  Property("born", Type.Integer, emptyList()),
                ),
              relationships =
                listOf(
                  Relationship(
                    name = "ACTED_IN",
                    source = "Person",
                    target = "Movie",
                    isDirected = true,
                    cardinality = null,
                    properties = listOf(Property("roles", Type.List(Type.String), emptyList())),
                    metadata = emptyList(),
                  ),
                  Relationship(
                    name = "DIRECTED",
                    source = "Person",
                    target = "Movie",
                    isDirected = true,
                    cardinality = null,
                    properties = emptyList(),
                    metadata = emptyList(),
                  ),
                  Relationship(
                    name = "PRODUCED",
                    source = "Person",
                    target = "Movie",
                    isDirected = true,
                    cardinality = null,
                    properties = emptyList(),
                    metadata = emptyList(),
                  ),
                  Relationship(
                    name = "WROTE",
                    source = "Person",
                    target = "Movie",
                    isDirected = true,
                    cardinality = null,
                    properties = emptyList(),
                    metadata = emptyList(),
                  ),
                  Relationship(
                    name = "REVIEWED",
                    source = "Person",
                    target = "Movie",
                    isDirected = true,
                    cardinality = null,
                    properties =
                      listOf(
                        Property("summary", Type.String, emptyList()),
                        Property("rating", Type.Integer, emptyList()),
                      ),
                    metadata = emptyList(),
                  ),
                ),
              metadata = emptyList(),
            ),
            Node(
              name = "Movie",
              properties =
                listOf(
                  Property("title", Type.String, emptyList()),
                  Property("released", Type.Integer, emptyList()),
                  Property("tagline", Type.String, emptyList()),
                ),
              relationships = emptyList(),
              metadata = emptyList(),
            ),
          ),
      )

    /** The [Schema.Graph] for the [PLACES_SCHEMA]. */
    val PLACES_GRAPH =
      Graph(
        name = "Places",
        nodes =
          listOf(
            Node(
              name = "Theater",
              properties = listOf(Property("name", Type.String, emptyList())),
              relationships =
                listOf(
                  Relationship(
                    name = "SHOWING",
                    source = "Theater",
                    target = "Movies.Movie",
                    isDirected = true,
                    cardinality = null,
                    properties = listOf(Property("times", Type.List(Type.Integer), emptyList())),
                    metadata = emptyList(),
                  )
                ),
              metadata = emptyList(),
            )
          ),
      )

    /** The [Schema] for the [MOVIES_GRAPH] and [PLACES_GRAPH]. */
    val MOVIES_AND_PLACES_GRAPH_SCHEMA = Schema.init(MOVIES_SCHEMA + PLACES_SCHEMA)

    /** The [Schema] for the [METADATA_SCHEMA]. */
    val METADATA_GRAPH_SCHEMA =
      buildSchema(
        Graph(
          name = "G",
          nodes =
            listOf(
              Node(
                name = "N",
                properties =
                  listOf(
                    Property(
                      name = "p",
                      type = Type.Any,
                      metadata = listOf(Metadata(name = "b", value = "c")),
                    )
                  ),
                relationships =
                  listOf(
                    Relationship(
                      name = "R",
                      source = "N",
                      target = "N",
                      isDirected = false,
                      cardinality = null,
                      properties =
                        listOf(
                          Property(
                            name = "p",
                            type = Type.Any,
                            metadata =
                              listOf(
                                Metadata(name = "e", value = "f"),
                                Metadata(name = "g", value = null),
                              ),
                          )
                        ),
                      metadata = listOf(Metadata(name = "d", value = null)),
                    )
                  ),
                metadata = listOf(Metadata(name = "a", value = null)),
              )
            ),
        )
      )

    /** A [Schema.Property] with a [Schema.Property.Type.Union] type. */
    val UNION_PROPERTY =
      Property(
        name = "p",
        type =
          Type.Union(listOf(Type.Boolean, Type.LiteralString("true"), Type.LiteralString("false"))),
        metadata = emptyList(),
      )

    /** The [Schema] for the [UNION_SCHEMA]. */
    val UNION_GRAPH_SCHEMA =
      buildSchema(
        Graph(
          name = "G",
          nodes =
            listOf(
              Node(
                name = "N",
                properties = listOf(UNION_PROPERTY),
                relationships = emptyList(),
                metadata = emptyList(),
              )
            ),
        )
      )

    infix fun String.with(parameters: Map<String, Any?>) = this to parameters

    infix fun Pair<String, Map<String, Any?>>.expect(expected: Violation?) =
      Data(first, second, expected)

    val PERSON = Violation.Entity.Node("Person")
    val NAME = Property("name", Type.String, emptyList())
    val BORN = Property("born", Type.Integer, emptyList())
    val SHOWING = Violation.Entity.Relationship("SHOWING", setOf("Theater"), setOf("Movie"))
    val TIMES = Property("times", Type.List(Type.Integer), emptyList())

    val A = Violation.Entity.Node("A")
    val A_A = Property("a", Type.List(Type.Nullable.Any), emptyList())
    val B = Violation.Entity.Node("B")
    val B_B = Property("b", Type.Nullable.List(Type.Any), emptyList())
    val C = Violation.Entity.Node("C")
    val C_C = Property("c", Type.Date, emptyList())
    val D = Violation.Entity.Node("D")
    val D_D = Property("d", Type.Time, emptyList())
    val E = Violation.Entity.Node("E")
    val E_E = Property("e", Type.LocalTime, emptyList())
    val F = Violation.Entity.Node("F")
    val F_F = Property("f", Type.DateTime, emptyList())
    val G = Violation.Entity.Node("G")
    val G_G = Property("g", Type.LocalDateTime, emptyList())
    val H = Violation.Entity.Node("H")
    val H_H = Property("h", Type.Duration, emptyList())
    val I = Violation.Entity.Node("I")
    val I_I = Property("i", Type.Any, emptyList())

    private fun buildSchema(vararg graphs: Graph): Schema {
      val allNodes = graphs.flatMap(Graph::nodes)
      val nodes = allNodes.associateBy(Node::name)
      val relationships = buildMap {
        allNodes.flatMap(Node::relationships).forEach { rel ->
          this[Relationship.Id(rel.name, rel.source, rel.target)] = rel
        }
      }
      return Schema(graphs.toList(), nodes, relationships)
    }
  }
}
