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

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.IsStableType
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import kotlin.reflect.KClass

class QueryTest : FunSpec() {

  init {
    context("parse cypher queries") {
      @IsStableType
      data class Data(val cypher: String, val expected: Query, val expectTypes: Boolean = false)
      fun String.expectTypes(expected: Query) = Data(this, expected, expectTypes = true)
      infix fun String.expect(expected: Query) = Data(this, expected)
      withData(
          MoviesGraph.CREATE.last()
              .expectTypes(
                  Query(
                      setOf(MOVIE, PERSON),
                      setOf(ACTED_IN, DIRECTED, PRODUCED, WROTE, REVIEWED),
                      setOf(
                          PERSON_NAME with String::class,
                          PERSON_BORN with Long::class,
                          MOVIE_TITLE with String::class,
                          MOVIE_RELEASED with Long::class,
                          MOVIE_TAGLINE with String::class,
                          ACTED_IN_ROLES with listOf(String::class),
                          REVIEWED_SUMMARY with String::class,
                          REVIEWED_RATING with Long::class),
                      emptySet())),
          MoviesGraph.MATCH_TOM_HANKS expect
              Query(setOf(PERSON), emptySet(), setOf(PERSON_NAME with "Tom Hanks"), emptySet()),
          MoviesGraph.MATCH_CLOUD_ATLAS expect
              Query(setOf(MOVIE), emptySet(), setOf(MOVIE_TITLE with "Cloud Atlas"), emptySet()),
          MoviesGraph.MATCH_10_PEOPLE expect
              Query(setOf(PERSON), emptySet(), setOf(PERSON_NAME), emptySet()),
          MoviesGraph.MATCH_NINETIES_MOVIES expect
              Query(
                  setOf(MOVIE),
                  emptySet(),
                  setOf(MOVIE_TITLE, MOVIE_RELEASED with setOf(1990L, 2000L)),
                  emptySet()),
          MoviesGraph.MATCH_TOM_HANKS_MOVIES expect
              Query(
                  setOf(PERSON, MOVIE),
                  setOf(ACTED_IN),
                  setOf(PERSON_NAME with "Tom Hanks"),
                  emptySet()),
          MoviesGraph.MATCH_CLOUD_ATLAS_DIRECTOR expect
              Query(
                  setOf(MOVIE, PERSON),
                  setOf(DIRECTED),
                  setOf(MOVIE_TITLE with "Cloud Atlas", PERSON_NAME),
                  emptySet()),
          MoviesGraph.MATCH_TOM_HANKS_CO_ACTORS expect
              Query(
                  setOf(PERSON, MOVIE),
                  setOf(ACTED_IN),
                  setOf(PERSON_NAME with "Tom Hanks"),
                  emptySet()),
          MoviesGraph.MATCH_CLOUD_ATLAS_PEOPLE expect
              Query(
                  setOf(PERSON, MOVIE),
                  emptySet(),
                  setOf(PERSON_NAME, MOVIE_TITLE with "Cloud Atlas", ROLES),
                  emptySet()),
          MoviesGraph.MATCH_SIX_DEGREES_OF_KEVIN_BACON expect
              Query(setOf(PERSON), emptySet(), setOf(PERSON_NAME with "Kevin Bacon"), emptySet()),
          MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO expect
              Query(
                  setOf(PERSON),
                  emptySet(),
                  setOf(PERSON_NAME with setOf("Kevin Bacon", "name")),
                  emptySet()),
          MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS expect
              Query(
                  setOf(PERSON),
                  setOf(PERSON_ACTED_IN),
                  setOf(PERSON_NAME with "Tom Hanks", NAME),
                  emptySet()),
          MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND expect
              Query(
                  setOf(PERSON),
                  setOf(PERSON_ACTED_IN),
                  setOf(PERSON_NAME with setOf("Tom Hanks", "name")),
                  emptySet()),
          MoviesGraph.MERGE_KEANU expect
              Query(
                  setOf(MOVIE, PERSON),
                  setOf(ACTED_IN),
                  setOf(
                      PERSON_NAME with "Keanu Reeves",
                      ACTED_IN_ROLES with listOf("Neo"),
                      MOVIE_TITLE with "The Matrix",
                      MOVIE_RELEASED with 1999L,
                      MOVIE_TAGLINE with "Welcome to the Real World"),
                  setOf(Query.MutatedProperty("Person", "properties")))) {
              (cypher, query, expectTypes) ->
            val parsed = (Query.parse(cypher) shouldNotBe null)!!
            parsed.nodes shouldContainExactlyInAnyOrder query.nodes
            parsed.relationships shouldContainExactlyInAnyOrder query.relationships
            val properties = if (expectTypes) parsed.properties.types() else parsed.properties
            properties shouldHaveSize query.properties.size
            properties.forEach { property ->
              val queryProperty =
                  (query.properties.find {
                    property.name == it.name && property.owner == it.owner
                  } shouldNotBe null)!!
              property.values.values() shouldContainExactlyInAnyOrder queryProperty.values.values()
            }
            parsed.mutatedProperties shouldContainExactlyInAnyOrder query.mutatedProperties
          }
    }

    context("resolvable function invocation") {
      withData(TIMES) { value: String ->
        val parsed = (Query.parse("CREATE (:N {n: $value})") shouldNotBe null)!!
        val values =
            parsed.properties.flatMap { property ->
              property.values.mapNotNull { value ->
                if (value is Query.Property.Type.Resolvable) value.name else null
              }
            }
        values shouldContainExactly setOf(value)
      }
    }
  }

  private companion object {

    const val PERSON = "Person"
    const val MOVIE = "Movie"

    val NAME = "name" of null
    val ROLES = "roles" of null

    val PERSON_NAME = "name" of PERSON
    val PERSON_BORN = "born" of PERSON
    val MOVIE_TITLE = "title" of MOVIE
    val MOVIE_RELEASED = "released" of MOVIE
    val MOVIE_TAGLINE = "tagline" of MOVIE
    val ACTED_IN_ROLES = "roles" of "ACTED_IN"
    val REVIEWED_SUMMARY = "summary" of "REVIEWED"
    val REVIEWED_RATING = "rating" of "REVIEWED"

    val ACTED_IN = Query.Relationship("ACTED_IN", PERSON, MOVIE)
    val PERSON_ACTED_IN = Query.Relationship("ACTED_IN", PERSON, null)
    val DIRECTED = Query.Relationship("DIRECTED", PERSON, MOVIE)
    val PRODUCED = Query.Relationship("PRODUCED", PERSON, MOVIE)
    val WROTE = Query.Relationship("WROTE", PERSON, MOVIE)
    val REVIEWED = Query.Relationship("REVIEWED", PERSON, MOVIE)

    infix fun String.of(owner: String?): Query.Property {
      return Query.Property(owner, this, emptySet())
    }

    infix fun Query.Property.with(value: Any): Query.Property {
      return when (value) {
        is List<*> -> copy(values = setOf(Query.Property.Type.Container(value)))
        is Set<*> ->
            copy(
                values =
                    value
                        .mapNotNull {
                          when (it) {
                            is String ->
                                if (it.startsWith("$")) Query.Property.Type.Resolvable(it)
                                else Query.Property.Type.Value(it)
                            is Boolean,
                            is Long,
                            is Float -> Query.Property.Type.Value(it)
                            else -> null
                          }
                        }
                        .toSet())
        else -> copy(values = setOf(Query.Property.Type.Value(value)))
      }
    }

    infix fun Query.Property.with(value: KClass<*>): Query.Property {
      return copy(values = setOf(Query.Property.Type.Value(value)))
    }

    infix fun Query.Property.with(values: List<KClass<*>>): Query.Property {
      return copy(values = setOf(Query.Property.Type.Container(values)))
    }

    fun Set<Query.Property>.types(): Set<Query.Property> {
      fun Query.Property.Type.type(): Query.Property.Type {
        return when (this) {
          is Query.Property.Type.Value -> Query.Property.Type.Value(value!!::class)
          is Query.Property.Type.Container ->
              Query.Property.Type.Container(values.map { it!!::class })
          is Query.Property.Type.Resolvable -> fail("Unknown value type")
        }
      }
      fun Query.Property.types(): Query.Property {
        return copy(values = values.map(Query.Property.Type::type).toSet())
      }
      return map(Query.Property::types).toSet()
    }

    fun Set<Query.Property.Type>.values(): Set<Any?> {
      return flatMap { type ->
            when (type) {
              is Query.Property.Type.Value -> setOf(type.value)
              is Query.Property.Type.Container -> type.values
              is Query.Property.Type.Resolvable -> setOf(type.name)
            }
          }
          .toSet()
    }
  }
}
