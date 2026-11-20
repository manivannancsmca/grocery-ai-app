package com.grocery.order;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientFailures;
import com.grocery.order.event.InventoryEventsListener;
import com.grocery.order.event.PaymentEventsListener;
import com.grocery.order.event.RestaurantEventsListener;
import com.grocery.order.event.VendorEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payment.events → orders: every row of the contract's state-guard table for the payment events (including a capture
 * after a cancellation), duplicates, events in other states, and an outage while applying an event.
 */
class PaymentEventsIntegrationTest extends AbstractIntegrationTest {

    static final String CONSUMER = "order-service.payment-events";

    private String paymentStatus(UUID customerId, UUID orderId) throws Exception {
        return order(customerId, orderId).get("payment").get("status").asString();
    }

    @Test
    void paymentInitiatedMarksAnUnpaidOrderPendingInAnyStatus() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);                      // CREATED, NOT_STARTED
        String eventId = newEventId();

        paymentListener.on(paymentInitiated(eventId, orderId, UUID.randomUUID()));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CREATED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PENDING");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated");

        // not once something is paid, nor once no payment is needed any more
        UUID paid = confirmedOrder(customerId);
        paymentListener.on(paymentInitiated(newEventId(), paid, UUID.randomUUID()));
        assertThat(paymentStatus(customerId, paid)).isEqualTo("PAID");
        UUID rejected = newGroceryOrder(UUID.randomUUID());
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, Map.of(UUID.randomUUID(), new int[]{1, 0})));
        paymentListener.on(paymentInitiated(newEventId(), rejected, UUID.randomUUID()));
        assertThat(jdbc().sql("SELECT payment_status FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(rejected.toString()).query(String.class).single()).isEqualTo("NOT_REQUIRED");
    }

    @Test
    void paymentCapturedConfirmsAnOrderAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        JsonNode awaiting = order(customerId, orderId);
        String eventId = newEventId();

        paymentListener.on(paymentCaptured(eventId, orderId, UUID.randomUUID(), "2026-11-11T10:05:00.250Z", false));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PAID");
        assertThat(Instant.parse(order.get("payment").get("paidAt").asString()))
                .isEqualTo(Instant.parse("2026-11-11T10:05:00.250Z"));
        assertThat(order.get("cancellable").asBoolean()).isTrue();       // until the store accepts (Phase 8)
        assertThat(order.get("timeline").valueStream().map(e -> e.get("status").asString()))
                .containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);

        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
        JsonNode confirmed = orderEvents(orderId, "OrderConfirmed").getFirst();
        assertThat(fields(confirmed)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "items", "total", "deliveryAddress", "confirmedAt", "subtotal", "note",    // + Phase 8
                "storeLocation",                                                                     // + Phase 9
                "couponCode", "discount", "deliveryFeeDiscount",                                     // + Phase 12
                "couponName", "couponFundedBy");                                                     // + 1.7.0
        assertThat(confirmed.get("storeLocation").get("name").asString()).isEqualTo("Green Basket");
        assertThat(confirmed.get("storeLocation").get("latitude").asDouble()).isEqualTo(StubCart.STORE_LATITUDE);
        assertThat(confirmed.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(confirmed.get("note").isNull()).isTrue();                // no delivery note on this order
        assertThat(confirmed.get("orderNumber").asString()).isEqualTo(awaiting.get("orderNumber").asString());
        assertThat(confirmed.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(confirmed.get("storeId").asString()).isEqualTo(awaiting.get("store").get("id").asString());
        assertThat(confirmed.get("items")).hasSize(2);
        assertThat(confirmed.get("items").get(0).get("itemId").asString())
                .isEqualTo(awaiting.get("items").get(0).get("itemId").asString());
        assertThat(confirmed.get("total").decimalValue()).isEqualByComparingTo(GROCERY_TOTAL);
        assertThat(confirmed.get("deliveryAddress").get("city").asString()).isEqualTo("Bengaluru");
        assertThat(confirmed.get("deliveryAddress").get("postalCode").asString()).isEqualTo("560001");
        assertThat(Instant.parse(confirmed.get("confirmedAt").asString()))
                .isEqualTo(Instant.parse(order.get("updatedAt").asString()));

        // redelivered, or captured again: once only
        paymentListener.on(paymentCaptured(eventId, orderId, false));
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
        assertThat(order(customerId, orderId)).isEqualTo(order);
    }

    @Test
    void paymentCapturedIsIgnoredBeforePaymentIsDueAndAfterARejection() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID created = newGroceryOrder(customerId);
        String eventId = newEventId();
        paymentListener.on(paymentCaptured(eventId, created, false));
        assertThat(order(customerId, created).get("status").asString()).isEqualTo("CREATED");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);

        UUID rejected = newGroceryOrder(customerId);
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, Map.of(UUID.randomUUID(), new int[]{1, 0})));
        paymentListener.on(paymentCaptured(newEventId(), rejected, false));
        assertThat(order(customerId, rejected).get("status").asString()).isEqualTo("REJECTED");
        assertThat(paymentStatus(customerId, rejected)).isEqualTo("NOT_REQUIRED");
        assertThat(orderEventTypes(created)).containsExactly("OrderCreated");
        assertThat(orderEventTypes(rejected)).containsExactly("OrderCreated", "OrderRejected");
    }

    @Test
    void aCaptureAfterTheOrderWasCancelledOnlyMarksTheRefund() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newRestaurantOrder(customerId);
        paymentListener.on(paymentExpired(newEventId(), orderId));           // the window closed: CANCELLED
        assertThat(paymentStatus(customerId, orderId)).isEqualTo("EXPIRED");
        JsonNode cancelled = order(customerId, orderId);

        // the customer paid at the last second: payment-service refunds it ("late capture")
        paymentListener.on(paymentCaptured(newEventId(), orderId, true));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");
        assertThat(order.get("payment").get("paidAt").isNull()).isTrue();
        assertThat(order.get("timeline")).isEqualTo(cancelled.get("timeline"));
        assertThat(order.get("cancellation")).isEqualTo(cancelled.get("cancellation"));
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");

        // and the refund lands
        paymentListener.on(refundCompleted(newEventId(), orderId, "359.00", "359.00", true));
        order = order(customerId, orderId);
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUNDED");
        assertThat(order.get("payment").get("refundedAmount").decimalValue()).isEqualByComparingTo("359.00");
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");

        // the customer cancelled while paying: the capture after the cancel is refunded too
        UUID second = newRestaurantOrder(customerId);
        mvc.perform(post("/api/orders/{id}/cancel", second).with(customer(customerId))).andExpect(status().isOk());
        paymentListener.on(paymentCaptured(newEventId(), second, false));
        assertThat(order(customerId, second).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(paymentStatus(customerId, second)).isEqualTo("REFUND_PENDING");
        assertThat(orderEventTypes(second)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
    }

    @Test
    void paymentFailedRecordsTheLastFailureWhileAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newRestaurantOrder(customerId);
        String eventId = newEventId();

        paymentListener.on(paymentFailed(eventId, orderId, "BAD_REQUEST_ERROR", "Payment declined"));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PENDING");
        JsonNode failure = order.get("payment").get("lastFailure");
        assertThat(fields(failure)).containsExactly("code", "description", "at");
        assertThat(failure.get("code").asString()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(failure.get("description").asString()).isEqualTo("Payment declined");
        assertThat(failure.get("at").asString()).isNotBlank();
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment");

        // the most recent one wins; paying afterwards clears it
        paymentListener.on(paymentFailed(newEventId(), orderId, "GATEWAY_ERROR", "Bank unavailable"));
        assertThat(order(customerId, orderId).get("payment").get("lastFailure").get("code").asString())
                .isEqualTo("GATEWAY_ERROR");
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        assertThat(order(customerId, orderId).get("payment").get("lastFailure").isNull()).isTrue();

        // not applied once the order is no longer payable
        paymentListener.on(paymentFailed(newEventId(), orderId, "BAD_REQUEST_ERROR", "Payment declined"));
        assertThat(order(customerId, orderId).get("payment").get("lastFailure").isNull()).isTrue();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void paymentExpiredCancelsAnOrderAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        String eventId = newEventId();

        paymentListener.on(paymentExpired(eventId, orderId));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(order.get("cancellation").get("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(order.get("cancellation").get("by").asString()).isEqualTo("SYSTEM");
        assertThat(order.get("timeline").get(2).get("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("previousStatus").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(cancelled.get("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(Instant.parse(cancelled.get("cancelledAt").asString()))
                .isEqualTo(Instant.parse(order.get("cancellation").get("at").asString()));

        paymentListener.on(paymentExpired(eventId, orderId));
        paymentListener.on(paymentExpired(newEventId(), orderId));
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
    }

    @Test
    void paymentExpiredIsIgnoredOncePaidOrBeforePaymentIsDue() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID confirmed = confirmedOrder(customerId);
        paymentListener.on(paymentExpired(newEventId(), confirmed));
        assertThat(order(customerId, confirmed).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(customerId, confirmed)).isEqualTo("PAID");

        UUID created = newGroceryOrder(customerId);
        paymentListener.on(paymentExpired(newEventId(), created));
        assertThat(order(customerId, created).get("status").asString()).isEqualTo("CREATED");
        assertThat(orderEventTypes(confirmed)).doesNotContain("OrderCancelled");
        assertThat(orderEventTypes(created)).containsExactly("OrderCreated");
    }

    @Test
    void refundCompletedKeepsTheTotalRefundedWithoutChangingTheStatus() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);                        // total 359.00

        paymentListener.on(refundCompleted(newEventId(), orderId, "50.00", "50.00", false));
        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(order.get("payment").get("refundedAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(mvc.perform(get("/api/orders/{id}", orderId).with(customer(customerId))).andReturn().getResponse()
                .getContentAsString()).contains("\"refundedAmount\":50.00");

        String stale = newEventId();
        paymentListener.on(refundCompleted(newEventId(), orderId, "100.00", "150.00", false));
        paymentListener.on(refundCompleted(stale, orderId, "20.00", "70.00", false));   // older total: ignored
        assertThat(order(customerId, orderId).get("payment").get("refundedAmount").decimalValue())
                .isEqualByComparingTo("150.00");
        assertThat(processed(stale, CONSUMER)).isEqualTo(1);

        paymentListener.on(refundCompleted(newEventId(), orderId, "209.00", "359.00", true));
        order = order(customerId, orderId);
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUNDED");
        assertThat(order.get("payment").get("refundedAmount").decimalValue()).isEqualByComparingTo("359.00");
        assertThat(order.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
    }

    @Test
    void refundFailedAndUnknownPaymentEventsChangeNothing() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        JsonNode before = order(customerId, orderId);
        String failed = newEventId();
        String other = newEventId();

        paymentListener.on(paymentEvent("RefundFailed", failed, orderId, Map.of("orderId", orderId.toString(),
                "paymentId", UUID.randomUUID().toString(), "refundId", UUID.randomUUID().toString(),
                "amount", 50.00, "reason", "ADMIN", "errorCode", "BAD_REQUEST_ERROR")));
        paymentListener.on(paymentEvent("PaymentSessionRefreshed", other, orderId,
                Map.of("orderId", orderId.toString())));

        assertThat(processed(failed, CONSUMER)).isZero();
        assertThat(processed(other, CONSUMER)).isZero();
        assertThat(order(customerId, orderId)).isEqualTo(before);
    }

    @Test
    void brokenPaymentEventsAreInvalid() {
        UUID orderId = UUID.randomUUID();
        assertThatThrownBy(() -> paymentListener.on(paymentEvent("PaymentCaptured", newEventId(), orderId,
                Map.of("paymentId", UUID.randomUUID().toString()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> paymentListener.on(paymentEvent("RefundCompleted", newEventId(), orderId,
                Map.of("orderId", orderId.toString(), "amount", 10))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> paymentListener.on(paymentEvent("PaymentCaptured", newEventId(), orderId,
                Map.of("orderId", "not-a-uuid"))))
                .isInstanceOf(InvalidEventException.class);
    }

    /**
     * An outage while applying an event (here: the order row stays locked longer than the lock wait timeout) must be
     * retried, not dead-lettered: the failure is one grocery-common's error handler classifies as transient, and the
     * event is not marked processed, so the redelivery applies it.
     */
    @Test
    void aLockWaitTimeoutIsATransientFailureAndTheRedeliveryAppliesTheEvent() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newRestaurantOrder(customerId);
        String eventId = newEventId();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT status FROM customer_order WHERE id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, orderId.toString());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            assertThatThrownBy(() -> paymentListener.on(paymentCaptured(eventId, orderId, false)))
                    .satisfies(e -> assertThat(TransientFailures.isTransient(e)).as(e.toString()).isTrue());
            blocker.rollback();
        }
        assertThat(processed(eventId, CONSUMER)).isZero();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("AWAITING_PAYMENT");

        paymentListener.on(paymentCaptured(eventId, orderId, false));        // the redelivery
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
    }

    @Test
    void theListenersUseTheContractsConsumerGroups() throws Exception {
        assertThat(List.of(InventoryEventsListener.class.getMethod("on", ConsumerRecord.class),
                PaymentEventsListener.class.getMethod("on", ConsumerRecord.class),
                VendorEventsListener.class.getMethod("on", ConsumerRecord.class),
                RestaurantEventsListener.class.getMethod("on", ConsumerRecord.class)))
                .extracting(m -> m.getAnnotation(KafkaListener.class))
                .extracting(l -> l.groupId() + "@" + String.join(",", l.topics()))
                .containsExactly("order-service.inventory-events@inventory.events",
                        "order-service.payment-events@payment.events",
                        "order-service.vendor-events@vendor.events",
                        "order-service.restaurant-events@restaurant.events");
    }
}
