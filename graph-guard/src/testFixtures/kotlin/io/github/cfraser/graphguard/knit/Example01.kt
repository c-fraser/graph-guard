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

// This file was automatically generated by https://github.com/Kotlin/kotlinx-knit. DO NOT EDIT.
package io.github.cfraser.graphguard.knit

import org.neo4j.driver.Driver
import org.neo4j.driver.exceptions.DatabaseException

/** Use the [driver] to run queries that violate the *movies* schema. */
fun runInvalidMoviesQueries(driver: Driver) {
  driver.session().use { session ->
    /** Run the invalid [query] and print the schema violation message. */
    fun run(query: String) {
      try {
        session.run(query)
        error("Expected schema violation for query '$query'")
      } catch (exception: DatabaseException) {
        println(exception.message)
      }
    }
    run("CREATE (:TVShow {title: 'The Office', released: 2005})")
    run("MATCH (theMatrix:Movie {title: 'The Matrix'}) SET theMatrix.budget = 63000000")
    run("MERGE (:Person {name: 'Chris Fraser'})-[:WATCHED]->(:Movie {title: 'The Matrix'})")
    run("MATCH (:Person)-[produced:PRODUCED]->(:Movie {title: 'The Matrix'}) SET produced.studio = 'Warner Bros.'")
    run("CREATE (Keanu:Person {name: 'Keanu Reeves', born: '09/02/1964'})")
  }
}
