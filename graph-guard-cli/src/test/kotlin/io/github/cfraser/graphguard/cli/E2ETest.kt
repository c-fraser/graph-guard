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
 *  cat <<'EOF' | ./cli/build/install/graph-guard-cli-shadow/bin/graph-guard-cli --styled -s -
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
