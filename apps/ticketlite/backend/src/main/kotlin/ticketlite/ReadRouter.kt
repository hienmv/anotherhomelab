package ticketlite

import java.sql.Connection

/**
 * Sends reads to the replica when that is correct, else to the primary
 * (docs/design/2026-11-25-read-routing.md).
 *
 * Read-your-writes + monotonic reads with an LSN token: the LSN (log sequence number) is the
 * position in Postgres' write-ahead log. After a user's write, and after any read of theirs served by
 * the primary, the token is moved to the primary's current LSN. A replica may serve that user only
 * once it has replayed past the token: they always see their own writes and never older data.
 */
class ReadCtx(token: String?, val loggedIn: Boolean, private val onAdvance: (String) -> Unit = {}) {
    /** The newest LSN this user must see. */
    var token: String? = token
        private set

    /**
     * Logged in but no token: a session from before D1, or a write handled by an old api pod during a
     * rolling deploy. What this user has seen is UNKNOWN (not "nothing"), so the primary answers
     * first and sets the token; later reads may use the replica.
     */
    val unknown: Boolean get() = loggedIn && token == null

    companion object {
        /** Not logged in: never wrote anything, so eventual reads are correct. */
        fun anonymous() = ReadCtx(null, loggedIn = false)
    }

    fun advanceTo(lsn: String?) {
        if (lsn != null && (token == null || lsnValue(lsn) > lsnValue(token!!))) {
            token = lsn
            onAdvance(lsn) // e.g. store it in the session cookie (ReadConsistency.kt)
        }
    }
}

/** "16/B374D848" -> comparable number (high 32 bits / low 32 bits, hex). */
fun lsnValue(lsn: String): Long {
    val (hi, lo) = lsn.split("/", limit = 2)
    return (hi.toLong(16) shl 32) or lo.toLong(16)
}

/** What the replica reports about itself (one cheap query). */
data class ReplicaState(val caughtUp: Boolean, val lagSeconds: Double)

/**
 * Where a read goes, once the replica has answered its state: the label of
 * ticketlite_db_reads_total{reason}. ("no_token" is decided before asking the replica: [ReadRouter.read].)
 */
fun readDecision(hasReplica: Boolean, breakerOpen: Boolean, state: ReplicaState?, maxLagSeconds: Double): String = when {
    !hasReplica -> "no_replica"
    breakerOpen || state == null -> "replica_down"
    state.lagSeconds > maxLagSeconds -> "lagging"
    !state.caughtUp -> "not_caught_up"
    else -> "ok"
}

class ReadRouter(
    private val primary: Db,
    private val replica: Db?,
    private val maxLagSeconds: Double = 10.0,
    private val breakerMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var replicaSkipUntil = 0L

    /** A read that may use the replica: eventual (anonymous) or read-your-writes + monotonic (logged in). */
    fun <T> read(ctx: ReadCtx, block: (Connection) -> T): T {
        val r = replica ?: return onPrimary(ctx, "no_replica", block)
        if (ctx.unknown) return onPrimary(ctx, "no_token", block)
        if (now() < replicaSkipUntil) return onPrimary(ctx, "replica_down", block)
        val reason = try {
            r.ds.connection.use { c ->
                val state = c.queryOne(STATE_SQL, ctx.token ?: "0/0") {
                    ReplicaState(it.getBoolean(1), it.getDouble(2))
                }
                val decision = readDecision(true, false, state, maxLagSeconds)
                if (decision == "ok") {
                    val value = block(c)
                    Metrics.dbRead("replica", "ok")
                    return value
                }
                decision
            }
        } catch (e: Exception) {
            // the replica is down or died mid-query: a read is safe to repeat on the primary
            if (unavailableDependency(e) == null) throw e
            replicaSkipUntil = now() + breakerMs
            "replica_down"
        }
        return onPrimary(ctx, reason, block)
    }

    /** Part of a write decision (stock, limits): must be the latest value. */
    fun <T> linearizable(block: (Connection) -> T): T = primary.read(block).also { Metrics.dbRead("primary", "write_path") }

    /** A write; afterwards the user's token covers it (read-your-writes). */
    fun <T> write(ctx: ReadCtx, block: (Connection) -> T): T =
        primary.tx { c -> block(c) }.also { if (ctx.loggedIn && replica != null) primary.read { c -> ctx.advanceTo(currentLsn(c)) } }

    private fun <T> onPrimary(ctx: ReadCtx, reason: String, block: (Connection) -> T): T = primary.read { c ->
        val value = block(c)
        // monotonic reads: the user saw the primary's state, so a later replica read must be at least this new
        if (ctx.loggedIn && replica != null) ctx.advanceTo(currentLsn(c))
        Metrics.dbRead("primary", reason)
        value
    }

    private fun currentLsn(c: Connection): String? = c.queryOne("select pg_current_wal_lsn()::text") { it.getString(1) }

    companion object {
        /**
         * On the replica: has it replayed past the token, and how far behind is it?
         * Lag is 0 when it replayed everything it received (an idle primary is not "lag");
         * not in recovery = it is a primary (e.g. right after a promotion), always current.
         */
        private const val STATE_SQL = """
            select not pg_is_in_recovery() or pg_last_wal_replay_lsn() >= ?::pg_lsn,
                   case when not pg_is_in_recovery() or pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() then 0
                        else coalesce(extract(epoch from now() - pg_last_xact_replay_timestamp()), 0) end::float8
        """
    }
}
