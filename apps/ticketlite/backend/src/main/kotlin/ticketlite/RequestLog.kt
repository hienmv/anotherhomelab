package ticketlite

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import net.logstash.logback.argument.StructuredArguments.kv
import net.logstash.logback.argument.StructuredArguments.v
import org.slf4j.LoggerFactory

/** Requests slower than this are logged at WARN (degraded, but still working). */
const val SLOW_REQUEST_MS = 1_000L

private val httpLog = LoggerFactory.getLogger("ticketlite.http")
private val startedAt = AttributeKey<Long>("ticketlite.startedAt")

/** Log level for one finished request (rule in logback.xml): 5xx ERROR, slow WARN, else INFO. */
fun requestLevel(status: Int, durationMs: Long): String = when {
    status >= 500 -> "ERROR"
    durationMs >= SLOW_REQUEST_MS -> "WARN"
    else -> "INFO"
}

/**
 * One structured log line per /api request, with the fields you filter on in Grafana:
 * http.request.method, url.path, http.response.status_code, duration_ms, request_id (+ trace_id, span_id
 * from the OpenTelemetry agent). Replaces Ktor's CallLogging (plain text, always INFO).
 * 4xx (SOLD_OUT, UNAUTHORIZED, ...) are INFO: the client got a correct answer, the service is fine.
 */
val RequestLog = createApplicationPlugin("RequestLog") {
    onCall { call -> call.attributes.put(startedAt, System.nanoTime()) }
    on(ResponseSent) { call ->
        val path = call.request.path()
        if (!path.startsWith("/api")) return@on // probes and /metrics: no noise
        val status = call.response.status()?.value ?: 0
        val ms = (System.nanoTime() - (call.attributes.getOrNull(startedAt) ?: System.nanoTime())) / 1_000_000
        val method = call.request.httpMethod.value
        // v(): the value appears in the message AND as a JSON field; kv() without a {}: field only
        val args = arrayOf<Any>(
            // OpenTelemetry semantic-convention names (same names as in the traces)
            v("http.request.method", method), v("url.path", path), v("http.response.status_code", status), v("duration_ms", ms),
            kv("request_id", call.callId ?: "-"),
        )
        val msg = "{} {} -> {} in {} ms"
        when (requestLevel(status, ms)) {
            "ERROR" -> httpLog.error(msg, *args)
            "WARN" -> httpLog.warn(msg, *args)
            else -> httpLog.info(msg, *args)
        }
    }
}
