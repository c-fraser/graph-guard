package io.github.cfraser.graphguard.plugin

import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.MoviesGraph
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.knit.MOVIES_SCHEMA
import io.github.cfraser.graphguard.withNeo4j
import io.github.cfraser.graphguard.withServer
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeOneOf
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates.notNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ValidatorTest : FunSpec() {

  init {
    test("observe server events") {
      val lock = Mutex()
      val clientAddresses = mutableListOf<InetSocketAddress>()
      val clientConnections by lazy { clientAddresses.map { Server.Connection.Client(it) } }
      var graphAddress by notNull<InetSocketAddress>()
      val graphConnection by lazy { Server.Connection.Graph(graphAddress) }
      val events = mutableListOf<Server.Event>()
      val observer = plugin {
        observe { event ->
          lock.withLock {
            when (event) {
              is Server.Connected ->
                  when (event.connection) {
                    is Server.Connection.Client -> clientAddresses += event.connection.address
                    is Server.Connection.Graph -> graphAddress = event.connection.address
                  }
              else -> {}
            }
            events += event
          }
        }
      }
      withNeo4j {
        withServer(plugin = Validator(Schema(MOVIES_SCHEMA)) then observer) { driver ->
          driver.session().use { session ->
            session.run(MoviesGraph.MATCH_TOM_HANKS).list().shouldBeEmpty()
            session.runCatching { run("MATCH (n:N) RETURN n") }
          }
        }
      }
      lock.isLocked shouldBe false
      events.forExactly(1) { it shouldBe Server.Started }
      events.filterIsInstance<Server.Connected>() shouldContainInOrder
          clientConnections.flatMap {
            listOf(Server.Connected(it), Server.Connected(graphConnection))
          }
      val ran = AtomicInteger()
      events.filterIsInstance<Server.Proxied>().forEach { event ->
        when (val message = event.received) {
          is Bolt.Hello,
          is Bolt.Goodbye,
          is Bolt.Logon,
          is Bolt.Reset -> {
            event.source.address shouldBeOneOf clientConnections.map { it.address }
            event.destination.address shouldBe graphConnection.address
            message shouldBe event.sent
          }
          is Bolt.Success -> {
            event.source.address shouldBe graphConnection.address
            event.destination.address shouldBeOneOf clientConnections.map { it.address }
            message shouldBe event.sent
          }
          is Bolt.Run ->
              if (message.query.contains("Tom Hanks")) {
                ran.getAndIncrement() shouldBe 0
                event.source.address shouldBeOneOf clientConnections.map { it.address }
                event.destination.address shouldBe graphConnection.address
                message shouldBe event.sent
              } else {
                ran.getAndIncrement() shouldBe 1
                event.source.address shouldBe event.destination.address
                event.sent.shouldBeTypeOf<Bolt.Messages>()
                (event.sent as Bolt.Messages).messages.map { it::class } shouldBe emptyList()
              }
          is Bolt.Pull ->
              if (ran.get() == 2) {
                event.source.address shouldBe event.destination.address
                event.sent.shouldBeTypeOf<Bolt.Messages>()
                (event.sent as Bolt.Messages).messages.map { it::class } shouldBe
                    listOf(Bolt.Failure::class, Bolt.Ignored::class)
              }
          else -> fail("Received unexpected '$message'")
        }
      }
      events.filterIsInstance<Server.Disconnected>() shouldContainAll
          clientConnections.flatMap {
            listOf(Server.Disconnected(graphConnection), Server.Disconnected(it))
          }
      events.forExactly(1) { it shouldBe Server.Stopped }
    }
  }
}
