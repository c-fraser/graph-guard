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
package io.github.cfraser.graphguard.cli

import io.github.cfraser.graphguard.LOCAL
import io.github.cfraser.graphguard.driver
import io.github.cfraser.graphguard.isE2eTest
import io.github.cfraser.graphguard.knit.runInvalidMoviesQueries
import io.github.cfraser.graphguard.runMoviesQueries
import io.kotest.core.spec.style.StringSpec
import org.neo4j.driver.AuthTokens

/**
 * Execute queries proxied through the CLI application to a Neo4J container.
 *
 * Start the CLI application.
 *
 * ```shell
 *  ./gradlew graph-guard-cli:clean graph-guard-cli:installShadowDist
 *  echo ''
 *  cat <<'EOF' | ./graph-guard-cli/build/install/graph-guard-cli-shadow/bin/graph-guard-cli --inspect -s -
 *  graph Movies {
 *    node Person(name: String, born: Integer):
 *      ACTED_IN(roles: List<String>) -> Movie,
 *      DIRECTED -> Movie,
 *      PRODUCED -> Movie,
 *      WROTE -> Movie,
 *      REVIEWED(summary: String, rating: Integer) -> Movie;
 *    node Movie(title: String, released: Integer, tagline: String);
 *  }
 *  EOF
 * ```
 *
 * In a separate terminal, start the Neo4J container then run the graph queries.
 *
 * ```shell
 *  CONTAINER_ID=$(docker run -d -p 7687:7687 --env NEO4J_AUTH=neo4j/password neo4j:latest)
 *  sleep 10
 *  ./gradlew graph-guard-cli:test \
 *    --tests 'io.github.cfraser.graphguard.cli.E2ETest' \
 *    -Dkotest.tags='Local' \
 *    -Dgraph-guard.e2e.test='true' \
 *    --rerun
 *  docker rm -f "$CONTAINER_ID"
 * ```
 */
class E2ETest :
  StringSpec({
    tags(LOCAL)
    "run queries"
      .config(enabled = isE2eTest) {
        driver(auth = AuthTokens.basic("neo4j", "password")).use { driver ->
          runMoviesQueries(driver)
          runInvalidMoviesQueries(driver)
        }
      }
  })
