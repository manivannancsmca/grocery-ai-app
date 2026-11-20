package com.grocery.pricing;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.pricing.event.OrderEventsListener;
import com.grocery.pricing.service.CouponRedemptionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The redemption lifecycle (Phase 12 §2): CONFIRMED on OrderCreated, RELEASED on OrderCancelled / OrderRejected
 * (order.events fed to the listener as Kafka would; duplicates ignored), and the sweeper releasing reservations
 * that never got an OrderCreated.
 */
class CouponLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OrderEventsListener orderEvents;
    @Autowired
    CouponRedemptionService redemptions;

    private final UUID storeId = UUID.randomUUID();

    private String coupon(String fields) throws Exception {
        String code = code("lc");
        adminCoupon("\"code\":\"%s\",\"name\":\"Lifecycle\",%s,%s".formatted(code, fields, validWindow()));
        return code;
    }

    /** Redeems {@code code} for a new order of a new customer and returns the order id. */
    private UUID reserved(String code) throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        redeem(customerId, redemption(code, orderId, customerId, storeId, "GROCERY", "200.00", "25.00"))
                .andExpect(status().isOk());
        return orderId;
    }

    private void deliver(String eventType, String eventId, UUID orderId) {
        orderEvents.on(orderEvent(eventType, eventId, orderId));
    }

    private long processed(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
                .param(eventId).param(CouponRedemptionService.CONSUMER).query(Long.class).single();
    }

    private void backdate(UUID orderId, int minutes) {
        jdbc().sql("""
                        UPDATE coupon_redemption SET reserved_at = TIMESTAMPADD(MINUTE, ?, UTC_TIMESTAMP(6))
                        WHERE order_id = UUID_TO_BIN(?)""")
                .param(-minutes).param(orderId.toString()).update();
    }

    private String releaseReason(UUID orderId) {
        return jdbc().sql("SELECT release_reason FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).optional().orElse(null);
    }

    @Test
    void orderCreatedConfirmsAndDuplicatesAreIgnored() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":40");
        UUID orderId = reserved(code);
        String eventId = UUID.randomUUID().toString();

        deliver("OrderCreated", eventId, orderId);
        assertThat(redemptionStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(processed(eventId)).isEqualTo(1);
        String confirmedAt = jdbc().sql("SELECT CAST(confirmed_at AS CHAR) FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single();

        deliver("OrderCreated", eventId, orderId);                         // redelivery: nothing changes
        deliver("OrderCreated", UUID.randomUUID().toString(), orderId);    // another copy: already confirmed
        assertThat(redemptionStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(jdbc().sql("SELECT CAST(confirmed_at AS CHAR) FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single()).isEqualTo(confirmedAt);

        // the admin view now shows the discount given, and the usage the order (number from the event)
        mvc.perform(get("/api/admin/coupons").param("q", code).with(opsAdmin()))
                .andExpect(jsonPath("$.content[0].usedCount").value(1))
                .andExpect(jsonPath("$.content[0].discountGiven").value(40.00));
        String couponId = body(mvc.perform(get("/api/admin/coupons").param("q", code).with(opsAdmin())))
                .get("content").get(0).get("id").asString();
        mvc.perform(get("/api/admin/coupons/{id}/usage", couponId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(couponId))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.usedCount").value(1))
                .andExpect(jsonPath("$.confirmedCount").value(1))
                .andExpect(jsonPath("$.reservedCount").value(0))
                .andExpect(jsonPath("$.discountGiven").value(40.00))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.redemptions.length()").value(1))
                .andExpect(jsonPath("$.redemptions[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.redemptions[0].orderNumber")
                        .value("GA-" + orderId.toString().substring(0, 8).toUpperCase()))
                .andExpect(jsonPath("$.redemptions[0].discount").value(40.00))
                .andExpect(jsonPath("$.redemptions[0].deliveryFeeDiscount").value(0.00))
                .andExpect(jsonPath("$.redemptions[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.redemptions[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.redemptions[0].confirmedAt").isNotEmpty());
        mvc.perform(get("/api/admin/coupons/{id}/usage", couponId).param("status", "RELEASED").with(opsAdmin()))
                .andExpect(jsonPath("$.redemptions.length()").value(0))
                .andExpect(jsonPath("$.usedCount").value(1));
    }

    @Test
    void cancellingAnOrderFreesTheUse() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":40,\"totalUsageLimit\":1,\"perCustomerLimit\":3");
        UUID orderId = reserved(code);
        deliver("OrderCreated", UUID.randomUUID().toString(), orderId);
        UUID late = UUID.randomUUID();
        redeem(late, redemption(code, UUID.randomUUID(), late, storeId, "GROCERY", "200", "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("COUPON_USAGE_LIMIT"));

        String cancelled = UUID.randomUUID().toString();
        deliver("OrderCancelled", cancelled, orderId);
        assertThat(redemptionStatus(orderId)).isEqualTo("RELEASED");
        assertThat(releaseReason(orderId)).isEqualTo("ORDER_CANCELLED");
        deliver("OrderCancelled", cancelled, orderId);                     // duplicate
        deliver("OrderCreated", UUID.randomUUID().toString(), orderId);    // a stray replay never revives it
        assertThat(redemptionStatus(orderId)).isEqualTo("RELEASED");
        assertThat(releaseReason(orderId)).isEqualTo("ORDER_CANCELLED");

        // the use is free again
        redeem(late, redemption(code, UUID.randomUUID(), late, storeId, "GROCERY", "200", "25"))
                .andExpect(status().isOk());
        // and the cancelled order's own redeem cannot come back
        UUID owner = UUID.fromString(jdbc().sql(
                        "SELECT BIN_TO_UUID(customer_id) FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single());
        redeem(owner, redemption(code, orderId, owner, storeId, "GROCERY", "200", "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_REDEMPTION_CONFLICT"));
    }

    @Test
    void aRejectedOrderReleasesItsReservation() throws Exception {
        String code = coupon("\"discountType\":\"FREE_DELIVERY\"");
        UUID orderId = reserved(code);
        deliver("OrderRejected", UUID.randomUUID().toString(), orderId);
        assertThat(redemptionStatus(orderId)).isEqualTo("RELEASED");
        assertThat(releaseReason(orderId)).isEqualTo("ORDER_REJECTED");
    }

    @Test
    void ordersWithoutACouponAndOtherEventsAreIgnored() throws Exception {
        String eventId = UUID.randomUUID().toString();
        deliver("OrderCreated", eventId, UUID.randomUUID());
        deliver("OrderCancelled", UUID.randomUUID().toString(), UUID.randomUUID());
        assertThat(processed(eventId)).isZero();                          // no trace for coupon-less orders

        String code = coupon("\"discountType\":\"FLAT\",\"value\":10");
        UUID orderId = reserved(code);
        for (String type : new String[]{"OrderConfirmed", "OrderDelivered", "ReturnApproved", "OrderAwaitingPayment"}) {
            deliver(type, UUID.randomUUID().toString(), orderId);
        }
        assertThat(redemptionStatus(orderId)).isEqualTo("RESERVED");

        ConsumerRecord<String, String> noOrderId = new ConsumerRecord<>("order.events", 0, 0L, "k", "{\"orderNumber\":\"GA-1\"}");
        noOrderId.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        noOrderId.headers().add("eventType", "OrderCancelled".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> orderEvents.on(noOrderId)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void theSweeperReleasesReservationsWithoutOrderCreated() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":10,\"totalUsageLimit\":2,\"perCustomerLimit\":3");
        UUID abandoned = reserved(code);
        UUID fresh = reserved(code);
        backdate(abandoned, 31);
        UUID confirmedLongAgo = reserved(coupon("\"discountType\":\"FLAT\",\"value\":10"));
        deliver("OrderCreated", UUID.randomUUID().toString(), confirmedLongAgo);
        backdate(confirmedLongAgo, 120);

        assertThat(redemptions.releaseExpired()).isGreaterThanOrEqualTo(1);
        assertThat(redemptionStatus(abandoned)).isEqualTo("RELEASED");
        assertThat(releaseReason(abandoned)).isEqualTo("RESERVATION_EXPIRED");
        assertThat(redemptionStatus(fresh)).isEqualTo("RESERVED");          // 30 minutes not over yet
        assertThat(redemptionStatus(confirmedLongAgo)).isEqualTo("CONFIRMED");

        // the use came back: the limit of 2 has room again
        reserved(code);
        // an OrderCreated that comes after all confirms the swept redemption again (the order exists)
        deliver("OrderCreated", UUID.randomUUID().toString(), abandoned);
        assertThat(redemptionStatus(abandoned)).isEqualTo("CONFIRMED");
        assertThat(releaseReason(abandoned)).isNull();
    }

    @Test
    void theSweeperSkipsRowsAnotherTransactionHolds() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":10,\"perCustomerLimit\":3");
        UUID held = reserved(code);
        UUID free = reserved(code);
        backdate(held, 45);
        backdate(free, 45);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT id FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, held.toString());
                lock.execute();
            }
            // does not wait for the held row (SKIP LOCKED), releases the other one
            CompletableFuture<Integer> run = CompletableFuture.supplyAsync(redemptions::releaseExpired);
            assertThat(run.get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(1);
            assertThat(redemptionStatus(free)).isEqualTo("RELEASED");
            assertThat(redemptionStatus(held)).isEqualTo("RESERVED");
            other.rollback();
        }
        redemptions.releaseExpired();
        assertThat(redemptionStatus(held)).isEqualTo("RELEASED");
    }
}
