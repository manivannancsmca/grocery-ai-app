package com.grocery.pricing;

import com.grocery.pricing.event.OrderEventsListener;
import com.grocery.pricing.service.CouponMetrics;
import com.grocery.pricing.service.CouponRedemptionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13 §1: {@code grocery_coupon_redemptions_total{outcome}} — RESERVED by redeem (not again for a retry),
 * REFUSED when redeem answers 409, CONFIRMED by OrderCreated (not again for a duplicate), RELEASED by OrderCancelled
 * and by the sweeper. Counted after the transaction commits. The sweeper only runs when a test calls it.
 */
class CouponMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;
    @Autowired
    OrderEventsListener orderEvents;
    @Autowired
    CouponRedemptionService redemptions;

    private final UUID storeId = UUID.randomUUID();

    private double count(CouponMetrics.Outcome outcome) {
        return registry.get(CouponMetrics.REDEMPTIONS).tag("outcome", outcome.name()).counter().count();
    }

    private String coupon(String fields) throws Exception {
        String code = code("mt");
        adminCoupon("\"code\":\"%s\",\"name\":\"Metrics\",%s,%s".formatted(code, fields, validWindow()));
        return code;
    }

    private void backdate(UUID orderId, int minutes) {
        jdbc().sql("""
                        UPDATE coupon_redemption SET reserved_at = TIMESTAMPADD(MINUTE, ?, UTC_TIMESTAMP(6))
                        WHERE order_id = UUID_TO_BIN(?)""")
                .param(-minutes).param(orderId.toString()).update();
    }

    @Test
    void everyOutcomeIsRegisteredFromTheStart() {
        assertThat(registry.find(CouponMetrics.REDEMPTIONS).counters()).map(c -> c.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("RESERVED", "CONFIRMED", "RELEASED", "REFUSED");
    }

    @Test
    void theRedemptionLifecycleIsCounted() throws Exception {
        double reserved = count(CouponMetrics.Outcome.RESERVED);
        double refused = count(CouponMetrics.Outcome.REFUSED);
        double confirmed = count(CouponMetrics.Outcome.CONFIRMED);
        double released = count(CouponMetrics.Outcome.RELEASED);

        String code = coupon("\"discountType\":\"FLAT\",\"value\":40,\"minOrderSubtotal\":100");
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String body = redemption(code, orderId, customerId, storeId, "GROCERY", "200.00", "25.00");
        redeem(customerId, body).andExpect(status().isOk());
        redeem(customerId, body).andExpect(status().isOk());                       // a retry: the same use
        assertThat(count(CouponMetrics.Outcome.RESERVED)).isEqualTo(reserved + 1);

        // refused: below the minimum order, and another coupon for the same order
        redeem(customerId, redemption(code, UUID.randomUUID(), customerId, storeId, "GROCERY", "50.00", "25.00"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"));
        String other = coupon("\"discountType\":\"FREE_DELIVERY\"");
        redeem(customerId, redemption(other, orderId, customerId, storeId, "GROCERY", "200.00", "25.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_REDEMPTION_CONFLICT"));
        assertThat(count(CouponMetrics.Outcome.REFUSED)).isEqualTo(refused + 2);
        assertThat(count(CouponMetrics.Outcome.RESERVED)).isEqualTo(reserved + 1);

        // OrderCreated confirms once (a redelivery is a duplicate), OrderCancelled releases
        String created = UUID.randomUUID().toString();
        orderEvents.on(orderEvent("OrderCreated", created, orderId));
        orderEvents.on(orderEvent("OrderCreated", created, orderId));
        assertThat(count(CouponMetrics.Outcome.CONFIRMED)).isEqualTo(confirmed + 1);
        orderEvents.on(orderEvent("OrderCancelled", UUID.randomUUID().toString(), orderId));
        assertThat(redemptionStatus(orderId)).isEqualTo("RELEASED");
        assertThat(count(CouponMetrics.Outcome.RELEASED)).isEqualTo(released + 1);

        // the sweeper: a reservation without OrderCreated for longer than the TTL
        UUID abandoned = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        redeem(buyer, redemption(code, abandoned, buyer, storeId, "GROCERY", "200.00", "25.00"))
                .andExpect(status().isOk());
        backdate(abandoned, 31);
        double beforeSweep = count(CouponMetrics.Outcome.RELEASED);
        int swept = redemptions.releaseExpired();
        assertThat(swept).isPositive();
        assertThat(redemptionStatus(abandoned)).isEqualTo("RELEASED");
        assertThat(count(CouponMetrics.Outcome.RELEASED)).isEqualTo(beforeSweep + swept);
    }
}
