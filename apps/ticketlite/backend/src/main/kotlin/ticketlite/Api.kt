package ticketlite

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.util.UUID
import net.logstash.logback.argument.StructuredArguments.kv
import net.logstash.logback.argument.StructuredArguments.v
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ticketlite.api")

fun runApi(cfg: AppConfig, repo: Repo, gate: RedisGate) {
    // One gauge per concert: tickets still reservable (Redis gate, falls back to Postgres).
    for (c in repo.concerts(ReadCtx.anonymous())) {
        Metrics.gauge("ticketlite.inventory.available", "concert", c.title, "hot", c.hot.toString()) {
            gate.available(c.id) ?: repo.availableForMetrics(c.id)
        }
    }
    embeddedServer(CIO, port = cfg.port) { apiModule(cfg, repo, gate) }.start(wait = true)
}

fun Application.apiModule(cfg: AppConfig, repo: Repo, gate: RedisGate) {
    install(XForwardedHeaders)
    install(MicrometerMetrics) {
        registry = Metrics.registry
        distributionStatisticConfig = DistributionStatisticConfig.Builder().percentilesHistogram(true).build()
    }
    install(ContentNegotiation) { json() }
    // Request id: one id per request, in every log line it causes (api AND worker), and sent back as
    // X-Request-ID. Taken from the caller if it sent one, else Cloudflare's CF-Ray (also shown in
    // Cloudflare's dashboard), else a new random one.
    install(CallId) {
        retrieveFromHeader("X-Request-ID")
        retrieveFromHeader("CF-Ray")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() && it.length <= 128 && it.all { ch -> ch.isLetterOrDigit() || ch in "-_." } }
        replyToHeader("X-Request-ID")
    }
    install(RequestLog)
    installErrorPages()
    install(Sessions) {
        cookie<UserSession>("TL_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = cfg.cookieSecure
            cookie.maxAgeInSeconds = 8 * 3600
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(cfg.sessionSecret))
        }
        // read-your-writes token, separate from the session on purpose (Models.kt ReadToken); signed too
        cookie<ReadToken>("TL_RYW") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = cfg.cookieSecure
            cookie.maxAgeInSeconds = 8 * 3600
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(cfg.sessionSecret))
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        get("/metrics") { call.respondText(Metrics.scrape()) }

        route("/api") {
            post("/auth/login") {
                val req = call.receive<LoginRequest>()
                val user = repo.findUser(req.username.trim().lowercase())
                if (user == null || !verifyPassword(req.password, user.passwordHash)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("INVALID_CREDENTIALS"))
                    return@post
                }
                call.sessions.set(UserSession(user.id, user.username, user.displayName))
                call.respond(UserDto(user.id, user.username, user.displayName))
            }

            post("/auth/logout") {
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.NoContent)
            }

            get("/auth/me") {
                val s = call.requireUser() ?: return@get
                call.respond(UserDto(s.userId, s.username, s.displayName))
            }

            get("/concerts") {
                // Show what can still be reserved right now (the Redis gate) when known, plus how many
                // tickets the logged-in user already holds so the UI can explain a disabled Buy button.
                val held = call.sessions.get<UserSession>()?.let { repo.userHeldByConcert(it.userId, call.readCtx) }
                call.respond(repo.concerts(call.readCtx).map { c ->
                    c.copy(available = gate.available(c.id) ?: c.available, userHeld = held?.let { it[c.id] ?: 0 })
                })
            }

            post("/orders") {
                val s = call.requireUser() ?: return@post
                val req = call.receive<OrderRequest>()
                val concert = repo.concert(req.concertId)
                if (concert == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("CONCERT_NOT_FOUND"))
                    return@post
                }
                val limit = maxPerUser(concert.hot)
                if (req.quantity !in 1..limit) {
                    Metrics.orderRequest("invalid")
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("INVALID_QUANTITY", "1..$limit tickets"))
                    return@post
                }
                // Atomic in Redis: per-person limit + inventory. The worker re-checks both in Postgres.
                val reservation = gate.reserve(
                    concert.id, s.userId, req.quantity, limit,
                    loadAvailable = { repo.available(concert.id) },
                    loadHeld = { repo.userHeld(s.userId, concert.id) },
                )
                when (reservation) {
                    Reservation.LIMIT -> {
                        Metrics.orderRequest("limit")
                        call.respond(HttpStatusCode.Conflict, ErrorDto("LIMIT", "max $limit per person"))
                        return@post
                    }
                    Reservation.SOLD_OUT -> {
                        Metrics.orderRequest("sold_out")
                        call.respond(HttpStatusCode.Conflict, ErrorDto("SOLD_OUT"))
                        return@post
                    }
                    Reservation.OK -> Unit
                }
                val id = UUID.randomUUID()
                try {
                    repo.insertPending(id, s.userId, concert.id, req.quantity, call.readCtx)
                    gate.enqueue(id, call.callId)
                } catch (e: Exception) {
                    gate.release(concert.id, s.userId, req.quantity)
                    runCatching { repo.markFailed(id, "ENQUEUE_FAILED", call.readCtx) }
                    throw e
                }
                Metrics.orderRequest("accepted")
                call.respond(HttpStatusCode.Accepted, repo.order(id, s.userId, call.readCtx)!!)
            }

            get("/orders") {
                val s = call.requireUser() ?: return@get
                call.respond(repo.orders(s.userId, call.readCtx))
            }

            get("/orders/{id}") {
                val s = call.requireUser() ?: return@get
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                val order = id?.let { repo.order(it, s.userId, call.readCtx) }
                if (order == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("ORDER_NOT_FOUND"))
                } else {
                    call.respond(order)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireUser(): UserSession? {
    val s = sessions.get<UserSession>()
    if (s == null) respond(HttpStatusCode.Unauthorized, ErrorDto("UNAUTHORIZED"))
    return s
}

/** Error answers: 400 bad input, 503 + Retry-After when Postgres/Redis is briefly down, 500 for bugs. */
fun Application.installErrorPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("BAD_REQUEST"))
        }
        exception<Throwable> { call, e ->
            val dependency = unavailableDependency(e)
            if (dependency != null) {
                // a failover/restart, not a bug: 503 + Retry-After; no stack trace (it is not our code)
                Metrics.dependencyUnavailable(dependency)
                log.error("{} unavailable on {}: {}", v("dependency", dependency), call.request.path(), e.toString(),
                    kv("request_id", call.callId ?: "-"))
                call.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS.toString())
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("UNAVAILABLE"))
                return@exception
            }
            log.error("unhandled error on {}", call.request.path(), kv("request_id", call.callId ?: "-"), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("INTERNAL"))
        }
    }
}
