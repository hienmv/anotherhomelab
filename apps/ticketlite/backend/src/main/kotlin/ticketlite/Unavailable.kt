package ticketlite

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException

/**
 * "A dependency is briefly unavailable" vs "a bug" (docs/design/2026-11-24-graceful-db-outage.md).
 *
 * During a Postgres failover or restart (~30-75 s) or a Redis restart, requests fail with these
 * errors. They are answered 503 + Retry-After (try again soon) instead of 500 (something is broken),
 * so the web app can retry reads by itself and tell the user what is going on.
 */
const val RETRY_AFTER_SECONDS = 5

/**
 * Postgres SQLSTATEs that mean "the server is not usable right now", not "your query is wrong".
 *
 * SQLSTATE is a fixed 5-character error code (SQL standard), not message text: the first 2 characters
 * are the class (08 = connection exception, 25 = invalid transaction state, 57 = operator
 * intervention), a "P" marks a PostgreSQL-specific code. The list is PostgreSQL's "Appendix A. Error
 * Codes" (https://www.postgresql.org/docs/17/errcodes-appendix.html), which recommends testing these
 * codes rather than messages: they stay the same across releases and are not translated.
 */
private val UNAVAILABLE_SQLSTATES = setOf(
    "57P01", // admin_shutdown: the server is stopping (restart, switchover)
    "57P02", // crash_shutdown
    "57P03", // cannot_connect_now: starting up / shutting down / in recovery
    "25006", // read_only_sql_transaction: a demoted primary (now a replica) got the write
    "53300", // too_many_connections: all server slots taken, frees up in seconds
)

/**
 * Class 08 (connection exception) means "the connection is gone", EXCEPT 08P01 protocol_violation:
 * client and server disagree about the protocol, i.e. a driver bug. Retrying does not fix that.
 */
private fun isConnectionLost(state: String) = state.startsWith("08") && state != "08P01"

/**
 * Redis error replies start with an upper-case code word (protocol convention, like SQLSTATE):
 *   LOADING  Redis is reading its data from disk after a restart (seconds)
 *   READONLY the node is a replica now (after a Redis failover, roadmap A2), like 25006
 */
private val UNAVAILABLE_REDIS_REPLIES = listOf("LOADING ", "READONLY ")

/**
 * The unavailable dependency ("postgres" or "redis"), or null if [e] is not an outage error.
 *
 * Deliberately a short ALLOW-list: everything else stays 500. Calling a bug "briefly unavailable"
 * hides it from the error logs' meaning and makes clients retry something that will never work.
 * Decided NOT to be outages (docs/design/2026-11-24-graceful-db-outage.md, "Other errors"):
 * wrong password / missing database (28xxx, 3D000: a config error, not brief), disk full (53100:
 * needs a person), statement timeout (57014) and serialization failure / deadlock (40001, 40P01:
 * a retry belongs inside the transaction code, not in the browser), and every query error.
 */
fun unavailableDependency(e: Throwable): String? {
    var cur: Throwable? = e
    val seen = HashSet<Throwable>()
    while (cur != null && seen.add(cur)) {
        when (cur) {
            // Hikari: no connection within connectionTimeout (the database does not answer)
            is SQLTransientConnectionException -> return "postgres"
            is SQLException -> {
                val state = cur.sqlState ?: ""
                if (isConnectionLost(state) || state in UNAVAILABLE_SQLSTATES) return "postgres"
            }
            is JedisConnectionException -> return "redis"
            is JedisDataException ->
                if (UNAVAILABLE_REDIS_REPLIES.any { cur.message.orEmpty().startsWith(it) }) return "redis"
        }
        cur = cur.cause
    }
    return null
}
