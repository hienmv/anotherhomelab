package ticketlite

import io.ktor.server.sessions.defaultSessionSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/** docs/design/2026-11-25-read-routing.md */
class ReadRouterTest {
    @Test
    fun `LSNs compare as numbers, not as text`() {
        assertTrue(lsnValue("0/A0") > lsnValue("0/9F"))
        assertTrue(lsnValue("1/0") > lsnValue("0/FFFFFFFF")) // high part wins
        assertTrue(lsnValue("16/B374D848") > lsnValue("9/FFFFFFFF")) // "16" < "9" as text
    }

    @Test
    fun `the token only moves forward (monotonic reads)`() {
        val ctx = ReadCtx(null, loggedIn = true)
        ctx.advanceTo("0/200")
        assertEquals("0/200", ctx.token)
        ctx.advanceTo("0/100") // an older position never replaces a newer one
        assertEquals("0/200", ctx.token)
        ctx.advanceTo(null)
        assertEquals("0/200", ctx.token)
        ctx.advanceTo("1/0")
        assertEquals("1/0", ctx.token)
    }

    @Test
    fun `a logged-in user without a token is unknown, an anonymous one is not`() {
        // a session from before D1, or a purchase handled by an old pod during the rollout
        assertTrue(ReadCtx(null, loggedIn = true).unknown)
        assertFalse(ReadCtx("0/10", loggedIn = true).unknown)
        assertFalse(ReadCtx.anonymous().unknown) // never wrote anything: eventual is correct
        val ctx = ReadCtx(null, loggedIn = true)
        ctx.advanceTo("0/10") // after the first read on the primary
        assertFalse(ctx.unknown)
    }

    @Test
    fun `routing decisions`() {
        val fresh = ReplicaState(caughtUp = true, lagSeconds = 0.2)
        assertEquals("no_replica", readDecision(hasReplica = false, breakerOpen = false, state = fresh, maxLagSeconds = 10.0))
        assertEquals("replica_down", readDecision(true, breakerOpen = true, state = fresh, maxLagSeconds = 10.0))
        assertEquals("replica_down", readDecision(true, false, state = null, maxLagSeconds = 10.0))
        assertEquals("lagging", readDecision(true, false, ReplicaState(caughtUp = true, lagSeconds = 12.0), 10.0))
        assertEquals("not_caught_up", readDecision(true, false, ReplicaState(caughtUp = false, lagSeconds = 0.5), 10.0))
        assertEquals("ok", readDecision(true, false, fresh, 10.0))
    }

    @Test
    fun `the read replica uses the primary's database and credentials on another host`() {
        val primary = parseDatabaseUrl("postgresql://app:s3cr%40t@pg-rw.data.svc.cluster.local:5432/app?sslmode=require")
        val ro = readReplicaConfig(primary, "pg-ro.data.svc.cluster.local")
        assertEquals("jdbc:postgresql://pg-ro.data.svc.cluster.local:5432/app?sslmode=require", ro.jdbcUrl)
        assertEquals(primary.user, ro.user)
        assertEquals(primary.password, ro.password)
        assertEquals("jdbc:postgresql://replica:6432/app?sslmode=require", readReplicaConfig(primary, "replica:6432").jdbcUrl)
    }

    @Test
    fun `read routing is off unless DATABASE_READ_HOST is set`() {
        assertNull(AppConfig.fromEnv(mapOf("SESSION_SECRET" to "00")).dbRead)
        val cfg = AppConfig.fromEnv(mapOf("SESSION_SECRET" to "00", "DATABASE_READ_HOST" to "pg-ro"))
        assertEquals("jdbc:postgresql://pg-ro:5432/app", cfg.dbRead?.jdbcUrl)
    }

    /** The session cookie as pods before D1 know it. */
    @Serializable
    data class OldSession(val userId: Long, val username: String, val displayName: String)

    @Test
    fun `the session cookie format is unchanged, so old and new pods read each other's sessions`() {
        // Ktor's session decoder REJECTS unknown keys: a new field in UserSession would log users out
        // whenever a request hits a pod of the other version during a rolling update or a rollback.
        val new = defaultSessionSerializer<UserSession>().serialize(UserSession(1, "alice", "Alice"))
        assertEquals(OldSession(1, "alice", "Alice"), defaultSessionSerializer<OldSession>().deserialize(new))
        val old = defaultSessionSerializer<OldSession>().serialize(OldSession(1, "alice", "Alice"))
        assertEquals(UserSession(1, "alice", "Alice"), defaultSessionSerializer<UserSession>().deserialize(old))
        // ...and that is a real risk, not a theory (seen 2026-11-26 with a minLsn field):
        val withExtraKey = defaultSessionSerializer<ReadTokenAndUser>().serialize(ReadTokenAndUser(1, "alice", "Alice", "0/1"))
        assertTrue(runCatching { defaultSessionSerializer<UserSession>().deserialize(withExtraKey) }.isFailure)
    }

    @Serializable
    data class ReadTokenAndUser(val userId: Long, val username: String, val displayName: String, val minLsn: String)
}
