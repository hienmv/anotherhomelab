package ticketlite

import kotlin.test.Test
import kotlin.test.assertTrue

/** The Grafana dashboard queries these exact names; a rename must fail the build. */
class MetricsTest {
    @Test
    fun `exports the metric names the dashboard uses`() {
        Metrics.orderRequest("accepted")
        Metrics.orderProcessed(OrderStatus.CONFIRMED, null, quantity = 2, durationNanos = 1_000_000)
        Metrics.orderProcessed(OrderStatus.FAILED, "SOLD_OUT", quantity = 1, durationNanos = 1_000_000)
        Metrics.gauge("ticketlite.inventory.available", "concert", "Test", "hot", "true") { 5 }

        val text = Metrics.scrape()
        listOf(
            """ticketlite_order_requests_total{result="accepted"}""",
            """ticketlite_orders_processed_total{reason="none",status="CONFIRMED"}""",
            """ticketlite_orders_processed_total{reason="SOLD_OUT",status="FAILED"}""",
            "ticketlite_tickets_sold_total 2.0",
            "ticketlite_order_process_seconds_bucket",
            """ticketlite_inventory_available{concert="Test",hot="true"} 5.0""",
        ).forEach { assertTrue(it in text, "missing in /metrics: $it") }
    }
}
