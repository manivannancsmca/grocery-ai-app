package com.grocery.order;

import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order event timeline (Phase 10 contract §5): every event order-service publishes for an order (PUBLISHED) and
 * every event it consumes for an order (CONSUMED: APPLIED, IGNORED with the guard's reason, DUPLICATE) — from all five
 * consumers — written in the processing transaction, and {@code GET /api/admin/orders/{id}/events}.
 */
class OrderEventTimelineIntegrationTest extends AbstractIntegrationTest {

    private final UUID admin = UUID.randomUUID();

    private JsonNode timeline(UUID orderId) throws Exception {
        return body(mvc.perform(get("/api/admin/orders/{id}/events", orderId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk()));
    }

    /** "direction eventType outcome[ detail]" per entry, oldest first. */
    private List<String> lines(UUID orderId) throws Exception {
        return timeline(orderId).valueStream()
                .map(e -> e.get("direction").asString() + " " + e.get("eventType").asString() + " "
                        + e.get("outcome").asString() + (e.get("detail").isNull() ? "" : " " + e.get("detail").asString()))
                .toList();
    }

    private static String eventId(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, String> withHeader(ConsumerRecord<String, String> record, String name,
                                                             String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void aGroceryOrderShowsWhatItPublishedAndWhatItConsumedInOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        inventoryListener.on(stockReserved(newEventId(), orderId));
        UUID paymentId = UUID.randomUUID();
        paymentListener.on(paymentInitiated(newEventId(), orderId, paymentId));
        ConsumerRecord<String, String> captured = paymentCaptured(newEventId(), orderId, paymentId,
                "2026-11-11T10:05:00.250Z", false);
        paymentListener.on(captured);
        paymentListener.on(captured);                                                 // redelivered
        inventoryListener.on(stockReserved(newEventId(), orderId));                   // out of state
        UUID vendorId = storeIdOf(orderId);
        vendorListener.on(storeAccepted(VENDOR, newEventId(), orderId, vendorId, 15, Instant.now()));
        vendorListener.on(storeReady(VENDOR, newEventId(), orderId, vendorId, Instant.now()));
        UUID partner = UUID.randomUUID();
        deliveryListener.on(partnerAssigned(newEventId(), orderId, partner, "Ravi Kumar", Instant.now()));
        deliveryListener.on(deliveryPickedUp(newEventId(), orderId, partner, Instant.now()));
        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partner, Instant.now()));

        assertThat(lines(orderId)).containsExactly(
                "PUBLISHED OrderCreated APPLIED",
                "CONSUMED StockReserved APPLIED CREATED → AWAITING_PAYMENT",
                "PUBLISHED OrderAwaitingPayment APPLIED",
                "CONSUMED PaymentInitiated APPLIED",
                "CONSUMED PaymentCaptured APPLIED AWAITING_PAYMENT → CONFIRMED",
                "PUBLISHED OrderConfirmed APPLIED",
                "CONSUMED PaymentCaptured DUPLICATE already processed",
                "CONSUMED StockReserved IGNORED order is CONFIRMED, not CREATED",
                "CONSUMED VendorOrderAccepted APPLIED CONFIRMED → ACCEPTED",
                "PUBLISHED OrderAccepted APPLIED",
                "CONSUMED VendorOrderReadyForPickup APPLIED ACCEPTED → READY_FOR_PICKUP",
                "PUBLISHED OrderReadyForPickup APPLIED",
                "CONSUMED DeliveryPartnerAssigned APPLIED",
                "CONSUMED DeliveryPickedUp APPLIED READY_FOR_PICKUP → OUT_FOR_DELIVERY",
                "PUBLISHED OrderOutForDelivery APPLIED",
                "CONSUMED DeliveryCompleted APPLIED OUT_FOR_DELIVERY → DELIVERED",
                "PUBLISHED OrderDelivered APPLIED");

        JsonNode entries = timeline(orderId);
        assertThat(entries.isArray()).isTrue();                                       // a plain array, not a page
        assertThat(entries.valueStream().map(e -> e.get("sourceTopic").asString()).distinct().toList())
                .containsExactly("order.events", "inventory.events", "payment.events", "vendor.events",
                        "delivery.events");
        // every published entry is the outbox row it describes
        List<String> outboxIds = jdbc().sql("SELECT id FROM outbox_event WHERE aggregate_id = ? "
                        + "ORDER BY occurred_at, id").param(orderId.toString()).query(String.class).list();
        assertThat(entries.valueStream().filter(e -> e.get("direction").asString().equals("PUBLISHED"))
                .map(e -> e.get("eventId").asString()).toList()).containsExactlyInAnyOrderElementsOf(outboxIds);
        // the consumed entries carry the consumed event's id; times are in order
        List<JsonNode> capturedEntries = entries.valueStream()
                .filter(e -> e.get("eventType").asString().equals("PaymentCaptured")).toList();
        assertThat(capturedEntries).extracting(e -> e.get("eventId").asString())
                .containsOnly(eventId(captured));
        Instant previous = Instant.EPOCH;
        for (JsonNode entry : entries) {
            Instant recordedAt = Instant.parse(entry.get("recordedAt").asString());
            assertThat(recordedAt).isAfterOrEqualTo(previous);
            previous = recordedAt;
        }
    }

    @Test
    void anEntryHasTheEventsOwnTimeAndCorrelationId() throws Exception {
        UUID orderId = newRestaurantOrder(UUID.randomUUID());
        Instant occurredAt = Instant.parse("2026-11-11T10:05:00.123456Z");
        long micros = occurredAt.getEpochSecond() * 1_000_000L + occurredAt.getNano() / 1_000L;
        ConsumerRecord<String, String> captured = withHeader(withHeader(paymentCaptured(newEventId(), orderId, false),
                "occurredAt", Long.toString(micros)), "correlationId", "checkout-7c1d2e9f");

        paymentListener.on(captured);

        JsonNode entries = timeline(orderId);
        assertThat(fields(entries.get(0))).containsExactly("eventId", "eventType", "sourceTopic", "direction",
                "outcome", "detail", "occurredAt", "recordedAt", "correlationId");
        JsonNode consumed = entries.valueStream()
                .filter(e -> e.get("direction").asString().equals("CONSUMED")).findFirst().orElseThrow();
        assertThat(consumed.get("eventId").asString()).isEqualTo(eventId(captured));
        assertThat(consumed.get("eventType").asString()).isEqualTo("PaymentCaptured");
        assertThat(consumed.get("sourceTopic").asString()).isEqualTo("payment.events");
        assertThat(consumed.get("outcome").asString()).isEqualTo("APPLIED");
        assertThat(consumed.get("detail").asString()).isEqualTo("AWAITING_PAYMENT → CONFIRMED");
        assertThat(Instant.parse(consumed.get("occurredAt").asString())).isEqualTo(occurredAt);
        assertThat(Instant.parse(consumed.get("recordedAt").asString()))
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(consumed.get("correlationId").asString()).isEqualTo("checkout-7c1d2e9f");

        // without the headers: no occurredAt, the event id stands in for the correlation id
        ConsumerRecord<String, String> refund = refundCompleted(newEventId(), orderId, "10.00", "10.00", false);
        paymentListener.on(refund);
        JsonNode refundEntry = timeline(orderId).valueStream()
                .filter(e -> e.get("eventType").asString().equals("RefundCompleted")).findFirst().orElseThrow();
        assertThat(refundEntry.get("occurredAt").isNull()).isTrue();
        assertThat(refundEntry.get("correlationId").asString()).isEqualTo(eventId(refund));
        assertThat(refundEntry.get("detail").isNull()).isTrue();                      // applied, no status change

        // a published entry: occurredAt = recordedAt (order-service's own time)
        JsonNode published = entries.get(0);
        assertThat(published.get("direction").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("sourceTopic").asString()).isEqualTo("order.events");
        assertThat(published.get("occurredAt").asString()).isEqualTo(published.get("recordedAt").asString());
    }

    @Test
    void ignoredEventsCarryTheGuardsReason() throws Exception {
        UUID restaurantOrder = newRestaurantOrder(UUID.randomUUID());               // AWAITING_PAYMENT
        inventoryListener.on(stockReservationFailed(newEventId(), restaurantOrder, Map.of(UUID.randomUUID(),
                new int[]{1, 0})));
        restaurantListener.on(storePreparing(RESTAURANT, newEventId(), restaurantOrder, storeIdOf(restaurantOrder),
                Instant.now()));
        UUID someVendor = UUID.randomUUID();
        vendorListener.on(storeAccepted(VENDOR, newEventId(), restaurantOrder, someVendor, 10,
                Instant.now()));                                                     // a vendor event for it
        deliveryListener.on(partnerUnassigned(newEventId(), restaurantOrder, UUID.randomUUID()));
        paymentListener.on(refundCompleted(newEventId(), restaurantOrder, "5.00", "5.00", false));
        paymentListener.on(refundCompleted(newEventId(), restaurantOrder, "2.00", "2.00", false)); // stale total

        assertThat(lines(restaurantOrder)).containsExactly(
                "PUBLISHED OrderCreated APPLIED",
                "PUBLISHED OrderAwaitingPayment APPLIED",
                "CONSUMED StockReservationFailed IGNORED order is AWAITING_PAYMENT, not CREATED",
                "CONSUMED RestaurantOrderPreparing IGNORED order is AWAITING_PAYMENT, not ACCEPTED",
                "CONSUMED VendorOrderAccepted IGNORED not an order of GROCERY store " + someVendor,
                "CONSUMED DeliveryPartnerUnassigned IGNORED order is AWAITING_PAYMENT, not ACCEPTED, PREPARING or "
                        + "READY_FOR_PICKUP",
                "CONSUMED RefundCompleted APPLIED",
                "CONSUMED RefundCompleted IGNORED totalRefunded 2.00 is below the recorded 5.00");
    }

    @Test
    void aCancellationAndTheWatchdogArePublishedEntriesWithTheRequestsCorrelationId() throws Exception {
        UUID orderId = confirmedOrder(UUID.randomUUID());
        movedBack(orderId, "confirmed_at", "CONFIRMED", Instant.now().minus(20, ChronoUnit.MINUTES));
        watchdog.run();
        mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).with(as(admin, "SUPER_ADMIN"))
                        .header("X-Correlation-Id", "support-call-0042")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Store unreachable\"}"))
                .andExpect(status().isOk());

        List<JsonNode> entries = timeline(orderId).valueStream().toList();
        assertThat(entries.subList(entries.size() - 2, entries.size()))
                .extracting(e -> e.get("eventType").asString())
                .containsExactly("OrderStuckDetected", "OrderCancelled");
        JsonNode cancelled = entries.getLast();
        assertThat(cancelled.get("direction").asString()).isEqualTo("PUBLISHED");
        assertThat(cancelled.get("correlationId").asString()).isEqualTo("support-call-0042");
        JsonNode stuck = entries.get(entries.size() - 2);
        assertThat(stuck.get("correlationId").asString()).isEqualTo(stuck.get("eventId").asString());
    }

    @Test
    void anUnknownOrdersEventsAreNotRecorded() throws Exception {
        UUID unknown = UUID.randomUUID();
        ConsumerRecord<String, String> reserved = stockReserved(newEventId(), unknown);
        inventoryListener.on(reserved);
        inventoryListener.on(reserved);                                               // and its redelivery

        assertThat(jdbc().sql("SELECT COUNT(*) FROM order_event_log WHERE order_id = UUID_TO_BIN(?)")
                .param(unknown.toString()).query(Long.class).single()).isZero();
        mvc.perform(get("/api/admin/orders/{id}/events", unknown).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/admin/orders/{id}/events", "not-a-uuid").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    /** An event whose processing fails leaves no entry (same transaction); the redelivery records it once. */
    @Test
    void aFailedProcessingLeavesNoEntry() throws Exception {
        UUID orderId = awaitingPaymentGroceryOrder(UUID.randomUUID());
        ConsumerRecord<String, String> captured = paymentCaptured(newEventId(), orderId, false);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM customer_order WHERE id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, orderId.toString());
                lock.executeQuery().close();
            }
            assertThatThrownBy(() -> paymentListener.on(captured))
                    .satisfies(failure -> assertThat(TransientFailures.isTransient(failure)).isTrue());
            holder.rollback();
        }
        assertThat(lines(orderId)).noneMatch(line -> line.contains("PaymentCaptured"));

        paymentListener.on(captured);                                                 // Kafka redelivers it

        assertThat(lines(orderId)).filteredOn(line -> line.contains("PaymentCaptured"))
                .containsExactly("CONSUMED PaymentCaptured APPLIED AWAITING_PAYMENT → CONFIRMED");
    }

    @Test
    void anyAdminMayReadTheTimeline() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String path = "/api/admin/orders/" + orderId + "/events";
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(path).with(as(admin, role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventType").value("OrderCreated"));
        }
        mvc.perform(get(path).with(customer(customerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
