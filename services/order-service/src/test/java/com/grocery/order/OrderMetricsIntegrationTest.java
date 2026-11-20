package com.grocery.order;

import com.grocery.order.entity.StuckReason;
import com.grocery.order.service.OrderMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13 §1: order-service's own meters. {@code grocery_orders_stuck{reason}} and {@code grocery_returns_open{status}}
 * follow the database on each refresh (the background refresh runs at start only here:
 * {@code grocery.order.metrics.refresh-interval=1h}); {@code grocery_orders_placement_seconds{outcome}} times every
 * placement. The database is shared by all test classes, so the gauges are compared with the counts read here.
 */
class OrderMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry registry;
    @Autowired
    private OrderMetrics metrics;

    private double gauge(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).gauge().value();
    }

    private long placements(String outcome) {
        return registry.get(OrderMetrics.PLACEMENT).tag("outcome", outcome).timer().count();
    }

    private long openFlags(StuckReason reason) {
        return jdbc().sql("""
                        SELECT COUNT(*) FROM stuck_order s JOIN customer_order o ON o.id = s.order_id
                        WHERE s.resolved_at IS NULL AND o.status = s.status AND s.reason = ?""")
                .param(reason.name()).query(Long.class).single();
    }

    private long returns(String status) {
        return jdbc().sql("SELECT COUNT(*) FROM order_return WHERE status = ?").param(status).query(Long.class).single();
    }

    @Test
    void everySeriesIsRegisteredFromTheStart() {
        assertThat(registry.find(OrderMetrics.STUCK).gauges()).map(g -> g.getId().getTag("reason"))
                .containsExactlyInAnyOrder(Arrays.stream(StuckReason.values()).map(Enum::name).toArray(String[]::new));
        assertThat(registry.find(OrderMetrics.RETURNS_OPEN).gauges()).map(g -> g.getId().getTag("status"))
                .containsExactlyInAnyOrder("REQUESTED", "ESCALATED");
        assertThat(registry.find(OrderMetrics.PLACEMENT).timers()).map(t -> t.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("PLACED", "REPLAYED", "REJECTED", "UNAVAILABLE", "FAILED");
        assertThat(registry.find(OrderMetrics.STUCK).meters()).map(Meter::getId)
                .allSatisfy(id -> assertThat(id.getType()).isEqualTo(Meter.Type.GAUGE));
    }

    @Test
    void theStuckOrdersGaugeFollowsTheOpenFlags() throws Exception {
        StuckReason reason = StuckReason.STORE_RESPONSE_OVERDUE;
        watchdog.run();
        metrics.refresh();
        double before = gauge(OrderMetrics.STUCK, "reason", reason.name());

        UUID confirmed = confirmedOrder(UUID.randomUUID());
        movedBack(confirmed, "confirmed_at", "CONFIRMED",
                Instant.now().minus(16, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));
        watchdog.run();
        assertThat(stuckFlags(confirmed)).containsKey("CONFIRMED");
        assertThat(gauge(OrderMetrics.STUCK, "reason", reason.name()))
                .as("a scrape never queries: the value changes on the next refresh").isEqualTo(before);

        metrics.refresh();
        double flagged = gauge(OrderMetrics.STUCK, "reason", reason.name());
        assertThat(flagged).isEqualTo(openFlags(reason)).isGreaterThanOrEqualTo(before + 1);

        jdbc().sql("UPDATE stuck_order SET resolved_at = UTC_TIMESTAMP(6) WHERE order_id = UUID_TO_BIN(?)")
                .param(confirmed.toString()).update();
        metrics.refresh();
        assertThat(gauge(OrderMetrics.STUCK, "reason", reason.name())).isEqualTo(openFlags(reason))
                .isLessThan(flagged);
    }

    @Test
    void theOpenReturnsGaugeCountsRequestedAndEscalatedReturns() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        metrics.refresh();
        double requestedBefore = gauge(OrderMetrics.RETURNS_OPEN, "status", "REQUESTED");

        JsonNode requested = body(requestReturn(customerId, orderId, Map.of(itemIds(customerId, orderId).getFirst(), 1),
                "DAMAGED", null).andExpect(status().isCreated()));
        metrics.refresh();
        assertThat(gauge(OrderMetrics.RETURNS_OPEN, "status", "REQUESTED")).isEqualTo(returns("REQUESTED"))
                .isGreaterThanOrEqualTo(requestedBefore + 1);
        double escalatedBefore = gauge(OrderMetrics.RETURNS_OPEN, "status", "ESCALATED");

        jdbc().sql("UPDATE order_return SET decide_by = ? WHERE id = UUID_TO_BIN(?)")
                .param(Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(requested.get("id").asString()).update();
        assertThat(escalation.escalateDue()).isPositive();
        metrics.refresh();
        assertThat(gauge(OrderMetrics.RETURNS_OPEN, "status", "ESCALATED")).isEqualTo(returns("ESCALATED"))
                .isGreaterThanOrEqualTo(escalatedBefore + 1);
        assertThat(gauge(OrderMetrics.RETURNS_OPEN, "status", "REQUESTED")).isEqualTo(returns("REQUESTED"));
    }

    @Test
    void everyPlacementIsTimedWithItsOutcome() throws Exception {
        long placed = placements("PLACED");
        long replayed = placements("REPLAYED");
        long rejected = placements("REJECTED");
        long unavailable = placements("UNAVAILABLE");

        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String key = newKey();
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isCreated());
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isCreated());                  // the retry
        place(customerId, newKey(), new BigDecimal("1.00")).andExpect(status().isConflict());  // TOTAL_CHANGED
        CART.down = true;
        place(customerId, newKey(), GROCERY_TOTAL).andExpect(status().isServiceUnavailable());
        CART.down = false;

        assertThat(placements("PLACED")).isEqualTo(placed + 1);
        assertThat(placements("REPLAYED")).isEqualTo(replayed + 1);
        assertThat(placements("REJECTED")).isEqualTo(rejected + 1);
        assertThat(placements("UNAVAILABLE")).isEqualTo(unavailable + 1);
        assertThat(registry.get(OrderMetrics.PLACEMENT).tag("outcome", "PLACED").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
    }
}
