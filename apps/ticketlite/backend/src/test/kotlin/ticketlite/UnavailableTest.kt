package ticketlite

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException

/** A Postgres failover / Redis restart is answered 503 + Retry-After; real bugs stay 500. */
class UnavailableTest {
    @Test
    fun `classifies outage errors by dependency`() {
        // what Hikari throws when no connection comes within connectionTimeout
        assertEquals("postgres", unavailableDependency(SQLTransientConnectionException("ticketlite - Connection is not available")))
        // the Postgres driver: connection refused / broken (class 08), server shutting down, demoted primary
        assertEquals("postgres", unavailableDependency(SQLException("refused", "08001")))
        assertEquals("postgres", unavailableDependency(SQLException("I/O error", "08006")))
        assertEquals("postgres", unavailableDependency(SQLException("terminating connection", "57P01")))
        assertEquals("postgres", unavailableDependency(SQLException("the database system is starting up", "57P03")))
        assertEquals("postgres", unavailableDependency(SQLException("read-only transaction", "25006")))
        assertEquals("postgres", unavailableDependency(SQLException("sorry, too many clients already", "53300")))
        assertEquals("redis", unavailableDependency(JedisConnectionException(ConnectException("refused"))))
        assertEquals("redis", unavailableDependency(JedisDataException("LOADING Redis is loading the dataset in memory")))
        assertEquals("redis", unavailableDependency(JedisDataException("READONLY You can't write against a read only replica.")))
        // found through the cause chain
        assertEquals("postgres", unavailableDependency(RuntimeException("wrapped", SQLException("x", "08003"))))
    }

    @Test
    fun `bugs are not outages`() {
        assertNull(unavailableDependency(SQLException("syntax error", "42601")))
        assertNull(unavailableDependency(SQLException("duplicate key", "23505")))
        assertNull(unavailableDependency(SQLException("no state")))
        // considered and rejected (see Unavailable.kt): not brief, or not fixed by a browser retry
        listOf("08P01", "28P01", "3D000", "53100", "57014", "40001", "40P01", "XX000").forEach {
            assertNull(unavailableDependency(SQLException("x", it)), it)
        }
        assertNull(unavailableDependency(JedisDataException("WRONGTYPE Operation against a key holding the wrong kind of value")))
        assertNull(unavailableDependency(JedisDataException("OOM command not allowed when used memory > 'maxmemory'")))
        assertNull(unavailableDependency(IllegalStateException("bug")))
    }

    @Test
    fun `answers 503 with Retry-After for an outage and 500 for a bug`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installErrorPages()
            routing {
                get("/db-down") { throw SQLTransientConnectionException("Connection is not available") }
                get("/bug") { throw IllegalStateException("bug") }
            }
        }
        val down = client.get("/db-down")
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals(RETRY_AFTER_SECONDS.toString(), down.headers[HttpHeaders.RetryAfter])
        assertTrue("UNAVAILABLE" in down.bodyAsText())

        val bug = client.get("/bug")
        assertEquals(HttpStatusCode.InternalServerError, bug.status)
        assertNull(bug.headers[HttpHeaders.RetryAfter])
        assertTrue("INTERNAL" in bug.bodyAsText())

        assertTrue("""ticketlite_dependency_unavailable_total{dependency="postgres"} 1.0""" in Metrics.scrape())
    }
}
