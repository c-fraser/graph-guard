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

import io.github.cfraser.graphguard.MoviesGraph
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

  companion object {

    /**
     * Cypher for
     * [temporal functions](https://neo4j.com/docs/cypher-manual/current/functions/temporal/).
     */
    val TIMES =
        """
        date()
        date({timezone: 'America/New York'})
        date({year: 1984, month: 10, day: 11})
        date({year: 1984, month: 10})
        date({year: 1984, week: 10, dayOfWeek: 3})
        date({year: 1984, week: 10})
        date({year: 1984, quarter: 3, dayOfQuarter: 45})
        date({year: 1984, quarter: 3})
        date({year: 1984, ordinalDay: 202})
        date({year: 1984})
        date('2015-07-21')
        date('2015-07')
        date('201507')
        date('2015-W30-2')
        date('2015202')
        date('2015')
        datetime()
        datetime({timezone: 'America/New York'})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, millisecond: 123, microsecond: 456, nanosecond: 789})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, millisecond: 645, timezone: '+01:00'})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, nanosecond: 645876123, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, timezone: '+01:00'})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14})
        datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, month: 10, day: 11, hour: 12, timezone: '+01:00'})
        datetime({year: 1984, month: 10, day: 11, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14, millisecond: 645})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14, microsecond: 645876, timezone: '+01:00'})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14, nanosecond: 645876123, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14})
        datetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, timezone: '+01:00'})
        datetime({year: 1984, week: 10, dayOfWeek: 3, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, quarter: 3, dayOfQuarter: 45, hour: 12, minute: 31, second: 14, microsecond: 645876})
        datetime({year: 1984, quarter: 3, dayOfQuarter: 45, hour: 12, minute: 31, second: 14, timezone: '+01:00'})
        datetime({year: 1984, quarter: 3, dayOfQuarter: 45, hour: 12, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, quarter: 3, dayOfQuarter: 45})
        datetime({year: 1984, ordinalDay: 202, hour: 12, minute: 31, second: 14, millisecond: 645})
        datetime({year: 1984, ordinalDay: 202, hour: 12, minute: 31, second: 14, timezone: '+01:00'})
        datetime({year: 1984, ordinalDay: 202, timezone: 'Europe/Stockholm'})
        datetime({year: 1984, ordinalDay: 202})
        datetime('2015-07-21T21:40:32.142+0100')
        datetime('2015-W30-2T214032.142Z')
        datetime('2015T214032-0100')
        datetime('20150721T21:40-01:30')
        datetime('2015-W30T2140-02')
        datetime('2015202T21+18:00')
        datetime('2015-07-21T21:40:32.142[Europe/London]')
        datetime('2015-07-21T21:40:32.142-04[America/New_York]')
        localdatetime()
        localdatetime({timezone: 'America/New York'})
        localdatetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, millisecond: 123, microsecond: 456, nanosecond: 789})
        localdatetime({year: 1984, week: 10, dayOfWeek: 3, hour: 12, minute: 31, second: 14, millisecond: 645})
        localdatetime({year: 1984, quarter: 3, dayOfQuarter: 45, hour: 12, minute: 31, second: 14, nanosecond: 645876123})
        localdatetime({year: 1984, ordinalDay: 202, hour: 12, minute: 31, second: 14, microsecond: 645876})
        localdatetime('2015-07-21T21:40:32.142')
        localdatetime('2015-W30-2T214032.142')
        localdatetime('2015-202T21:40:32')
        localdatetime('2015202T21')
        localtime()
        localtime({timezone: 'America/New York'})
        localtime({hour: 12, minute: 31, second: 14, nanosecond: 789, millisecond: 123, microsecond: 456})
        localtime({hour: 12, minute: 31, second: 14})
        localtime({hour: 12})
        localtime('21:40:32.142')
        localtime('214032.142')
        localtime('21:40')
        localtime('21')
        time()
        time({timezone: 'America/New York'})
        time({hour: 12, minute: 31, second: 14, millisecond: 123, microsecond: 456, nanosecond: 789})
        time({hour: 12, minute: 31, second: 14, nanosecond: 645876123})
        time({hour: 12, minute: 31, second: 14, microsecond: 645876, timezone: '+01:00'})
        time({hour: 12, minute: 31, timezone: '+01:00'})
        time({hour: 12, timezone: '+01:00'})
        time('21:40:32.142+0100')
        time('214032.142Z')
        time('21:40:32+01:00')
        time('214032-0100')
        time('21:40-01:30')
        time('2140-00:00')
        time('2140-02')
        time('22+18:00')
        """
            .lines()
            .map(String::trim)
            .filterNot(String::isBlank)

    private const val PERSON = "Person"
    private const val MOVIE = "Movie"

    private val NAME = "name" of null
    private val ROLES = "roles" of null

    private val PERSON_NAME = "name" of PERSON
    private val PERSON_BORN = "born" of PERSON
    private val MOVIE_TITLE = "title" of MOVIE
    private val MOVIE_RELEASED = "released" of MOVIE
    private val MOVIE_TAGLINE = "tagline" of MOVIE
    private val ACTED_IN_ROLES = "roles" of "ACTED_IN"
    private val REVIEWED_SUMMARY = "summary" of "REVIEWED"
    private val REVIEWED_RATING = "rating" of "REVIEWED"

    private val ACTED_IN = Query.Relationship("ACTED_IN", PERSON, MOVIE)
    private val PERSON_ACTED_IN = Query.Relationship("ACTED_IN", PERSON, null)
    private val DIRECTED = Query.Relationship("DIRECTED", PERSON, MOVIE)
    private val PRODUCED = Query.Relationship("PRODUCED", PERSON, MOVIE)
    private val WROTE = Query.Relationship("WROTE", PERSON, MOVIE)
    private val REVIEWED = Query.Relationship("REVIEWED", PERSON, MOVIE)

    private infix fun String.of(owner: String?): Query.Property {
      return Query.Property(owner, this, emptySet())
    }

    private infix fun Query.Property.with(value: Any): Query.Property {
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

    private infix fun Query.Property.with(value: KClass<*>): Query.Property {
      return copy(values = setOf(Query.Property.Type.Value(value)))
    }

    private infix fun Query.Property.with(values: List<KClass<*>>): Query.Property {
      return copy(values = setOf(Query.Property.Type.Container(values)))
    }

    private fun Set<Query.Property>.types(): Set<Query.Property> {
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

    private fun Set<Query.Property.Type>.values(): Set<Any?> {
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
