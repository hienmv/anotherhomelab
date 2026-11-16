package ticketlite

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.argument.StructuredArguments.kv
import net.logstash.logback.argument.StructuredArguments.v
import org.slf4j.LoggerFactory

/** Log queries in Grafana filter on these exact field names; a rename must fail the build. */
class LoggingTest {
    @Test
    fun `log lines are JSON with the fields the log queries use`() {
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        @Suppress("UNCHECKED_CAST")
        val appender = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("out")
            as OutputStreamAppender<ILoggingEvent>
        val event = LoggingEvent(
            "test", ctx.getLogger("ticketlite.http"), Level.INFO, "{} {} -> {} in {} ms", null,
            arrayOf(
                v("http.request.method", "GET"), v("url.path", "/api/concerts"), v("http.response.status_code", 200),
                v("duration_ms", 13L), kv("request_id", "abc-1"),
            ),
        )
        val line = String(appender.encoder.encode(event)).trim()
        assertTrue('\n' !in line, "one JSON object per line")
        val json = Json.parseToJsonElement(line).jsonObject

        assertEquals("INFO", json["level"]!!.jsonPrimitive.content)
        assertEquals("ticketlite.http", json["logger"]!!.jsonPrimitive.content)
        assertEquals("GET /api/concerts -> 200 in 13 ms", json["message"]!!.jsonPrimitive.content)
        assertEquals("ticketlite", json["service.namespace"]!!.jsonPrimitive.content)
        assertEquals("abc-1", json["request_id"]!!.jsonPrimitive.content)
        assertEquals(200, json["http.response.status_code"]!!.jsonPrimitive.int)
        assertEquals(13, json["duration_ms"]!!.jsonPrimitive.int)
        assertTrue(json["time"]!!.jsonPrimitive.content.endsWith("Z"), "time in UTC, RFC 3339")
        listOf("service.name", "url.path", "http.request.method").forEach { assertTrue(it in json, "missing field $it") }
    }

    @Test
    fun `level follows the homelab rule`() {
        assertEquals("INFO", requestLevel(200, 13))
        assertEquals("INFO", requestLevel(409, 13))   // SOLD_OUT: a correct answer, not a fault
        assertEquals("WARN", requestLevel(200, SLOW_REQUEST_MS))
        assertEquals("ERROR", requestLevel(503, 13))
    }
}
