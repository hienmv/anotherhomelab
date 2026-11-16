package ticketlite

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * Tracing helpers (docs/learn/10-tracing.md). The OpenTelemetry Java agent traces HTTP, SQL and Redis
 * calls by itself; this file only does what it cannot know: carry the trace through the Redis queue
 * (api -> worker) and name the worker's own steps.
 *
 * Without the agent (unit tests, OTEL_JAVAAGENT_ENABLED=false) every call here is a cheap no-op.
 */
object Tracing {
    private val tracer get() = GlobalOpenTelemetry.getTracer("ticketlite")
    private val propagator get() = GlobalOpenTelemetry.getPropagators().textMapPropagator

    private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier!![key] = value }
    private val getter = object : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>) = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
    }

    /** The current trace as message fields (W3C `traceparent`, maybe `tracestate`), for the queue. */
    fun injectCurrent(): Map<String, String> = mutableMapOf<String, String>().also {
        propagator.inject(Context.current(), it, setter)
    }

    /**
     * Runs [block] in a span. With [parentFields] (a queue message) the span continues the trace that
     * produced the message, so api and worker appear as ONE trace. Exceptions mark the span as error.
     */
    fun <T> inSpan(
        name: String,
        kind: SpanKind = SpanKind.INTERNAL,
        parentFields: Map<String, String>? = null,
        attributes: Map<String, String> = emptyMap(),
        block: (Span) -> T,
    ): T {
        val parent = parentFields?.let { propagator.extract(Context.root(), it, getter) } ?: Context.current()
        val span = tracer.spanBuilder(name).setParent(parent).setSpanKind(kind).startSpan()
        attributes.forEach { (k, v) -> span.setAttribute(k, v) }
        return try {
            span.makeCurrent().use { block(span) }
        } catch (e: Throwable) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }
}
