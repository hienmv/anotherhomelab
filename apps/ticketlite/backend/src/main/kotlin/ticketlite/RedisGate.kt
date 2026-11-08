package ticketlite

import java.net.URI
import java.util.UUID
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.params.XAddParams

/**
 * Fast admission gate for ticket sales, in front of Postgres.
 *
 * `inventory:<concertId>` holds tickets that can still be reserved. A Lua script checks and
 * decrements it atomically, so a rush on a hot concert is answered in Redis without touching
 * the database. Postgres stays the source of truth: the worker re-checks under a row lock,
 * so even a wrong gate value can never oversell.
 */
class RedisGate(redisUrl: String) {
    val jedis = JedisPooled(URI(redisUrl))

    fun reserve(concertId: Long, quantity: Int, loadAvailable: () -> Int): Boolean {
        repeat(2) {
            val result = jedis.eval(RESERVE_LUA, listOf(key(concertId)), listOf(quantity.toString())) as Long
            when {
                result >= 0 -> return true
                result == SOLD_OUT -> return false
                else -> jedis.set(key(concertId), loadAvailable().toString(), SetParams.setParams().nx())
            }
        }
        return false
    }

    fun release(concertId: Long, quantity: Int) {
        jedis.incrBy(key(concertId), quantity.toLong())
    }

    fun available(concertId: Long): Int? = jedis.get(key(concertId))?.toIntOrNull()

    fun enqueue(orderId: UUID) {
        jedis.xadd(STREAM, XAddParams.xAddParams().maxLen(10_000).approximateTrimming(), mapOf("orderId" to orderId.toString()))
    }

    companion object {
        const val STREAM = "orders"
        const val GROUP = "workers"
        private const val MISSING = -1L
        private const val SOLD_OUT = -2L

        fun key(concertId: Long) = "inventory:$concertId"

        private val RESERVE_LUA = """
            local v = redis.call('GET', KEYS[1])
            if not v then return $MISSING end
            if tonumber(v) < tonumber(ARGV[1]) then return $SOLD_OUT end
            return redis.call('DECRBY', KEYS[1], ARGV[1])
        """.trimIndent()
    }
}
