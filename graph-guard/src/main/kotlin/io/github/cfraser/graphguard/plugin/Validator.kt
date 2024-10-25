package io.github.cfraser.graphguard.plugin

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.github.cfraser.graphguard.Bolt
import io.github.cfraser.graphguard.Server
import io.github.cfraser.graphguard.validate.Rule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * [Validator] is a [Server.Plugin] that validates the [Bolt.Run.query] and [Bolt.Run.parameters] in
 * a [Bolt.Run] message. If the data in the [Bolt.Message] is invalid, according to the [rule], then
 * a [Bolt.Failure] and [Bolt.Ignored] is returned upon the next [Bolt.Pull].
 *
 * @param rule the [Rule] to validate
 * @param cacheSize the maximum entries in the cache of validated queries
 */
class Validator @JvmOverloads constructor(private val rule: Rule, cacheSize: Long? = null) :
    Server.Plugin {

  /** A [Mutex] for writing [failures]. */
  private val lock = Mutex()

  /**
   * A [MutableMap] storing a [Bolt.Failure] for a [Bolt.Session].
   * > The [Bolt.Failure] isn't returned immediately after the [Bolt.Run], but rather upon the
   * > subsequent [Bolt.Pull].
   */
  private val failures: MutableMap<Bolt.Session, Bolt.Failure> = mutableMapOf()

  /** A [LoadingCache] of validated *Cypher* queries. */
  private val cache =
      Caffeine.newBuilder().maximumSize(cacheSize ?: 1024).build<
          Pair<String, Map<String, Any?>>, Rule.Violation?> { (query, parameters) ->
        rule.validate(query, parameters)
      }

  override suspend fun intercept(session: Bolt.Session, message: Bolt.Message): Bolt.Message {
    when (message) {
      is Bolt.Run -> {
        val violation = cache[message.query to message.parameters] ?: return message
        LOGGER.info("Cypher query '{}' is invalid: {}", message.query, violation.message)
        val failure =
            Bolt.Failure(
                mapOf("code" to "GraphGuard.Invalid.Query", "message" to violation.message))
        lock.withLock { failures[session] = failure }
        return Bolt.Messages(emptyList())
      }
      is Bolt.Pull -> {
        val failure = failures[session] ?: return message
        failures -= session
        return failure and Bolt.Ignored
      }
      else -> return message
    }
  }

  override suspend fun observe(event: Server.Event) {}

  private companion object {

    val LOGGER = LoggerFactory.getLogger(Validator::class.java)!!
  }
}
