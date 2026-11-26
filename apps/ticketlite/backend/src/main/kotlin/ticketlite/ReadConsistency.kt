package ticketlite

import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.util.AttributeKey

/**
 * Request-scoped read consistency (docs/design/2026-11-25-read-routing.md, "Request context").
 *
 * Routes never load or save the LSN token themselves. `call.readCtx` is the request's [ReadCtx]:
 *   - made from the session on first use (anonymous = no session)
 *   - the token comes from its own cookie TL_RYW (ReadToken in Models.kt: why not in the session)
 *   - when the router moves the token, it is written at once, so the response's Set-Cookie carries
 *     it (also for error answers)
 * A new route that reads user data passes `call.readCtx` to the Repo (the parameter is required, so
 * the compiler does not let it be forgotten) and gets read-your-writes without further code.
 *
 * (First try was a plugin saving the token in onCallRespond: too late, the Sessions plugin had
 * already prepared the cookie; caught by ReadConsistencyTest.)
 */
private val readCtxKey = AttributeKey<ReadCtx>("ticketlite.readCtx")

val ApplicationCall.readCtx: ReadCtx
    get() = attributes.computeIfAbsent(readCtxKey) {
        if (sessions.get<UserSession>() == null) return@computeIfAbsent ReadCtx.anonymous()
        // the token cookie (TL_RYW) is per browser and outlives logins: logging in again (or as someone
        // else) can only make reads stricter, never let a write be missed
        ReadCtx(sessions.get<ReadToken>()?.minLsn, loggedIn = true) { lsn -> sessions.set(ReadToken(lsn)) }
    }
