package com.grocery.order;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * vendor.events / restaurant.events → orders (Phase 8 contract §4): every row of the store state-guard table for both
 * store kinds, duplicates, events out of state or past their step, events of the wrong store type or store, the
 * events the consumers skip, unknown orders, broken events and an outage while applying one.
 */
class StoreEventsIntegrationTest extends AbstractIntegrationTest {

    static final String VENDOR_CONSUMER = "order-service.vendor-events";
    static final String RESTAURANT_CONSUMER = "order-service.restaurant-events";

    private static List<String> timeline(JsonNode order) {
        return order.get("timeline").valueStream()
                .map(e -> e.get("status").asString() + (e.get("reason").isNull() ? "" : ":" + e.get("reason").asString()))
                .toList();
    }

    private static Instant instant(JsonNode node) {
        return Instant.parse(node.asString());
    }

    private static String eventId(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- accepted

    @Test
    void vendorOrderAcceptedMovesAConfirmedGroceryOrderToAccepted() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        JsonNode confirmed = order(customerId, orderId);
        assertThat(confirmed.get("acceptedAt").isNull()).isTrue();
        assertThat(confirmed.get("readyInMinutes").isNull()).isTrue();
        assertThat(confirmed.get("readyBy").isNull()).isTrue();
        Instant acceptedAt = Instant.parse("2026-11-11T10:06:00.123456Z");
        String eventId = newEventId();

        vendorListener.on(storeAccepted(VENDOR, eventId, orderId, vendorId, 15, acceptedAt));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(instant(order.get("acceptedAt"))).isEqualTo(acceptedAt);
        assertThat(order.get("readyInMinutes").asInt()).isEqualTo(15);
        assertThat(instant(order.get("readyBy"))).isEqualTo(acceptedAt.plusSeconds(15 * 60));
        assertThat(order.get("cancellable").asBoolean()).isFalse();          // the store accepted
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PAID");
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED");
        assertThat(processed(eventId, VENDOR_CONSUMER)).isEqualTo(1);

        assertThat(orderEventTypes(orderId))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderAccepted");
        JsonNode accepted = orderEvents(orderId, "OrderAccepted").getFirst();
        assertThat(fields(accepted)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "readyInMinutes", "readyBy", "acceptedAt");
        assertThat(accepted.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(accepted.get("orderNumber").asString()).isEqualTo(order.get("orderNumber").asString());
        assertThat(accepted.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(accepted.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(accepted.get("storeId").asString()).isEqualTo(vendorId.toString());
        assertThat(accepted.get("readyInMinutes").asInt()).isEqualTo(15);
        assertThat(instant(accepted.get("readyBy"))).isEqualTo(acceptedAt.plusSeconds(15 * 60));
        assertThat(instant(accepted.get("acceptedAt"))).isEqualTo(acceptedAt);
        assertThat(outboxRow(orderId, "OrderAccepted"))
                .containsEntry("topic", "order.events").containsEntry("aggregate_type", "Order")
                .containsEntry("aggregate_id", orderId.toString());

        // redelivered, or accepted again: once only
        vendorListener.on(storeAccepted(VENDOR, eventId, orderId, vendorId, 30, acceptedAt.plusSeconds(5)));
        String again = newEventId();
        vendorListener.on(storeAccepted(VENDOR, again, orderId, vendorId, 30, acceptedAt.plusSeconds(5)));
        assertThat(processed(again, VENDOR_CONSUMER)).isEqualTo(1);
        assertThat(order(customerId, orderId)).isEqualTo(order);
        assertThat(orderEventTypes(orderId))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderAccepted");
    }

    @Test
    void anAcceptanceWithoutReadyByOrAcceptedAtIsCompletedByOrderService() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        Instant before = Instant.now().minusSeconds(1);

        restaurantListener.on(storeEvent(RESTAURANT, "OrderAccepted", newEventId(), orderId, storeIdOf(orderId),
                Map.of("readyInMinutes", 25)));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("ACCEPTED");
        Instant acceptedAt = instant(order.get("acceptedAt"));
        assertThat(acceptedAt).isAfter(before);
        assertThat(instant(order.get("readyBy"))).isEqualTo(acceptedAt.plusSeconds(25 * 60));
        assertThat(order.get("readyInMinutes").asInt()).isEqualTo(25);
    }

    // ---------------------------------------------------------------- preparing / ready

    @Test
    void aRestaurantOrderIsPreparedAndBecomesReadyForPickup() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        UUID restaurantId = storeIdOf(orderId);
        Instant acceptedAt = Instant.parse("2026-11-11T11:01:00Z");
        restaurantListener.on(storeAccepted(RESTAURANT, newEventId(), orderId, restaurantId, 20, acceptedAt));
        Instant preparingAt = Instant.parse("2026-11-11T11:02:30.5Z");
        String preparing = newEventId();

        restaurantListener.on(storePreparing(RESTAURANT, preparing, orderId, restaurantId, preparingAt));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("PREPARING");
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(instant(order.get("acceptedAt"))).isEqualTo(acceptedAt);          // kept
        assertThat(processed(preparing, RESTAURANT_CONSUMER)).isEqualTo(1);
        JsonNode prepared = orderEvents(orderId, "OrderPreparing").getFirst();
        assertThat(fields(prepared)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "preparingAt");
        assertThat(prepared.get("orderType").asString()).isEqualTo("RESTAURANT");
        assertThat(prepared.get("storeId").asString()).isEqualTo(restaurantId.toString());
        assertThat(prepared.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(instant(prepared.get("preparingAt"))).isEqualTo(preparingAt);
        assertThat(jdbc().sql("SELECT preparing_at FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC))
                .isEqualTo(preparingAt);

        // a redelivery and a second "preparing": once only
        restaurantListener.on(storePreparing(RESTAURANT, preparing, orderId, restaurantId, preparingAt));
        restaurantListener.on(storePreparing(RESTAURANT, newEventId(), orderId, restaurantId, preparingAt));
        assertThat(order(customerId, orderId)).isEqualTo(order);

        Instant readyAt = Instant.parse("2026-11-11T11:18:00Z");
        String ready = newEventId();
        restaurantListener.on(storeReady(RESTAURANT, ready, orderId, restaurantId, readyAt));

        JsonNode readyOrder = order(customerId, orderId);
        assertThat(readyOrder.get("status").asString()).isEqualTo("READY_FOR_PICKUP");
        assertThat(readyOrder.get("cancellable").asBoolean()).isFalse();
        assertThat(timeline(readyOrder)).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED",
                "PREPARING", "READY_FOR_PICKUP");
        JsonNode readyEvent = orderEvents(orderId, "OrderReadyForPickup").getFirst();
        assertThat(fields(readyEvent)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "readyAt");
        assertThat(instant(readyEvent.get("readyAt"))).isEqualTo(readyAt);
        assertThat(readyEvent.get("orderNumber").asString()).isEqualTo(readyOrder.get("orderNumber").asString());

        // a redelivery, a second "ready" and a late "preparing" (past that step): ignored
        restaurantListener.on(storeReady(RESTAURANT, ready, orderId, restaurantId, readyAt));
        restaurantListener.on(storeReady(RESTAURANT, newEventId(), orderId, restaurantId, readyAt));
        String late = newEventId();
        restaurantListener.on(storePreparing(RESTAURANT, late, orderId, restaurantId, readyAt));
        assertThat(processed(late, RESTAURANT_CONSUMER)).isEqualTo(1);
        assertThat(order(customerId, orderId)).isEqualTo(readyOrder);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed",
                "OrderAccepted", "OrderPreparing", "OrderReadyForPickup");
    }

    @Test
    void aGroceryOrderMayGoFromAcceptedStraightToReady() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        vendorListener.on(storeAccepted(VENDOR, newEventId(), orderId, vendorId, 15, Instant.now()));

        vendorListener.on(storeReady(VENDOR, newEventId(), orderId, vendorId, Instant.now()));
        vendorListener.on(storePreparing(VENDOR, newEventId(), orderId, vendorId, Instant.now()));   // past it

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("READY_FOR_PICKUP");
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED",
                "READY_FOR_PICKUP");
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed",
                "OrderAccepted", "OrderReadyForPickup");
        assertThat(orderEvents(orderId, "OrderReadyForPickup").getFirst().get("orderType").asString())
                .isEqualTo("GROCERY");
    }

    // ---------------------------------------------------------------- rejected

    @Test
    void aStoreRejectionCancelsTheOrderWithStoreRejected() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        UUID vendorUser = UUID.randomUUID();
        String eventId = newEventId();

        vendorListener.on(storeRejected(VENDOR, eventId, orderId, vendorId, "OUT_OF_STOCK", "  Toor dal is over ",
                false, vendorUser));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");   // full refund
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        JsonNode cancellation = order.get("cancellation");
        assertThat(cancellation.get("reason").asString()).isEqualTo("STORE_REJECTED");
        assertThat(cancellation.get("by").asString()).isEqualTo("STORE");
        assertThat(cancellation.get("note").asString()).isEqualTo("OUT_OF_STOCK: Toor dal is over");
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED",
                "CANCELLED:STORE_REJECTED");
        assertThat(processed(eventId, VENDOR_CONSUMER)).isEqualTo(1);

        assertThat(orderEventTypes(orderId))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderCancelled");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("previousStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("STORE_REJECTED");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("STORE");
        assertThat(cancelled.get("actorUserId").asString()).isEqualTo(vendorUser.toString());
        assertThat(cancelled.get("note").asString()).isEqualTo("OUT_OF_STOCK: Toor dal is over");
        assertThat(cancelled.get("storeId").asString()).isEqualTo(vendorId.toString());

        // redelivered, rejected again, or accepted after the rejection: nothing more
        vendorListener.on(storeRejected(VENDOR, eventId, orderId, vendorId, "OUT_OF_STOCK", null, false, vendorUser));
        vendorListener.on(storeRejected(VENDOR, newEventId(), orderId, vendorId, "TOO_BUSY", null, false, vendorUser));
        String acceptedLate = newEventId();
        vendorListener.on(storeAccepted(VENDOR, acceptedLate, orderId, vendorId, 15, Instant.now()));
        assertThat(processed(acceptedLate, VENDOR_CONSUMER)).isEqualTo(1);
        assertThat(order(customerId, orderId)).isEqualTo(order);
        assertThat(orderEventTypes(orderId))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderCancelled");
    }

    @Test
    void aRejectionWithoutANoteKeepsJustTheReason() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);

        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), "TOO_BUSY", "  ",
                false, UUID.randomUUID()));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("cancellation").get("reason").asString()).isEqualTo("STORE_REJECTED");
        assertThat(order.get("cancellation").get("note").asString()).isEqualTo("TOO_BUSY");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("note").asString()).isEqualTo("TOO_BUSY");
        assertThat(cancelled.get("orderType").asString()).isEqualTo("RESTAURANT");
    }

    @Test
    void aLongStoreNoteIsKeptWhole() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        String note = "n".repeat(300);

        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), "OTHER", note,
                false, UUID.randomUUID()));

        assertThat(order(customerId, orderId).get("cancellation").get("note").asString()).isEqualTo("OTHER: " + note);
    }

    @Test
    void anAutomaticRejectionCancelsTheOrderWithStoreNoResponse() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        String eventId = newEventId();

        restaurantListener.on(storeRejected(RESTAURANT, eventId, orderId, storeIdOf(orderId), "NO_RESPONSE", null,
                true, null));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");
        JsonNode cancellation = order.get("cancellation");
        assertThat(cancellation.get("reason").asString()).isEqualTo("STORE_NO_RESPONSE");
        assertThat(cancellation.get("by").asString()).isEqualTo("SYSTEM");
        assertThat(cancellation.get("note").isNull()).isTrue();
        assertThat(timeline(order).getLast()).isEqualTo("CANCELLED:STORE_NO_RESPONSE");
        assertThat(processed(eventId, RESTAURANT_CONSUMER)).isEqualTo(1);

        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("previousStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("STORE_NO_RESPONSE");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(cancelled.get("actorUserId").isNull()).isTrue();
        assertThat(cancelled.get("note").isNull()).isTrue();

        // the refund arrives later: the order stays CANCELLED
        paymentListener.on(refundCompleted(newEventId(), orderId, "359.00", "359.00", true));
        assertThat(order(customerId, orderId).get("payment").get("status").asString()).isEqualTo("REFUNDED");
    }

    // ---------------------------------------------------------------- out of state, wrong store, skipped

    @Test
    void storeEventsOutOfStateAreIgnoredAndMarked() throws Exception {
        UUID customerId = UUID.randomUUID();
        // not paid yet: no store event applies
        UUID awaiting = newRestaurantOrder(customerId);
        UUID awaitingStore = storeIdOf(awaiting);
        List<ConsumerRecord<String, String>> tooEarly = List.of(
                storeAccepted(RESTAURANT, newEventId(), awaiting, awaitingStore, 20, Instant.now()),
                storePreparing(RESTAURANT, newEventId(), awaiting, awaitingStore, Instant.now()),
                storeReady(RESTAURANT, newEventId(), awaiting, awaitingStore, Instant.now()),
                storeRejected(RESTAURANT, newEventId(), awaiting, awaitingStore, "TOO_BUSY", null, false, null));
        for (ConsumerRecord<String, String> record : tooEarly) {
            restaurantListener.on(record);
            assertThat(processed(eventId(record), RESTAURANT_CONSUMER)).isEqualTo(1);
        }
        assertThat(order(customerId, awaiting).get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(orderEventTypes(awaiting)).containsExactly("OrderCreated", "OrderAwaitingPayment");

        // CONFIRMED: preparing / ready need an acceptance first
        UUID confirmed = confirmedOrder(customerId);
        UUID confirmedStore = storeIdOf(confirmed);
        restaurantListener.on(storePreparing(RESTAURANT, newEventId(), confirmed, confirmedStore, Instant.now()));
        restaurantListener.on(storeReady(RESTAURANT, newEventId(), confirmed, confirmedStore, Instant.now()));
        assertThat(order(customerId, confirmed).get("status").asString()).isEqualTo("CONFIRMED");

        // ACCEPTED / PREPARING: a rejection comes too late
        UUID accepted = acceptedOrder(customerId);
        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), accepted, storeIdOf(accepted), "TOO_BUSY",
                null, false, UUID.randomUUID()));
        assertThat(order(customerId, accepted).get("status").asString()).isEqualTo("ACCEPTED");
        UUID preparing = preparingOrder(customerId);
        restaurantListener.on(storeAccepted(RESTAURANT, newEventId(), preparing, storeIdOf(preparing), 10,
                Instant.now()));
        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), preparing, storeIdOf(preparing), "NO_RESPONSE",
                null, true, null));
        assertThat(order(customerId, preparing).get("status").asString()).isEqualTo("PREPARING");
        assertThat(order(customerId, preparing).get("readyInMinutes").asInt()).isEqualTo(20);

        // CANCELLED (by the customer before the store decided): every store event is ignored
        UUID cancelled = confirmedOrder(customerId);
        UUID cancelledStore = storeIdOf(cancelled);
        mvcCancel(customerId, cancelled);
        for (ConsumerRecord<String, String> record : List.of(
                storeAccepted(RESTAURANT, newEventId(), cancelled, cancelledStore, 20, Instant.now()),
                storePreparing(RESTAURANT, newEventId(), cancelled, cancelledStore, Instant.now()),
                storeReady(RESTAURANT, newEventId(), cancelled, cancelledStore, Instant.now()),
                storeRejected(RESTAURANT, newEventId(), cancelled, cancelledStore, "TOO_BUSY", null, false, null))) {
            restaurantListener.on(record);
            assertThat(processed(eventId(record), RESTAURANT_CONSUMER)).isEqualTo(1);
        }
        JsonNode order = order(customerId, cancelled);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation").get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(order.get("acceptedAt").isNull()).isTrue();
        assertThat(orderEventTypes(cancelled))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderCancelled");
    }

    private void mvcCancel(UUID customerId, UUID orderId) throws Exception {
        mvc.perform(post("/api/orders/{id}/cancel", orderId).with(customer(customerId))).andExpect(status().isOk());
    }

    @Test
    void eventsOfTheWrongStoreTypeOrStoreAreIgnoredAndMarked() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID restaurantOrder = confirmedOrder(customerId);
        UUID restaurantId = storeIdOf(restaurantOrder);
        UUID groceryOrder = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(groceryOrder);

        List<ConsumerRecord<String, String>> wrong = List.of(
                // a vendor event for a RESTAURANT order (even with the order's own store id)
                storeAccepted(VENDOR, newEventId(), restaurantOrder, restaurantId, 15, Instant.now()),
                storeRejected(VENDOR, newEventId(), restaurantOrder, restaurantId, "TOO_BUSY", null, false, null),
                // a restaurant event for a GROCERY order
                storeAccepted(RESTAURANT, newEventId(), groceryOrder, vendorId, 15, Instant.now()),
                // the right store type, another store
                storeAccepted(RESTAURANT, newEventId(), restaurantOrder, UUID.randomUUID(), 15, Instant.now()),
                storeRejected(RESTAURANT, newEventId(), restaurantOrder, UUID.randomUUID(), "TOO_BUSY", null, true,
                        null),
                storeAccepted(VENDOR, newEventId(), groceryOrder, UUID.randomUUID(), 15, Instant.now()),
                storeRejected(VENDOR, newEventId(), groceryOrder, restaurantId, "OTHER", "x", false, null));

        for (ConsumerRecord<String, String> record : wrong) {
            onStoreEvent(record);
            String consumer = record.topic().equals("vendor.events") ? VENDOR_CONSUMER : RESTAURANT_CONSUMER;
            assertThat(processed(eventId(record), consumer)).isEqualTo(1);
        }
        assertThat(order(customerId, restaurantOrder).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order(customerId, groceryOrder).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(orderEventTypes(restaurantOrder)).containsExactly("OrderCreated", "OrderAwaitingPayment",
                "OrderConfirmed");
        assertThat(orderEventTypes(groceryOrder)).containsExactly("OrderCreated", "OrderAwaitingPayment",
                "OrderConfirmed");

        // the right store still can
        restaurantListener.on(storeAccepted(RESTAURANT, newEventId(), restaurantOrder, restaurantId, 15,
                Instant.now()));
        assertThat(order(customerId, restaurantOrder).get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void receivedAndLifecycleEventsAreSkippedWithoutTouchingTheDatabase() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        UUID restaurantId = storeIdOf(orderId);
        JsonNode before = order(customerId, orderId);
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("ownerUserId", UUID.randomUUID().toString());
        received.put("itemCount", 3);
        received.put("total", 359.00);
        received.put("acceptBy", Instant.now().plusSeconds(300).toString());
        received.put("receivedAt", Instant.now().toString());
        List<ConsumerRecord<String, String>> skipped = List.of(
                storeEvent(RESTAURANT, "OrderReceived", newEventId(), orderId, restaurantId, received),
                storeEvent(VENDOR, "OrderReceived", newEventId(), orderId, UUID.randomUUID(), received),
                record("vendor.events", "Vendor", "VendorApproved", newEventId(), UUID.randomUUID().toString(),
                        "{\"vendorId\":\"" + UUID.randomUUID() + "\"}"),
                record("vendor.events", "Vendor", "VendorSuspended", newEventId(), UUID.randomUUID().toString(),
                        "{\"reason\":\"x\"}"),
                record("restaurant.events", "Restaurant", "RestaurantListingUpdated", newEventId(),
                        UUID.randomUUID().toString(), "{\"acceptingOrders\":false}"),
                record("restaurant.events", "Restaurant", "RestaurantRegistered", newEventId(),
                        UUID.randomUUID().toString(), "not even json"),
                // the other store kind's event type on a topic: not this consumer's business
                record("vendor.events", "RestaurantOrder", "RestaurantOrderAccepted", newEventId(), orderId.toString(),
                        "{\"orderId\":\"" + orderId + "\",\"restaurantId\":\"" + restaurantId
                                + "\",\"readyInMinutes\":5}"));

        for (ConsumerRecord<String, String> record : skipped) {
            onStoreEvent(record);
            assertThat(processed(eventId(record), VENDOR_CONSUMER)).isZero();
            assertThat(processed(eventId(record), RESTAURANT_CONSUMER)).isZero();
        }
        assertThat(order(customerId, orderId)).isEqualTo(before);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
    }

    @Test
    void aStoreEventForAnUnknownOrderIsMarkedAndIgnored() {
        UUID unknown = UUID.randomUUID();
        String vendorEvent = newEventId();
        String restaurantEvent = newEventId();

        vendorListener.on(storeAccepted(VENDOR, vendorEvent, unknown, UUID.randomUUID(), 15, Instant.now()));
        restaurantListener.on(storeRejected(RESTAURANT, restaurantEvent, unknown, UUID.randomUUID(), "TOO_BUSY", null,
                false, null));

        assertThat(processed(vendorEvent, VENDOR_CONSUMER)).isEqualTo(1);
        assertThat(processed(restaurantEvent, RESTAURANT_CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(unknown)).isEmpty();
    }

    @Test
    void brokenStoreEventsAreInvalidAndChangeNothing() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);
        UUID restaurantId = storeIdOf(orderId);
        String noOrder = newEventId();

        assertThatThrownBy(() -> restaurantListener.on(record("restaurant.events", "RestaurantOrder",
                "RestaurantOrderAccepted", noOrder, orderId.toString(),
                "{\"restaurantId\":\"" + restaurantId + "\",\"readyInMinutes\":5}")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> restaurantListener.on(record("restaurant.events", "RestaurantOrder",
                "RestaurantOrderPreparing", newEventId(), orderId.toString(), "{\"orderId\":\"" + orderId + "\"}")))
                .isInstanceOf(InvalidEventException.class);                                  // no restaurantId
        assertThatThrownBy(() -> vendorListener.on(record("vendor.events", "VendorOrder", "VendorOrderReadyForPickup",
                newEventId(), orderId.toString(),
                "{\"orderId\":\"" + orderId + "\",\"restaurantId\":\"" + restaurantId + "\"}")))
                .isInstanceOf(InvalidEventException.class);                                  // no vendorId
        assertThatThrownBy(() -> restaurantListener.on(storeEvent(RESTAURANT, "OrderAccepted", newEventId(), orderId,
                restaurantId, Map.of("acceptedAt", Instant.now().toString()))))
                .isInstanceOf(InvalidEventException.class);                                  // no readyInMinutes
        assertThatThrownBy(() -> restaurantListener.on(storeEvent(RESTAURANT, "OrderRejected", newEventId(), orderId,
                restaurantId, Map.of("auto", false))))
                .isInstanceOf(InvalidEventException.class);                                  // no reason
        assertThatThrownBy(() -> restaurantListener.on(record("restaurant.events", "RestaurantOrder",
                "RestaurantOrderAccepted", newEventId(), orderId.toString(), "not json")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> restaurantListener.on(record("restaurant.events", "RestaurantOrder",
                "RestaurantOrderRejected", newEventId(), orderId.toString(),
                "{\"orderId\":\"not-a-uuid\",\"restaurantId\":\"" + restaurantId + "\",\"reason\":\"OTHER\"}")))
                .isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> headerless = new ConsumerRecord<>("vendor.events", 0, 0L,
                orderId.toString(), "{\"orderId\":\"" + orderId + "\"}");
        assertThatThrownBy(() -> vendorListener.on(headerless)).isInstanceOf(InvalidEventException.class);

        assertThat(processed(noOrder, RESTAURANT_CONSUMER)).isZero();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed");
    }

    /** A lock wait timeout while applying a store event is transient: not marked, the redelivery applies it. */
    @Test
    void aLockWaitTimeoutOnAStoreEventIsTransientAndTheRedeliveryAppliesIt() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        String eventId = newEventId();
        ConsumerRecord<String, String> accepted = storeAccepted(VENDOR, eventId, orderId, vendorId, 15, Instant.now());

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT status FROM customer_order WHERE id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, orderId.toString());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            assertThatThrownBy(() -> vendorListener.on(accepted))
                    .satisfies(e -> assertThat(TransientFailures.isTransient(e)).as(e.toString()).isTrue());
            blocker.rollback();
        }
        assertThat(processed(eventId, VENDOR_CONSUMER)).isZero();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CONFIRMED");

        vendorListener.on(accepted);                                                // the redelivery
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(processed(eventId, VENDOR_CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderAccepted");
    }
}
