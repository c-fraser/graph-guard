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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import java.net.URI
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Values

class ServerTest : FunSpec() {

  init {
    test("proxy bolt messages") {
      withNeo4j {
        val proxy = thread {
          Server.create("localhost", 8787, URI(boltUrl), MOVIES_GRAPH_SCHEMA).run()
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
        GraphDatabase.driver("bolt://localhost:8787", AuthTokens.basic("neo4j", adminPassword))
            .use { driver ->
              driver.session().use { session ->
                MoviesGraph.CREATE.forEach(session::run)
                session.run(MoviesGraph.MATCH_TOM_HANKS).list() shouldHaveSize 1
                session.run(MoviesGraph.MATCH_CLOUD_ATLAS).list() shouldHaveSize 1
                session.run(MoviesGraph.MATCH_10_PEOPLE).list() shouldHaveSize 10
                session.run(MoviesGraph.MATCH_NINETIES_MOVIES).list() shouldHaveSize 20
                session.run(MoviesGraph.MATCH_TOM_HANKS_MOVIES).list() shouldHaveSize 12
                session.run(MoviesGraph.MATCH_CLOUD_ATLAS_DIRECTOR).list() shouldHaveSize 3
                session.run(MoviesGraph.MATCH_TOM_HANKS_CO_ACTORS).list() shouldHaveSize 34
                session.run(MoviesGraph.MATCH_CLOUD_ATLAS_PEOPLE).list() shouldHaveSize 10
                session.run(MoviesGraph.MATCH_SIX_DEGREES_OF_KEVIN_BACON).list() shouldHaveSize 170
                session
                    .run(
                        MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO,
                        Values.parameters("name", "Tom Hanks"))
                    .list() shouldHaveSize 1
                session.run(MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS).list() shouldHaveSize
                    44
                session
                    .run(
                        MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND,
                        Values.parameters("name", "Keanu Reeves"))
                    .list() shouldHaveSize 4
              }
            }
        proxy.interrupt()
      }
    }
  }
}
