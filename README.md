# graph-guard

[![Test](https://github.com/c-fraser/graph-guard/workflows/test/badge.svg)](https://github.com/c-fraser/graph-guard/actions)
[![Release](https://img.shields.io/github/v/release/c-fraser/graph-guard?logo=github&sort=semver)](https://github.com/c-fraser/graph-guard/releases)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.c-fraser/graph-guard.svg)](https://search.maven.org/search?q=g:io.github.c-fraser%20AND%20a:graph-guard)
[![Apache License 2.0](https://img.shields.io/badge/License-Apache2-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

`graph-guard` is an extensible [Bolt](https://neo4j.com/docs/bolt/current/bolt/) proxy server,
that's capable of performing realtime [Cypher](https://opencypher.org/) query validation,
for [Neo4j](https://neo4j.com/) 5+ (compatible databases).

<!--- TOC -->

* [Design](#design)
  * [Schema](#schema)
    * [Graph](#graph)
    * [Nodes](#nodes)
    * [Relationships](#relationships)
    * [Properties](#properties)
    * [Metadata](#metadata)
    * [Violations](#violations)
    * [Grammar](#grammar)
* [Usage](#usage)
  * [Examples](#examples)
    * [Kotlin](#kotlin)
    * [Java](#java)
  * [Documentation](#documentation)
  * [CLI](#cli)
* [Libraries](#libraries)
  * [graph-guard-validate](#graph-guard-validate)
  * [graph-guard-script](#graph-guard-script)
* [License](#license)

<!--- END -->

## Design

The [Server](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/index.html)
proxies [Bolt](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-bolt/index.html)
messages as displayed in the diagram below.

![proxy-server](docs/proxy-server.png)

Proxied messages are intercepted by
the [Plugin](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/-plugin/index.html),
enabling
the [Server](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/index.html)
to dynamically transform the incoming and outgoing data.

[Validator](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard.plugin/-validator/index.html)
is
a [Plugin](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/-plugin/index.html)
that performs realtime query validation by
intercepting [RUN](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-bolt/-run/index.html)
requests then analyzing the [Cypher](https://opencypher.org/) (and parameters) for
schema [violations](#violations). If the intercepted query is determined to be *invalid* according
to the schema, then
a [FAILURE](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-bolt/-failure/index.html)
response is sent to the *client*.

For example, validate [movies](https://github.com/neo4j-graph-examples/movies) queries align with
the [schema](#schema) via the [Server](#design), using the [graph-guard](#usage) library.

<!--- INCLUDE
import org.neo4j.driver.Driver
import org.neo4j.driver.exceptions.DatabaseException
-->

[//]: # (@formatter:off)
```kotlin
/** Use the [driver] to run queries that violate the *movies* schema. */
fun runInvalidMoviesQueries(driver: Driver) {
  driver.session().use { session ->
    for (query in
      listOf(
        "CREATE (:TVShow {title: 'The Office', released: 2005})",
        "MATCH (theMatrix:Movie {title: 'The Matrix'}) SET theMatrix.budget = 63000000",
        "MERGE (:Person {name: 'Chris Fraser'})-[:WATCHED]->(:Movie {title: 'The Matrix'})",
        "MATCH (:Person)-[produced:PRODUCED]->(:Movie {title: 'The Matrix'}) SET produced.studio = 'Warner Bros.'",
        "CREATE (Keanu:Person {name: 'Keanu Reeves', born: '09/02/1964'})")) {
      // run the invalid query and print the schema violation message
      try {
        session.run(query)
        error("Expected schema violation for query '$query'")
      } catch (exception: DatabaseException) {
        println(exception.message)
      }
    }
  }
}
```
[//]: # (@formatter:on)
<!--- KNIT Example01.kt -->

<!--- TEST_NAME Example02Test --> 
<!--- INCLUDE
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.validate.Schema
import io.github.cfraser.graphguard.plugin.Validator
import io.github.cfraser.graphguard.withNeo4j
import java.net.InetSocketAddress
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase

fun runExample02() {
  withNeo4j {
----- SUFFIX
  }
}
-->

[//]: # (@formatter:off)
```kotlin
val plugin = Validator(Schema(MOVIES_SCHEMA))
val server = Server(boltURI(), plugin, InetSocketAddress("localhost", 8787))
server.use {
  GraphDatabase.driver("bolt://localhost:8787", Config.builder().withoutEncryption().build())
    .use(::runInvalidMoviesQueries)
}
```
[//]: # (@formatter:on)
<!--- KNIT Example02.kt --> 

The code above prints the following *schema violation* messages.

```text
Unknown node TVShow
Unknown property 'budget' for node Movie
Unknown relationship WATCHED from Person to Movie
Unknown property 'studio' for relationship PRODUCED from Person to Movie
Invalid query value(s) '09/02/1964' for property 'born: Integer' on node Person
```

<!--- TEST -->

### Schema

A schema describes the nodes and relationships in a graph. The schema is defined
using a custom DSL language, demonstrated below for
the [movies](https://github.com/neo4j-graph-examples/movies) graph.

<!--- INCLUDE
/** The schema DSL for the *movies* graph. */
const val MOVIES_SCHEMA =
    """
----- SUFFIX
"""
-->

[//]: # (@formatter:off)
```kotlin
graph Movies {
  node Person(name: String, born: Integer):
      ACTED_IN(roles: List<String>) -> Movie,
      DIRECTED -> Movie,
      PRODUCED -> Movie,
      WROTE -> Movie,
      REVIEWED(summary: String, rating: Integer) -> Movie;

  node Movie(title: String, released: Integer, tagline: String);
}
```
[//]: # (@formatter:on)
<!--- KNIT Example03.kt --> 

#### Graph

A `graph` contains [node statements](#nodes). A [schema](#schema) may include multiple
interconnected [graphs](#graph). To reference a *node* in another *graph*, qualify the *node* name
with the *graph* name, as shown below.

<!--- INCLUDE
const val PLACES_SCHEMA =
    """
----- SUFFIX
"""
-->

[//]: # (@formatter:off)
```kotlin
graph Places {
  node Theater(name: String):
      SHOWING(times: List<Integer>) -> Movies.Movie;
}
```
[//]: # (@formatter:on)
<!--- KNIT Example04.kt --> 

#### Nodes

A `node` must have a unique name, and may
have [properties](#properties) and/or [relationship definitions](#relationships).

#### Relationships

Relationships are defined relative to the *source* [node](#nodes). A relationship definition must
have a name, direction (`->` for directed, or `--` for undirected), and target node. A relationship
must have a unique `(source)-[name]-(target)`, and may also have [properties](#properties).

#### Properties

A [node](#nodes) or [relationship](#relationships) may have *typed* properties. The supported
property types are listed below.

> The types align with the
> supported [Cypher values](https://neo4j.com/docs/cypher-manual/current/values-and-types/).

- `Any` - a dynamically typed property
- `Boolean`
- `Date` - a
  [date()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/#functions-date)
- `DateTime` -
  a [datetime()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/#functions-datetime)
- `Duration` -
  a [duration()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/duration/)
- `Float`
- `Integer`
- `List<T>` - where `T` is another (un-parameterized) supported type
- `LocalDateTime` -
  a [localdatetime()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/#functions-localdatetime)
- `LocalTime` -
  a [localtime()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/#functions-localtime)
- `String`
- `Time` -
  a [time()](https://neo4j.com/docs/cypher-manual/current/functions/temporal/#functions-time)
- String literal - any value enclosed in double quotation marks, e.g. `"example"`

A property can be designated as nullable by including the `?` suffix on the type, for
example `String?` and `List<Any?>`.

> The omission of a *type* results in the usage of `Any?`, which effectively disables property value
> validation.

A [property](#properties) may specify multiple types of values with the *union type* syntax, as
shown below.

<!--- INCLUDE
const val UNION_SCHEMA =
    """
----- SUFFIX
"""
-->

[//]: # (@formatter:off)
```kotlin
graph G {
  node N(p: Boolean | "true" | "false");
}
```
[//]: # (@formatter:on)
<!--- KNIT Example05.kt --> 

#### Metadata

A [node](#nodes), [relationship](#relationships), or [property](#properties) may have arbitrary
*metadata*.

> Currently, the metadata is purely information, it isn't used in [schema](#schema) verification.

<!--- INCLUDE
const val METADATA_SCHEMA =
    """
----- SUFFIX
"""
-->

[//]: # (@formatter:off)
```kotlin
graph G {
  node @a N(@b("c") p: Any):
      @d R(@e("f") @g p: Any) -- N;
}
```
[//]: # (@formatter:on)
<!--- KNIT Example06.kt --> 

The metadata annotations can have any name, and may include a string literal value within
parenthesis.

#### Violations

The Cypher query validation prevents the following [schema](#schema) violations.

- `"Unknown ${entity}"` - a query has a node or relationship not defined in the schema
- `"Unknown property '${property}' for ${entity}"` - a query has a property (on a node or
  relationship) not defined in the schema
- `"Invalid query value(s) '${values}' for property '${property}' on ${entity}"` - a query has
  property value(s) (on a node or relationship) conflicting with the type defined in the schema

#### Grammar

Refer to
the ([antlr4](https://github.com/antlr/antlr4))
[grammar](https://github.com/c-fraser/graph-guard/blob/main/graph-guard-validate/src/main/antlr/Schema.g4)
for an exact specification of the [schema](#schema) DSL.

## Usage

The `graph-guard*` libraries are accessible
via [Maven Central](https://search.maven.org/search?q=g:io.github.c-fraser%20AND%20a:graph-guard*)
and the `graph-guard-cli` application is published in
the [releases](https://github.com/c-fraser/graph-guard/releases).

> `graph-guard` requires Java 17+.

> `Server` doesn't currently support TLS (because
> of [ktor-network](https://youtrack.jetbrains.com/issue/KTOR-694) limitations).
> Use [NGINX](https://docs.nginx.com/nginx/admin-guide/security-controls/terminating-ssl-tcp/) or a
> cloud load balancer to decrypt *Bolt* traffic for the proxy server.

### Examples

Refer to the snippets below to see how to initialize and run a `Server` with the `graph-guard`
library.

#### Kotlin

<!--- INCLUDE
import io.github.cfraser.graphguard.Server
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
-->

[//]: # (@formatter:off)
```kotlin
/** [Server.run] `this` [Server] in a [thread] then execute the [block]. */
fun Server.use(wait: Duration = 1.seconds, block: () -> Unit) {
  val server = thread(block = ::run) // run the server until the thread is interrupted
  Thread.sleep(wait.toLong(DurationUnit.MILLISECONDS)) // wait for the server to start in separate thread
  try {
    block() // execute a function interacting with the server
  } finally {
    server.interrupt() // interrupt the thread running the server to initiate a graceful shutdown
  }
}
```
[//]: # (@formatter:on)
<!--- KNIT Example07.kt -->

<!--- INCLUDE
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.withNeo4j

@Suppress("unused")
fun runExample08() {
  withNeo4j {
----- SUFFIX
  }
}
-->

[//]: # (@formatter:off)
```kotlin
Server(
  boltURI(),
  plugin { // define plugin using DSL
    intercept { _, message -> message.also(::println) }
    observe { event -> println(event) }
  })
  .use { TODO("interact with the running server") }
```
[//]: # (@formatter:on)
<!--- KNIT Example08.kt -->

#### Java

[//]: # (@formatter:off)
```java
Server.Plugin plugin = // implement async plugin; can't utilize Kotlin coroutines plugin interface in Java
    new Server.Plugin.Async() {
      @NotNull
      @Override
      public CompletableFuture<Message> interceptAsync(
              @NotNull String session, @NotNull Bolt.Message message) {
        return CompletableFuture.supplyAsync(
                () -> {
                  System.out.println(message);
                  return message;
                });
      }

      @NotNull
      @Override
      public CompletableFuture<Void> observeAsync(@NotNull Server.Event event) {
        return CompletableFuture.supplyAsync(
                () -> {
                  System.out.println(event);
                  return null;
                });
      }
    };
Thread server = new Thread(new Server(boltURI(), plugin));
server.start(); // run the server until the thread is interrupted
Thread.sleep(1_000); // wait for the server to start in separate thread
/* TODO: interact with the running server */
server.interrupt(); // interrupt the thread running the server to initiate a graceful shutdown
```
[//]: # (@formatter:on)

### Documentation

Refer to the [graph-guard website](https://c-fraser.github.io/graph-guard/api/).

### CLI

Download and run the `graph-guard-cli` application.

```shell
curl -OL https://github.com/c-fraser/graph-guard/releases/latest/download/graph-guard-cli.tar
mkdir graph-guard-cli
tar -xvf graph-guard-cli.tar --strip-components=1 -C graph-guard-cli
./graph-guard-cli/bin/graph-guard-cli --help
```

> Refer to the [demo](https://c-fraser.github.io/graph-guard/cli/)
> (and [source script](https://github.com/c-fraser/graph-guard/blob/main/graph-guard-cli/demo.sh)).

## Libraries

The following `graph-guard-*` libraries provide types and/or implementations to facilitate
augmenting [Server](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/index.html)
functionality.

### graph-guard-validate

`graph-guard-validate`
defines [Rule](https://c-fraser.github.io/graph-guard/api/graph-guard-validate/io.github.cfraser.graphguard.validate/-rule/index.html),
which is the basis for query validation, through
the [Validator](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard.plugin/-validator/index.html)
plugin. [Schema](https://c-fraser.github.io/graph-guard/api/graph-guard-validate/io.github.cfraser.graphguard.validate/-schema/index.html)
is
the [Rule](https://c-fraser.github.io/graph-guard/api/graph-guard-validate/io.github.cfraser.graphguard.validate/-rule/index.html)
that evaluates whether a Cypher query contains [schema](#schema) violations. Refer
to [Patterns](https://c-fraser.github.io/graph-guard/api/graph-guard-validate/io.github.cfraser.graphguard.validate/-patterns/index.html)
for additional
Cypher [Rule](https://c-fraser.github.io/graph-guard/api/graph-guard-validate/io.github.cfraser.graphguard.validate/-rule/index.html)
implementations.

### graph-guard-script

[Script.evaluate](https://c-fraser.github.io/graph-guard/api/graph-guard-script/io.github.cfraser.graphguard.plugin/-script/-companion/evaluate.html)
enables [plugins](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/-plugin/index.html)
to be compiled and loaded from
a [Kotlin script](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).
The [Script.Context](https://c-fraser.github.io/graph-guard/api/graph-guard-script/io.github.cfraser.graphguard.plugin/-script/-context/index.html)
exposes
a [DSL](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/-plugin/-d-s-l/index.html)
to
build [plugins](https://c-fraser.github.io/graph-guard/api/graph-guard/io.github.cfraser.graphguard/-server/-plugin/index.html).

For example, use a [plugin script](#graph-guard-script) with the [Server](#design).

<!--- TEST_NAME Example09Test --> 
<!--- INCLUDE
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.driver
import io.github.cfraser.graphguard.plugin.Script
import io.github.cfraser.graphguard.runMoviesQueries
import io.github.cfraser.graphguard.withNeo4j
import kotlin.time.Duration.Companion.seconds

fun runExample09() {
  withNeo4j {
----- SUFFIX
  }
}
-->

[//]: # (@formatter:off)
```kotlin
val script = """
@file:DependsOn(
    "io.github.resilience4j:resilience4j-ratelimiter:2.2.0",
    "io.github.resilience4j:resilience4j-kotlin:2.2.0")

import io.github.resilience4j.kotlin.ratelimiter.RateLimiterConfig
import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import java.util.concurrent.atomic.AtomicInteger

plugin {
  val rateLimiter = RateLimiter.of("message-limiter", RateLimiterConfig {})
  intercept { _, message -> rateLimiter.executeSuspendFunction { message } }
}

plugin { 
  val messages = AtomicInteger()
  intercept { _, message ->
    if (messages.getAndIncrement() == 0) println(message::class.simpleName)
    message
  }
}
"""
val plugin = Script.evaluate(script)
val server = Server(boltURI(), plugin)
server.use(wait = 10.seconds) { server.driver.use(block = ::runMoviesQueries) }
```
[//]: # (@formatter:on)

> Script compilation and evaluation takes longer, thus the 10 second `wait`.

<!--- KNIT Example09.kt --> 

The code above prints the following message.

```text
Hello
```

<!--- TEST -->

## License

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
