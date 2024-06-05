/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF Any KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.MoviesGraph
import io.github.cfraser.graphguard.knit.METADATA_SCHEMA
import io.github.cfraser.graphguard.knit.MOVIES_SCHEMA
import io.github.cfraser.graphguard.knit.PLACES_SCHEMA
import io.github.cfraser.graphguard.knit.UNION_SCHEMA
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
    test("parse movies and places schema") {
      Schema(MOVIES_SCHEMA + PLACES_SCHEMA) shouldBe MOVIES_AND_PLACES_GRAPH_SCHEMA
    }

    test("parse metadata schema") { Schema(METADATA_SCHEMA) shouldBe METADATA_GRAPH_SCHEMA }

    test("parse union schema") { Schema(UNION_SCHEMA) shouldBe UNION_GRAPH_SCHEMA }

    test("render movies and places schema") { "$MOVIES_GRAPH" shouldBe MOVIES_SCHEMA.trim() }

    test("render union schema") {
      "${UNION_GRAPH_SCHEMA.graphs.first()}" shouldBe UNION_SCHEMA.trim()
    }

    context("validate cypher queries") {
      withData(
          "" with emptyMap() expect null,
          "MATCH (person:Person)-[:ACTED_IN]->(tvShow:TVShow) RETURN person, tvShow" with
              emptyMap() expect
              Schema.Violation.Unknown(Schema.Violation.Entity.Node("TVShow")),
          "MATCH (TheMatrix:Movie {title:'The Matrix'}) SET TheMatrix.budget = 63000000" with
              emptyMap() expect
              Schema.Violation.UnknownProperty(Schema.Violation.Entity.Node("Movie"), "budget"),
          "MATCH (person:Person)-[:WATCHED]->(movie:Movie) RETURN person, movie" with
              emptyMap() expect
              Schema.Violation.Unknown(
                  Schema.Violation.Entity.Relationship("WATCHED", "Person", "Movie")),
          """MATCH (:Person)-[produced:PRODUCED]->(:Movie {title:'The Matrix'})
              |SET produced.company = 'Warner Bros.'"""
              .trimMargin() with
              emptyMap() expect
              Schema.Violation.UnknownProperty(
                  Schema.Violation.Entity.Relationship("PRODUCED", "Person", "Movie"), "company"),
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
              Schema.Violation.InvalidProperty(PERSON, NAME, listOf(null, "Kevin Bacon")),
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("name" to 123) expect
              Schema.Violation.InvalidProperty(PERSON, NAME, listOf(123, "Kevin Bacon")),
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO with
              mapOf("name" to listOf("Tom Hanks")) expect
              Schema.Violation.InvalidProperty(
                  PERSON, NAME, listOf(listOf("Tom Hanks"), "Kevin Bacon")),
          MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS with emptyMap() expect null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to "Keanu Reeves") expect
              null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("fullName" to "Keanu Charles Reeves") expect
              null,
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to null) expect
              Schema.Violation.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", null)),
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to 123) expect
              Schema.Violation.InvalidProperty(PERSON, NAME, listOf("Tom Hanks", 123)),
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND with
              mapOf("name" to listOf("Keanu Reeves")) expect
              Schema.Violation.InvalidProperty(
                  PERSON, NAME, listOf(listOf("Keanu Reeves"), "Tom Hanks")),
          MoviesGraph.MERGE_KEANU with emptyMap() expect null,
          MoviesGraph.MERGE_KEANU with mapOf("properties" to mapOf("born" to 1963L)) expect null,
          MoviesGraph.MERGE_KEANU with
              mapOf("properties" to mapOf("fullName" to "Keanu Charles Reeves")) expect
              Schema.Violation.UnknownProperty(PERSON, "fullName"),
          "MATCH (theater:Theater)-[:SHOWING]->(movie:Movie) RETURN theater, movie" with
              emptyMap() expect
              null,
          "MATCH (person:Person {name: 'Keanu Reeves'}) SET person.name = 'Keanu Charles Reeves', person.born = '09/02/1964' RETURN person" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(PERSON, BORN, listOf("09/02/1964"))) {
              (query, parameters, expected) ->
            MOVIES_AND_PLACES_GRAPH_SCHEMA.validate(query, parameters) shouldBe expected?.violation
          }
    }

    context("validate cypher queries with union schema") {
      fun invalidProperty(vararg values: Any?) =
          Schema.Violation.InvalidProperty(
              Schema.Violation.Entity.Node("N"), UNION_PROPERTY, values.toList())
      withData(
          "CREATE (:N {p: true})" with emptyMap() expect null,
          "CREATE (:N {p: false})" with emptyMap() expect null,
          "CREATE (:N {p: 'true'})" with emptyMap() expect null,
          "CREATE (:N {p: 'false'})" with emptyMap() expect null,
          "CREATE (:N {p: \$p})" with mapOf("p" to true) expect null,
          "CREATE (:N {p: \$p})" with mapOf("p" to false) expect null,
          "CREATE (:N {p: \$p})" with mapOf("p" to "true") expect null,
          "CREATE (:N {p: \$p})" with mapOf("p" to "false") expect null,
          "CREATE (:N {p: ''})" with emptyMap() expect invalidProperty(""),
          "CREATE (:N {p: \$p})" with mapOf("p" to "") expect invalidProperty(""),
          "CREATE (:N {p: \$p})" with mapOf("p" to null) expect invalidProperty(null),
          "CREATE (:N {p: 'TRUE'})" with emptyMap() expect invalidProperty("TRUE"),
          "CREATE (:N {p: \$p})" with mapOf("p" to "FALSE") expect invalidProperty("FALSE"),
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
              .let(::Schema)
      withData(
          "CREATE (:A {a: []})" with emptyMap() expect null,
          "CREATE (:A {a: [1, '2', 3.0]})" with emptyMap() expect null,
          "CREATE (:A {a: [1, '2', 3.0, null]})" with emptyMap() expect null,
          "CREATE (:A {a: null})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(A, A_A, listOf(null)),
          "CREATE (:A {a: \$a})" with
              mapOf("a" to null) expect
              Schema.Violation.InvalidProperty(A, A_A, listOf(null)),
          "CREATE (:B {b: []})" with emptyMap() expect null,
          "CREATE (:B {b: [1, '2', 3.0]})" with emptyMap() expect null,
          "CREATE (:B {b: [null]]})" with emptyMap() expect null,
          "CREATE (:B {b: \$b})" with mapOf("b" to listOf(null)) expect null,
          "CREATE (:B {b: null})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(B, B_B, listOf(null)),
          "CREATE (:B {b: \$b})" with
              mapOf("b" to null) expect
              Schema.Violation.InvalidProperty(B, B_B, listOf(null))) {
              (query, parameters, expected) ->
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
              .let(::Schema)
      withData(
          "CREATE (:C {c: \$c})" with mapOf("c" to localDate) expect null,
          "CREATE (:C {c: time()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(C, C_C, listOf("time()")),
          "CREATE (:C {c: \$c})" with
              mapOf("c" to offsetTime) expect
              Schema.Violation.InvalidProperty(C, C_C, listOf(offsetTime)),
          "CREATE (:D {d: \$d})" with mapOf("d" to offsetTime) expect null,
          "CREATE (:D {d: localtime()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(D, D_D, listOf("localtime()")),
          "CREATE (:D {d: \$d})" with
              mapOf("d" to localTime) expect
              Schema.Violation.InvalidProperty(D, D_D, listOf(localTime)),
          "CREATE (:E {e: \$e})" with mapOf("e" to localTime) expect null,
          "CREATE (:E {e: datetime()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(E, E_E, listOf("datetime()")),
          "CREATE (:E {e: \$e})" with
              mapOf("e" to zonedDateTime) expect
              Schema.Violation.InvalidProperty(E, E_E, listOf(zonedDateTime)),
          "CREATE (:F {f: \$f})" with mapOf("f" to zonedDateTime) expect null,
          "CREATE (:F {f: localdatetime()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(F, F_F, listOf("localdatetime()")),
          "CREATE (:F {f: \$f})" with
              mapOf("f" to localDateTime) expect
              Schema.Violation.InvalidProperty(F, F_F, listOf(localDateTime)),
          "CREATE (:G {g: \$g})" with mapOf("g" to localDateTime) expect null,
          "CREATE (:G {g: date()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(G, G_G, listOf("date()")),
          "CREATE (:G {g: \$g})" with
              mapOf("g" to localDate) expect
              Schema.Violation.InvalidProperty(G, G_G, listOf(localDate)),
          "CREATE (:H {h: \$h})" with mapOf("h" to duration) expect null,
          "CREATE (:H {h: date()})" with
              emptyMap() expect
              Schema.Violation.InvalidProperty(H, H_H, listOf("date()")),
          "CREATE (:H {h: \$h})" with
              mapOf("h" to "") expect
              Schema.Violation.InvalidProperty(H, H_H, listOf("")),
          *listOf(
                  "C" to "date",
                  "D" to "time",
                  "E" to "localtime",
                  "F" to "datetime",
                  "G" to "localdatetime",
                  "H" to "duration")
              .flatMap { (id, fn) ->
                QueryTest.TIMES.filter { time -> time.startsWith("$fn(") }
                    .map { time ->
                      "CREATE (:$id {${id.lowercase()}: $time})" with emptyMap() expect null
                    }
              }
              .toTypedArray()) { (query, parameters, expected) ->
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
              .let(::Schema)
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
              .filterNot(String::isBlank)) { query ->
            schema.validate(query, emptyMap()) shouldBe null
          }
    }
  }

  @IsStableType
  private data class Data(
      val query: String,
      val parameters: Map<String, Any?>,
      val expected: Schema.Violation?
  )

  private companion object {

    /** The [Schema.Graph] for the [MOVIES_SCHEMA]. */
    val MOVIES_GRAPH =
        Schema.Graph(
            name = "Movies",
            nodes =
                setOf(
                    Schema.Node(
                        name = "Person",
                        properties =
                            setOf(
                                Schema.Property("name", Schema.Property.Type.String, emptySet()),
                                Schema.Property("born", Schema.Property.Type.Integer, emptySet())),
                        relationships =
                            setOf(
                                Schema.Relationship(
                                    name = "ACTED_IN",
                                    source = "Person",
                                    target = "Movie",
                                    isDirected = true,
                                    properties =
                                        setOf(
                                            Schema.Property(
                                                "roles",
                                                Schema.Property.Type.String,
                                                emptySet(),
                                                isList = true)),
                                    metadata = emptySet(),
                                ),
                                Schema.Relationship(
                                    name = "DIRECTED",
                                    source = "Person",
                                    target = "Movie",
                                    isDirected = true,
                                    properties = emptySet(),
                                    metadata = emptySet(),
                                ),
                                Schema.Relationship(
                                    name = "PRODUCED",
                                    source = "Person",
                                    target = "Movie",
                                    isDirected = true,
                                    properties = emptySet(),
                                    metadata = emptySet(),
                                ),
                                Schema.Relationship(
                                    name = "WROTE",
                                    source = "Person",
                                    target = "Movie",
                                    isDirected = true,
                                    properties = emptySet(),
                                    metadata = emptySet(),
                                ),
                                Schema.Relationship(
                                    name = "REVIEWED",
                                    source = "Person",
                                    target = "Movie",
                                    isDirected = true,
                                    properties =
                                        setOf(
                                            Schema.Property(
                                                "summary", Schema.Property.Type.String, emptySet()),
                                            Schema.Property(
                                                "rating",
                                                Schema.Property.Type.Integer,
                                                emptySet())),
                                    metadata = emptySet(),
                                )),
                        metadata = emptySet()),
                    Schema.Node(
                        name = "Movie",
                        properties =
                            setOf(
                                Schema.Property("title", Schema.Property.Type.String, emptySet()),
                                Schema.Property(
                                    "released", Schema.Property.Type.Integer, emptySet()),
                                Schema.Property(
                                    "tagline", Schema.Property.Type.String, emptySet())),
                        relationships = emptySet(),
                        metadata = emptySet()),
                ))

    /** The [Schema.Graph] for the [PLACES_SCHEMA]. */
    val PLACES_GRAPH =
        Schema.Graph(
            name = "Places",
            nodes =
                setOf(
                    Schema.Node(
                        name = "Theater",
                        properties =
                            setOf(Schema.Property("name", Schema.Property.Type.String, emptySet())),
                        relationships =
                            setOf(
                                Schema.Relationship(
                                    name = "SHOWING",
                                    source = "Theater",
                                    target = "Movies.Movie",
                                    isDirected = true,
                                    properties =
                                        setOf(
                                            Schema.Property(
                                                "times",
                                                Schema.Property.Type.Integer,
                                                emptySet(),
                                                isList = true)),
                                    metadata = emptySet(),
                                )),
                        metadata = emptySet())))

    /** The [Schema] for the [MOVIES_GRAPH] and [PLACES_GRAPH]. */
    val MOVIES_AND_PLACES_GRAPH_SCHEMA = Schema(setOf(MOVIES_GRAPH, PLACES_GRAPH))

    /** The [Schema] for the [METADATA_SCHEMA]. */
    val METADATA_GRAPH_SCHEMA =
        Schema(
            graphs =
                setOf(
                    Schema.Graph(
                        name = "G",
                        nodes =
                            setOf(
                                Schema.Node(
                                    name = "N",
                                    properties =
                                        setOf(
                                            Schema.Property(
                                                name = "p",
                                                type = Schema.Property.Type.Any,
                                                metadata =
                                                    setOf(
                                                        Schema.Metadata(name = "b", value = "c")))),
                                    relationships =
                                        setOf(
                                            Schema.Relationship(
                                                name = "R",
                                                source = "N",
                                                target = "N",
                                                isDirected = false,
                                                properties =
                                                    setOf(
                                                        Schema.Property(
                                                            name = "p",
                                                            type = Schema.Property.Type.Any,
                                                            metadata =
                                                                setOf(
                                                                    Schema.Metadata(
                                                                        name = "e", value = "f"),
                                                                    Schema.Metadata(
                                                                        name = "g",
                                                                        value = null)))),
                                                metadata =
                                                    setOf(
                                                        Schema.Metadata(
                                                            name = "d", value = null)))),
                                    metadata = setOf(Schema.Metadata(name = "a", value = null)))))))

    /** A [Schema.Property] with a [Schema.Property.Type.Union] type. */
    val UNION_PROPERTY =
        Schema.Property(
            name = "p",
            type =
                Schema.Property.Type.Union(
                    listOf(
                        Schema.Property.Type.Boolean,
                        Schema.Property.Type.LiteralString("true"),
                        Schema.Property.Type.LiteralString("false"))),
            metadata = emptySet())

    /** The [Schema] for the [UNION_SCHEMA]. */
    val UNION_GRAPH_SCHEMA =
        Schema(
            graphs =
                setOf(
                    Schema.Graph(
                        name = "G",
                        nodes =
                            setOf(
                                Schema.Node(
                                    name = "N",
                                    properties = setOf(UNION_PROPERTY),
                                    relationships = emptySet(),
                                    metadata = emptySet())))))

    infix fun String.with(parameters: Map<String, Any?>) = this to parameters

    infix fun Pair<String, Map<String, Any?>>.expect(expected: Schema.Violation?) =
        Data(first, second, expected)

    val PERSON = Schema.Violation.Entity.Node("Person")
    val NAME = Schema.Property("name", Schema.Property.Type.String, emptySet())
    val BORN = Schema.Property("born", Schema.Property.Type.Integer, emptySet())

    val A = Schema.Violation.Entity.Node("A")
    val A_A =
        Schema.Property(
            "a", Schema.Property.Type.Any, emptySet(), isList = true, allowsNullable = true)
    val B = Schema.Violation.Entity.Node("B")
    val B_B =
        Schema.Property("b", Schema.Property.Type.Any, emptySet(), isList = true, isNullable = true)
    val C = Schema.Violation.Entity.Node("C")
    val C_C = Schema.Property("c", Schema.Property.Type.Date, emptySet())
    val D = Schema.Violation.Entity.Node("D")
    val D_D = Schema.Property("d", Schema.Property.Type.Time, emptySet())
    val E = Schema.Violation.Entity.Node("E")
    val E_E = Schema.Property("e", Schema.Property.Type.LocalTime, emptySet())
    val F = Schema.Violation.Entity.Node("F")
    val F_F = Schema.Property("f", Schema.Property.Type.DateTime, emptySet())
    val G = Schema.Violation.Entity.Node("G")
    val G_G = Schema.Property("g", Schema.Property.Type.LocalDateTime, emptySet())
    val H = Schema.Violation.Entity.Node("H")
    val H_H = Schema.Property("h", Schema.Property.Type.Duration, emptySet())
  }
}
