package ticketlite

import java.net.URI
import java.util.UUID
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.params.XAddParams

enum class Reservation { OK, SOLD_OUT, LIMIT }

/**
 * Fast admission gate for ticket sales, in front of Postgres.
 *
 * `inventory:<concertId>`      tickets that can still be reserved
 * `hold:<userId>:<concertId>`  tickets a user holds or is waiting for (pending + confirmed)
 *
 * One Lua script checks the per-person limit AND the inventory and reserves both atomically, so
 * simultaneous requests from the same user cannot all pass the limit check (they could when the
 * limit was read from Postgres first: 18 of 60 parallel requests were accepted for a limit of 6).
 * Postgres stays the source of truth: the worker re-checks under a row lock, so even a wrong
 * gate value can never oversell. Missing keys (e.g. after a Redis restart) are reloaded from
 * Postgres with SET NX.
 */
class RedisGate(redisUrl: String) {
    val jedis = JedisPooled(URI(redisUrl))

    fun reserve(
        concertId: Long,
        userId: Long,
        quantity: Int,
        limit: Int,
        loadAvailable: () -> Int,
        loadHeld: () -> Int,
    ): Reservation {
        repeat(3) {
            val keys = listOf(inventoryKey(concertId), holdKey(userId, concertId))
            val result = jedis.eval(RESERVE_LUA, keys, listOf(quantity.toString(), limit.toString())) as Long
            when (result) {
                MISSING_INVENTORY -> jedis.set(keys[0], loadAvailable().toString(), SetParams.setParams().nx())
                MISSING_HOLD -> jedis.set(keys[1], loadHeld().toString(), SetParams.setParams().nx())
                SOLD_OUT -> return Reservation.SOLD_OUT
                OVER_LIMIT -> return Reservation.LIMIT
                else -> return Reservation.OK
            }
        }
        return Reservation.SOLD_OUT // keys could not be initialised; fail closed
    }

    /** Gives back inventory and the user's hold when an order fails. */
    fun release(concertId: Long, userId: Long, quantity: Int) {
        jedis.eval(RELEASE_LUA, listOf(inventoryKey(concertId), holdKey(userId, concertId)), listOf(quantity.toString()))
    }

    /**
     * Overwrites the inventory gate with Postgres' number. Called when the worker finds a concert
     * sold out although Redis let the order through: the gate was wrong (e.g. stale after a restore),
     * so correct it instead of failing every following buyer the slow way.
     */
    fun resyncInventory(concertId: Long, available: Int) {
        jedis.set(inventoryKey(concertId), available.toString())
    }

    fun available(concertId: Long): Int? = jedis.get(inventoryKey(concertId))?.toIntOrNull()

    fun enqueue(orderId: UUID) {
        jedis.xadd(STREAM, XAddParams.xAddParams().maxLen(10_000).approximateTrimming(), mapOf("orderId" to orderId.toString()))
    }

    companion object {
        const val STREAM = "orders"
        const val GROUP = "workers"
        private const val MISSING_INVENTORY = -1L
        private const val SOLD_OUT = -2L
        private const val MISSING_HOLD = -3L
        private const val OVER_LIMIT = -4L

        fun inventoryKey(concertId: Long) = "inventory:$concertId"
        fun holdKey(userId: Long, concertId: Long) = "hold:$userId:$concertId"

        // KEYS[1]=inventory, KEYS[2]=hold, ARGV[1]=quantity, ARGV[2]=per-person limit
        private val RESERVE_LUA = """
            local inv = redis.call('GET', KEYS[1])
            if not inv then return $MISSING_INVENTORY end
            local held = redis.call('GET', KEYS[2])
            if not held then return $MISSING_HOLD end
            local qty = tonumber(ARGV[1])
            if tonumber(held) + qty > tonumber(ARGV[2]) then return $OVER_LIMIT end
            if tonumber(inv) < qty then return $SOLD_OUT end
            redis.call('INCRBY', KEYS[2], qty)
            return redis.call('DECRBY', KEYS[1], qty)
        """.trimIndent()

        // Never let the hold go negative (e.g. if the key was reloaded in between).
        private val RELEASE_LUA = """
            redis.call('INCRBY', KEYS[1], ARGV[1])
            local held = redis.call('DECRBY', KEYS[2], ARGV[1])
            if held < 0 then redis.call('SET', KEYS[2], 0) end
            return 1
        """.trimIndent()
    }
}
