package ticketlite

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XAutoClaimParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry

private val log = LoggerFactory.getLogger("ticketlite.worker")

/** Result of processing one order, used to give back Redis gate capacity on failure. */
private data class Outcome(val concertId: Long, val quantity: Int, val status: String, val reason: String?)

/**
 * Consumes order ids from the Redis stream `orders` (consumer group `workers`) and confirms them
 * in Postgres. Several workers can run in parallel: each message goes to one consumer, and the
 * concert row lock (`for update`) serialises purchases of the same concert.
 */
class Worker(private val cfg: AppConfig, private val db: Db, private val gate: RedisGate) {
    private val running = AtomicBoolean(true)
    private val consumer = System.getenv("HOSTNAME") ?: "worker-${UUID.randomUUID()}"

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread { running.set(false) })
        ensureGroup()
        log.info("worker {} started", consumer)
        var lastClaim = 0L
        while (running.get()) {
            try {
                if (System.currentTimeMillis() - lastClaim > 30_000) {
                    reclaimStale()
                    lastClaim = System.currentTimeMillis()
                }
                val batches = gate.jedis.xreadGroup(
                    RedisGate.GROUP, consumer,
                    XReadGroupParams.xReadGroupParams().count(10).block(5_000),
                    mapOf(RedisGate.STREAM to StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY),
                ) ?: continue
                for (batch in batches) batch.value.forEach(::handle)
            } catch (e: Exception) {
                if (e is JedisDataException && e.message?.contains("NOGROUP") == true) ensureGroup()
                log.error("worker loop error, retrying in 2s", e)
                Thread.sleep(2_000)
            }
        }
        log.info("worker {} stopped", consumer)
    }

    private fun ensureGroup() {
        try {
            gate.jedis.xgroupCreate(RedisGate.STREAM, RedisGate.GROUP, StreamEntryID("0-0"), true)
        } catch (e: JedisDataException) {
            if (e.message?.contains("BUSYGROUP") != true) throw e
        }
    }

    /** Takes over messages another (crashed) worker read but never acknowledged. */
    private fun reclaimStale() {
        val claimed = gate.jedis.xautoclaim(
            RedisGate.STREAM, RedisGate.GROUP, consumer, 60_000, StreamEntryID("0-0"),
            XAutoClaimParams.xAutoClaimParams().count(50),
        )
        claimed.value.forEach(::handle)
    }

    private fun handle(entry: StreamEntry) {
        val orderId = entry.fields["orderId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (orderId != null) {
            if (cfg.workerDelayMs > 0) Thread.sleep(cfg.workerDelayMs) // simulated payment step
            val outcome = process(orderId)
            if (outcome != null) {
                if (outcome.status == OrderStatus.FAILED) gate.release(outcome.concertId, outcome.quantity)
                log.info("order {} -> {}{}", orderId, outcome.status, outcome.reason?.let { " ($it)" } ?: "")
            }
        }
        gate.jedis.xack(RedisGate.STREAM, RedisGate.GROUP, entry.id)
    }

    /** Confirms or fails one order atomically. Returns null if it was already processed. */
    private fun process(orderId: UUID): Outcome? = db.tx { c ->
        val order = c.queryOne(
            "select user_id, concert_id, quantity, status from orders where id = ? for update", orderId,
        ) { Triple(it.getLong(1), it.getLong(2), it.getInt(3)) to it.getString(4) }
            ?: return@tx null
        val (ids, status) = order
        val (userId, concertId, quantity) = ids
        if (status != OrderStatus.PENDING) return@tx null

        // Row lock on the concert: purchases of the same concert run one at a time here.
        val (available, hot) = c.queryOne(
            "select available, hot from concerts where id = ? for update", concertId,
        ) { it.getInt(1) to it.getBoolean(2) }!!
        val confirmed = c.queryOne(
            "select coalesce(sum(quantity), 0) from orders where user_id = ? and concert_id = ? and status = ?",
            userId, concertId, OrderStatus.CONFIRMED,
        ) { it.getInt(1) }!!

        val reason = when {
            confirmed + quantity > maxPerUser(hot) -> "LIMIT"
            available < quantity -> "SOLD_OUT"
            else -> null
        }
        if (reason == null) {
            c.update("update concerts set available = available - ? where id = ?", quantity, concertId)
            c.update("update orders set status = ?, updated_at = now() where id = ?", OrderStatus.CONFIRMED, orderId)
            Outcome(concertId, quantity, OrderStatus.CONFIRMED, null)
        } else {
            c.update("update orders set status = ?, reason = ?, updated_at = now() where id = ?", OrderStatus.FAILED, reason, orderId)
            Outcome(concertId, quantity, OrderStatus.FAILED, reason)
        }
    }
}

fun runWorker(cfg: AppConfig, db: Db, gate: RedisGate) {
    // Small HTTP server only for Kubernetes probes.
    embeddedServer(CIO, port = cfg.port) {
        routing { get("/healthz") { call.respondText("ok") } }
    }.start(wait = false)
    Worker(cfg, db, gate).run()
}
