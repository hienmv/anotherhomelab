package ticketlite

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/**
 * Business metrics, exposed at GET /metrics (Prometheus text format, scraped by VictoriaMetrics).
 * /metrics is not routed by the public Ingress (only /api and / are), so it stays in-cluster.
 *
 *   ticketlite_order_requests_total{result}       api: accepted | sold_out | limit | invalid
 *   ticketlite_orders_processed_total{status,reason} worker: CONFIRMED | FAILED (+ reason)
 *   ticketlite_tickets_sold_total                 worker: confirmed ticket count
 *   ticketlite_order_process_seconds              worker: time to confirm/fail one order
 *   ticketlite_inventory_available{concert}       api: Redis gate per concert
 *   ticketlite_queue_lag / _pending               worker: stream backlog / unacked messages
 */
object Metrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private val processTimer: Timer = Timer.builder("ticketlite.order.process")
        .description("Time for a worker to confirm or fail one order")
        .publishPercentileHistogram()
        .maximumExpectedValue(Duration.ofSeconds(10))
        .register(registry)

    init {
        // Create every counter at 0 up front. A series that first appears with value N has no
        // "before" sample, so increase()/rate() treat its first N events as zero (seen 2026-11-09:
        // dashboard showed 8 accepted orders instead of 18).
        listOf("accepted", "sold_out", "limit", "invalid").forEach {
            registry.counter("ticketlite.order.requests", "result", it)
        }
        listOf(OrderStatus.CONFIRMED to "none", OrderStatus.FAILED to "SOLD_OUT", OrderStatus.FAILED to "LIMIT").forEach { (status, reason) ->
            registry.counter("ticketlite.orders.processed", "status", status, "reason", reason)
        }
        registry.counter("ticketlite.tickets.sold")
    }

    fun bindJvm() {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
    }

    fun orderRequest(result: String) =
        registry.counter("ticketlite.order.requests", "result", result).increment()

    fun orderProcessed(status: String, reason: String?, quantity: Int, durationNanos: Long) {
        registry.counter("ticketlite.orders.processed", "status", status, "reason", reason ?: "none").increment()
        if (status == OrderStatus.CONFIRMED) registry.counter("ticketlite.tickets.sold").increment(quantity.toDouble())
        processTimer.record(Duration.ofNanos(durationNanos))
    }

    fun gauge(name: String, vararg tags: String, value: () -> Number?) {
        Gauge.builder(name) { value()?.toDouble() ?: Double.NaN }.tags(*tags).register(registry)
    }

    fun scrape(): String = registry.scrape()
}
