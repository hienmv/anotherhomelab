package ticketlite

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import redis.clients.jedis.exceptions.JedisConnectionException

/**
 * "A dependency is briefly unavailable" vs "a bug" (docs/design/2026-11-24-graceful-db-outage.md).
 *
 * During a Postgres failover or restart (~30-75 s) or a Redis restart, requests fail with these
 * errors. They are answered 503 + Retry-After (try again soon) instead of 500 (something is broken),
 * so the web app can retry reads by itself and tell the user what is going on.
 */
const val RETRY_AFTER_SECONDS = 5

/** Postgres SQLSTATEs that mean "the server is not usable right now", not "your query is wrong". */
private val UNAVAILABLE_SQLSTATES = setOf(
    "57P01", // admin_shutdown: the server is stopping (restart, switchover)
    "57P02", // crash_shutdown
    "57P03", // cannot_connect_now: starting up / shutting down / in recovery
    "25006", // read_only_sql_transaction: a demoted primary (now a replica) got the write
)

/** The unavailable dependency ("postgres" or "redis"), or null if [e] is not an outage error. */
fun unavailableDependency(e: Throwable): String? {
    var cur: Throwable? = e
    val seen = HashSet<Throwable>()
    while (cur != null && seen.add(cur)) {
        when (cur) {
            // Hikari: no connection within connectionTimeout (the database does not answer)
            is SQLTransientConnectionException -> return "postgres"
            is SQLException -> {
                val state = cur.sqlState ?: ""
                // class 08 = connection exception (refused, broken, lost)
                if (state.startsWith("08") || state in UNAVAILABLE_SQLSTATES) return "postgres"
            }
            is JedisConnectionException -> return "redis"
        }
        cur = cur.cause
    }
    return null
}
