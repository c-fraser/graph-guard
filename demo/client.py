#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "neo4j",
# ]
# ///

"""A test script demonstrating a client connecting to Neo4j through graph-guard.

Usage examples:
  # Docker compose (default):
  uv run client.py

  # Local server:
  uv run client.py --uri bolt://localhost:8787

  # Show help:
  uv run client.py --help
"""

import argparse
import sys
import time
from neo4j import Driver, GraphDatabase, Session
from typing import Any


def wait_for_service(driver: Driver, max_retries: int = 5, delay: int = 5) -> bool:
    """Wait for the graph-guard service to be ready."""
    print("Waiting for graph-guard service to be ready...")
    for i in range(max_retries):
        try:
            driver.verify_connectivity()
            print("✓ graph-guard service is ready")
            return True
        except Exception as e:
            if i < max_retries - 1:
                print(
                    f"  Attempt {i + 1}/{max_retries}: Service not ready yet, "
                    f"retrying in {delay}s..."
                )
                time.sleep(delay)
            else:
                print(f"✗ Service failed to become ready: {e}")
                return False
    return False


def run_query(
    session: Session,
    query: str,
    description: str,
    parameters: dict[str, Any] | None = None,
    expected_count: int | None = None,
) -> bool:
    """Run a query and print the results."""
    print(f"\n→ {description}")
    query_display: str = query.strip()
    if len(query_display) > 80:
        query_display = query_display[:77] + "..."
    print(f"  Query: {query_display}")
    if parameters:
        print(f"  Parameters: {parameters}")
    try:
        result = session.run(query, parameters or {})
        records: list[Any] = list(result)
        print(f"  ✓ Success: {len(records)} record(s) returned", end="")
        if expected_count is not None and len(records) == expected_count:
            print(" ✓")
        elif expected_count is not None:
            print(f" (expected {expected_count})")
        else:
            print()

        for record in records[:3]:  # Print first 3 records
            print(f"    {dict(record)}")
        if len(records) > 3:
            print(f"    ... and {len(records) - 3} more")
        return True
    except Exception as e:
        print(f"  ✗ Failed: {e}")
        return False


def main(
    uri: str,
    username: str,
    password: str,
    max_retries: int = 5,
    retry_delay: int = 3,
) -> int:
    """Run the test suite against graph-guard.

    Args:
        uri: Graph-guard Bolt URI
        username: Neo4j username
        password: Neo4j password
        max_retries: Maximum connection retry attempts
        retry_delay: Delay in seconds between retry attempts

    Returns:
        Exit code (0 for success, 1 for failure)
    """
    print(f"Connecting to graph-guard at {uri}...")

    driver: Driver = GraphDatabase.driver(uri, auth=(username, password))

    try:
        # Wait for service to be ready
        if not wait_for_service(driver, max_retries=max_retries, delay=retry_delay):
            return 1

        with driver.session() as session:
            print("\n" + "=" * 80)
            print("MOVIES GRAPH TEST SUITE")
            print("=" * 80)

            # ===== MATCH QUERIES =====
            print("\n" + "=" * 80)
            print("SECTION 1: Match Queries (Read Operations)")
            print("=" * 80)

            run_query(
                session,
                "MATCH (tom:Person {name: 'Tom Hanks'}) RETURN tom",
                "Find Tom Hanks",
                expected_count=1,
            )

            run_query(
                session,
                "MATCH (cloudAtlas:Movie {title: 'Cloud Atlas'}) RETURN cloudAtlas",
                "Find Cloud Atlas movie",
                expected_count=1,
            )

            run_query(
                session,
                "MATCH (people:Person) RETURN people.name LIMIT 10",
                "Find 10 people",
                expected_count=10,
            )

            run_query(
                session,
                """MATCH (nineties:Movie)
               WHERE nineties.released >= 1990 AND nineties.released < 2000
               RETURN nineties.title""",
                "Find movies released in the 1990s",
                expected_count=20,
            )

            run_query(
                session,
                """MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(tomHanksMovies:Movie)
               RETURN tom, tomHanksMovies""",
                "Find movies Tom Hanks acted in",
                expected_count=12,
            )

            run_query(
                session,
                """MATCH (cloudAtlas:Movie {title: 'Cloud Atlas'})<-[:DIRECTED]-(directors:Person)
               RETURN directors.name""",
                "Find Cloud Atlas directors",
                expected_count=3,
            )

            run_query(
                session,
                """MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(:Movie)<-[:ACTED_IN]-(coActors:Person)
               RETURN DISTINCT coActors.name""",
                "Find Tom Hanks co-actors",
                expected_count=34,
            )

            run_query(
                session,
                """MATCH (people:Person)-[relatedTo]-(:Movie {title: 'Cloud Atlas'})
               RETURN people.name, type(relatedTo), relatedTo.roles""",
                "Find people related to Cloud Atlas",
                expected_count=10,
            )

            # ===== PATTERN QUERIES =====
            print("\n" + "=" * 80)
            print("SECTION 2: Graph Pattern Queries")
            print("=" * 80)

            run_query(
                session,
                """MATCH (bacon:Person {name: 'Kevin Bacon'})-[*1..6]-(hollywood)
               RETURN DISTINCT hollywood""",
                "Six degrees of Kevin Bacon",
                expected_count=170,
            )

            run_query(
                session,
                """MATCH p = shortestPath((bacon:Person {name: 'Kevin Bacon'})-[*]-(:Person {name: $name}))
               RETURN p""",
                "Shortest path from Kevin Bacon to Tom Hanks",
                parameters={"name": "Tom Hanks"},
                expected_count=1,
            )

            run_query(
                session,
                """MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(coActors),
                     (coActors)-[:ACTED_IN]->(m2)<-[:ACTED_IN]-(cocoActors)
               WHERE NOT (tom)-[:ACTED_IN]->()<-[:ACTED_IN]-(cocoActors) AND tom <> cocoActors
               RETURN cocoActors.name AS Recommended, COUNT(*) AS Strength
               ORDER BY Strength DESC""",
                "Recommended co-actors for Tom Hanks",
                expected_count=44,
            )

            run_query(
                session,
                """MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(coActors),
                     (coActors)-[:ACTED_IN]->(m2)<-[:ACTED_IN]-(p:Person {name: $name})
               RETURN tom, m, coActors, m2, p""",
                "Co-actors connecting Tom Hanks and Keanu Reeves",
                parameters={"name": "Keanu Reeves"},
                expected_count=4,
            )

            # ===== AGGREGATION QUERIES =====
            print("\n" + "=" * 80)
            print("SECTION 3: Aggregation & Analysis Queries")
            print("=" * 80)

            run_query(
                session,
                """MATCH (p:Person)-[:ACTED_IN]->(m:Movie)
               RETURN p.name AS actor, COUNT(m) AS movie_count
               ORDER BY movie_count DESC
               LIMIT 10""",
                "Top 10 actors by number of movies",
            )

            run_query(
                session,
                """MATCH (m:Movie)<-[:ACTED_IN]-(p:Person)
               RETURN m.title AS movie, COUNT(p) AS actor_count
               ORDER BY actor_count DESC
               LIMIT 10""",
                "Top 10 movies by number of actors",
            )

            run_query(
                session,
                """MATCH (p:Person)-[:DIRECTED]->(m:Movie)
               RETURN p.name AS director, COUNT(m) AS movies_directed
               ORDER BY movies_directed DESC""",
                "Directors and their movie counts",
            )

            run_query(
                session,
                """MATCH (p:Person)-[:REVIEWED]->(m:Movie)
               RETURN m.title AS movie, AVG(p.rating) AS avg_rating, COUNT(*) AS review_count
               ORDER BY avg_rating DESC""",
                "Movies with average ratings",
            )

            # ===== RELATIONSHIP QUERIES =====
            print("\n" + "=" * 80)
            print("SECTION 4: Relationship Pattern Queries")
            print("=" * 80)

            run_query(
                session,
                """MATCH (p:Person)-[r:ACTED_IN]->(m:Movie)
               WHERE size(r.roles) > 1
               RETURN p.name AS actor, m.title AS movie, r.roles AS multiple_roles
               LIMIT 10""",
                "Actors playing multiple roles in the same movie",
            )

            run_query(
                session,
                """MATCH (p:Person)-[:ACTED_IN]->(m:Movie)<-[:DIRECTED]-(p)
               RETURN p.name AS person, m.title AS movie""",
                "People who both acted in and directed the same movie",
            )

            run_query(
                session,
                """MATCH (p:Person)-[:ACTED_IN]->(m:Movie)<-[:PRODUCED]-(p)
               RETURN p.name AS person, m.title AS movie""",
                "People who both acted in and produced the same movie",
            )

            run_query(
                session,
                """MATCH (p1:Person)-[:ACTED_IN]->(m:Movie)<-[:ACTED_IN]-(p2:Person)
               WHERE p1.name < p2.name
               RETURN p1.name AS actor1, p2.name AS actor2, COUNT(m) AS movies_together
               ORDER BY movies_together DESC
               LIMIT 10""",
                "Actor pairs who appeared together most frequently",
            )

            # ===== WRITE OPERATIONS =====
            print("\n" + "=" * 80)
            print("SECTION 5: Write Operations")
            print("=" * 80)

            run_query(
                session,
                "CREATE (p:Person {name: 'Test Actor', born: 1990}) RETURN p",
                "Create a new Person node",
                expected_count=1,
            )

            run_query(
                session,
                "CREATE (m:Movie {title: 'Test Movie', released: 2024, tagline: 'A test movie'}) RETURN m",
                "Create a new Movie node",
                expected_count=1,
            )

            run_query(
                session,
                """MATCH (p:Person {name: 'Test Actor'})
               MATCH (m:Movie {title: 'Test Movie'})
               CREATE (p)-[r:ACTED_IN {roles: ['Lead']}]->(m)
               RETURN p, r, m""",
                "Create ACTED_IN relationship",
                expected_count=1,
            )

            run_query(
                session,
                """MERGE (p:Person {name: 'Keanu Reeves'})-[:ACTED_IN {roles: ['Neo']}]->
                     (:Movie {title: 'The Matrix', released: 1999, tagline: 'Welcome to the Real World'})
               RETURN p""",
                "MERGE operation (idempotent create)",
                expected_count=1,
            )

            # ===== NOTIFICATION TESTS =====
            print("\n" + "=" * 80)
            print("SECTION 6: Notification-Generating Queries (Performance Hints)")
            print("=" * 80)

            # Cartesian Product Warning
            run_query(
                session,
                """MATCH (p:Person), (m:Movie)
                   WHERE p.born > 1970 AND m.released > 2000
                   RETURN p.name, m.title
                   LIMIT 10""",
                "Cartesian product query (should generate notification)",
            )

            # Missing Label on Node
            run_query(
                session,
                """MATCH (n)
                   WHERE n.name = 'Tom Hanks'
                   RETURN n""",
                "Query without label (should generate notification)",
            )

            # Unbounded Shortest Path
            run_query(
                session,
                """MATCH p = shortestPath((a:Person {name: 'Kevin Bacon'})-[*]-(b:Person {name: 'Tom Hanks'}))
                   RETURN p""",
                "Unbounded shortest path (should generate notification)",
            )

            # Dynamic Property Access
            run_query(
                session,
                """MATCH (p:Person)
                   WITH p, ['name', 'born'] AS props
                   RETURN p[props[0]] AS value
                   LIMIT 5""",
                "Dynamic property lookup (should generate notification)",
            )

            # Eager Loading with LOAD CSV
            run_query(
                session,
                """WITH ['Tom Hanks', 'Keanu Reeves', 'Tom Cruise'] AS names
                   UNWIND names AS name
                   MATCH (p:Person {name: name})
                   RETURN p.name, p.born""",
                "Eager operation with UNWIND (may generate notification)",
            )

            # Missing Relationship Direction
            run_query(
                session,
                """MATCH (p:Person)-[:ACTED_IN]-(m:Movie)
                   WHERE p.name = 'Tom Hanks'
                   RETURN m.title
                   LIMIT 10""",
                "Undirected relationship (should generate notification)",
            )

            # Suboptimal Index Usage
            run_query(
                session,
                """MATCH (p:Person)
                   WHERE p.born > 1960 AND p.born < 1970
                   RETURN p.name
                   LIMIT 10""",
                "Range query without index (may generate notification)",
            )

            # CREATE with existing nodes (potential duplicate)
            run_query(
                session,
                """CREATE (p:Person {name: 'Tom Hanks', born: 1956})
                   RETURN p""",
                "CREATE possibly creating duplicate (should generate notification)",
            )

            # Remove duplicate created above
            session.run(
                """MATCH (p:Person {name: 'Tom Hanks'})
                   WITH p ORDER BY p.born SKIP 1
                   DETACH DELETE p"""
            )

            # Multiple shortest paths without LIMIT
            run_query(
                session,
                """MATCH p = allShortestPaths((a:Person)-[*]-(b:Person))
                   WHERE a.name = 'Kevin Bacon' AND b.name <> 'Kevin Bacon'
                   RETURN p
                   LIMIT 5""",
                "All shortest paths (potentially expensive, may generate notification)",
            )

            # Non-selective filter
            run_query(
                session,
                """MATCH (p:Person)-[:ACTED_IN]->(m:Movie)
                   WHERE 1=1
                   RETURN p.name, m.title
                   LIMIT 10""",
                "Query with always-true WHERE clause (should generate notification)",
            )

            # Deprecated function usage (if any)
            run_query(
                session,
                """MATCH (p:Person)
                   RETURN p.name, length(p.name) AS name_length
                   LIMIT 10""",
                "Using length() function (may generate deprecation notification)",
            )

            # Join hint without appropriate pattern
            run_query(
                session,
                """MATCH (p:Person)
                   WHERE EXISTS {
                       MATCH (p)-[:ACTED_IN]->(m:Movie)
                       WHERE m.released > 2000
                   }
                   RETURN p.name
                   LIMIT 10""",
                "Existential subquery (may generate performance notification)",
            )

            # ===== VALIDATION TESTS =====
            print("\n" + "=" * 80)
            print("SECTION 7: Schema Validation Tests (Expected Failures)")
            print("=" * 80)

            run_query(
                session,
                "CREATE (p:Person {name: 'Invalid Person', invalidProperty: 'test'}) RETURN p",
                "Invalid property test (should fail validation)",
            )

            run_query(
                session,
                "CREATE (x:InvalidLabel {name: 'Test'}) RETURN x",
                "Invalid node label test (should fail validation)",
            )

            run_query(
                session,
                "CREATE (p:Person {name: 'Bad Person', born: 'not a number'}) RETURN p",
                "Invalid property type test (should fail validation)",
            )

            run_query(
                session,
                """CREATE (p:Person {name: 'Actor'})-[:INVALID_RELATIONSHIP]->(m:Movie {title: 'Movie'})
                   RETURN p, m""",
                "Invalid relationship type test (should fail validation)",
            )

            run_query(
                session,
                "invalid cypher syntax",
                "Invalid Cypher syntax test (should fail)",
            )

            # ===== CLEANUP =====
            print("\n" + "=" * 80)
            print("Cleanup")
            print("=" * 80)
            session.run("MATCH (n:Person {name: 'Test Actor'}) DETACH DELETE n")
            session.run("MATCH (n:Movie {title: 'Test Movie'}) DETACH DELETE n")
            print("✓ Test data cleaned up")

        print("✓ Completed tests!")

        return 0
    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback

        traceback.print_exc()
        return 1
    finally:
        driver.close()


if __name__ == "__main__":
    parser: argparse.ArgumentParser = argparse.ArgumentParser(
        description="Test client for graph-guard proxy",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--uri",
        default="bolt://graph-guard:8787",
        help="graph-guard bolt URI",
    )
    parser.add_argument(
        "--username",
        "-u",
        default="neo4j",
        help="Neo4j username",
    )
    parser.add_argument(
        "--password",
        "-p",
        default="password",
        help="Neo4j password",
    )
    args: argparse.Namespace = parser.parse_args()
    sys.exit(main(args.uri, args.username, args.password))
