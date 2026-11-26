package ticketlite

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The request-scoped token (ReadConsistency.kt): routes never load or save it themselves. */
class ReadConsistencyTest {
    @Test
    fun `call readCtx comes from the session and a moved token goes into the cookie`() = testApplication {
        application {
            install(Sessions) { cookie<UserSession>("TL_SESSION"); cookie<ReadToken>("TL_RYW") }
            routing {
                // logged in, no token yet (session from before D1, or a purchase on an old pod)
                get("/login-old") { call.sessions.set(UserSession(1, "alice", "Alice")); call.respondText("ok") }
                get("/state") { val c = call.readCtx; call.respondText("loggedIn=${c.loggedIn} unknown=${c.unknown} token=${c.token}") }
                // what ReadRouter does after a primary read or a write
                get("/read-primary") { call.readCtx.advanceTo("0/3000060"); call.respondText("ok") }
                get("/read-replica") { call.readCtx; call.respondText("ok") } // token unchanged
                get("/logout") { val c = call.readCtx; call.sessions.clear<UserSession>(); c.advanceTo("0/4000000"); call.respondText("ok") }
            }
        }
        val client = createClient { install(HttpCookies) }

        assertEquals("loggedIn=false unknown=false token=null", client.get("/state").bodyAsText()) // anonymous

        client.get("/login-old")
        assertEquals("loggedIn=true unknown=true token=null", client.get("/state").bodyAsText()) // → primary first

        val moved = client.get("/read-primary")
        val setCookies = moved.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertTrue(setCookies.any { it.startsWith("TL_RYW=") && "minLsn" in it }, "token saved in its own cookie")
        assertTrue(setCookies.none { it.startsWith("TL_SESSION=") }, "the session cookie is not rewritten")
        assertEquals("loggedIn=true unknown=false token=0/3000060", client.get("/state").bodyAsText())

        // unchanged token: no new cookie on every read
        assertNull(client.get("/read-replica").headers[HttpHeaders.SetCookie])

        // logged out: anonymous again (the token cookie stays; it only ever makes reads stricter)
        client.get("/logout")
        assertEquals("loggedIn=false unknown=false token=null", client.get("/state").bodyAsText())
        client.get("/login-old")
        assertEquals("loggedIn=true unknown=false token=0/4000000", client.get("/state").bodyAsText())
    }
}
