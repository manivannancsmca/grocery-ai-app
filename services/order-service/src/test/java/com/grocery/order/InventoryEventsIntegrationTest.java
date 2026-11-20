package com.grocery.order;

import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * inventory.events → orders: every row of the contract's state-guard table for the reservation events, duplicates,
 * events in other states (ignored), and the events on the topic that are not for orders.
 */
class InventoryEventsIntegrationTest extends AbstractIntegrationTest {

    static final String CONSUMER = "order-service.inventory-events";

    private List<String> timeline(JsonNode order) {
        return order.get("timeline").valueStream()
                .map(e -> e.get("status").asString() + (e.get("reason").isNull() ? "" : ":" + e.get("reason").asString()))
                .toList();
    }

    @Test
    void stockReservedMovesACreatedOrderToAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String eventId = newEventId();

        inventoryListener.on(stockReserved(eventId, orderId));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PENDING");
        assertThat(order.get("cancellable").asBoolean()).isTrue();
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment");
        JsonNode awaiting = orderEvents(orderId, "OrderAwaitingPayment").getFirst();
        assertThat(awaiting.get("amount").decimalValue()).isEqualByComparingTo(GROCERY_TOTAL);
        assertThat(awaiting.get("expiresAt").asString()).isNotBlank();
        assertThat(awaiting.get("contactPhone").asString()).isEqualTo("+919876543210");
        assertThat(outboxRow(orderId, "OrderAwaitingPayment")).containsEntry("topic", "order.events")
                .containsEntry("aggregate_type", "Order");

        // redelivered, or another StockReserved for the same order: nothing changes, nothing is published again
        inventoryListener.on(stockReserved(eventId, orderId));
        String second = newEventId();
        inventoryListener.on(stockReserved(second, orderId));
        assertThat(processed(second, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment");
        assertThat(order(customerId, orderId)).isEqualTo(order);
    }

    @Test
    void stockReservationFailedRejectsACreatedOrderWithTheItems() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        JsonNode created = order(customerId, orderId);
        UUID rice = UUID.fromString(created.get("items").get(0).get("itemId").asString());
        UUID dal = UUID.fromString(created.get("items").get(1).get("itemId").asString());
        Map<UUID, int[]> unavailable = new LinkedHashMap<>();
        unavailable.put(rice, new int[]{2, 1});
        unavailable.put(dal, new int[]{1, 0});
        String eventId = newEventId();

        inventoryListener.on(stockReservationFailed(eventId, orderId, unavailable));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("REJECTED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("NOT_REQUIRED");
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(order.get("cancellation").isNull()).isTrue();
        JsonNode rejection = order.get("rejection");
        assertThat(fields(rejection)).containsExactly("reason", "unavailableItems");
        assertThat(rejection.get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(rejection.get("unavailableItems")).hasSize(2);
        JsonNode first = rejection.get("unavailableItems").get(0);
        assertThat(fields(first)).containsExactly("itemId", "name", "requested", "available");
        assertThat(first.get("itemId").asString()).isEqualTo(rice.toString());
        assertThat(first.get("name").asString()).isEqualTo("Basmati Rice");
        assertThat(first.get("requested").asInt()).isEqualTo(2);
        assertThat(first.get("available").asInt()).isEqualTo(1);
        assertThat(rejection.get("unavailableItems").get(1).get("name").asString()).isEqualTo("Toor Dal");
        assertThat(timeline(order)).containsExactly("CREATED", "REJECTED:OUT_OF_STOCK");

        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderRejected");
        JsonNode rejected = orderEvents(orderId, "OrderRejected").getFirst();
        assertThat(fields(rejected)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "reason", "unavailableItems");
        assertThat(rejected.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(rejected.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(rejected.get("storeId").asString()).isEqualTo(created.get("store").get("id").asString());
        assertThat(rejected.get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(fields(rejected.get("unavailableItems").get(0)))
                .containsExactlyInAnyOrder("itemId", "requested", "available");
        assertThat(rejected.get("unavailableItems").get(1).get("itemId").asString()).isEqualTo(dal.toString());

        // duplicates, a late StockReserved and an expiry change nothing on a rejected order
        inventoryListener.on(stockReservationFailed(eventId, orderId, unavailable));
        inventoryListener.on(stockReservationFailed(newEventId(), orderId, unavailable));
        inventoryListener.on(stockReserved(newEventId(), orderId));
        inventoryListener.on(stockReservationExpired(newEventId(), orderId));
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderRejected");
        assertThat(order(customerId, orderId)).isEqualTo(order);
    }

    @Test
    void reservationEventsForAnOrderPastCreatedAreIgnored() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newRestaurantOrder(customerId);                  // AWAITING_PAYMENT already
        JsonNode before = order(customerId, orderId);
        String failed = newEventId();

        inventoryListener.on(stockReservationFailed(failed, orderId, Map.of(UUID.randomUUID(), new int[]{1, 0})));
        inventoryListener.on(stockReserved(newEventId(), orderId));

        assertThat(processed(failed, CONSUMER)).isEqualTo(1);           // handled once: ignored
        assertThat(order(customerId, orderId)).isEqualTo(before);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment");
    }

    @Test
    void stockReservationExpiredCancelsAnOrderAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        String eventId = newEventId();

        inventoryListener.on(stockReservationExpired(eventId, orderId));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("NOT_REQUIRED");
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        JsonNode cancellation = order.get("cancellation");
        assertThat(fields(cancellation)).containsExactly("reason", "note", "by", "at");
        assertThat(cancellation.get("reason").asString()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(cancellation.get("by").asString()).isEqualTo("SYSTEM");
        assertThat(cancellation.get("note").isNull()).isTrue();
        assertThat(cancellation.get("at").asString()).isEqualTo(order.get("updatedAt").asString());
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT", "CANCELLED:RESERVATION_EXPIRED");

        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(fields(cancelled)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "previousStatus", "reason", "cancelledBy", "actorUserId", "note", "cancelledAt");
        assertThat(cancelled.get("previousStatus").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(cancelled.get("reason").asString()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(cancelled.get("actorUserId").isNull()).isTrue();
        assertThat(cancelled.get("orderType").asString()).isEqualTo("GROCERY");

        inventoryListener.on(stockReservationExpired(eventId, orderId));            // redelivery
        inventoryListener.on(stockReservationExpired(newEventId(), orderId));       // already cancelled
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
    }

    @Test
    void stockReservationExpiredCancelsAConfirmedOrderAndItsPaymentIsRefunded() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        inventoryListener.on(stockReserved(newEventId(), orderId));
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));

        inventoryListener.on(stockReservationExpired(newEventId(), orderId));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");
        assertThat(order.get("payment").get("paidAt").asString()).isNotBlank();
        assertThat(order.get("cancellation").get("reason").asString()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(orderEvents(orderId, "OrderCancelled").getFirst().get("previousStatus").asString())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void stockReservationExpiredIsIgnoredWhileCreated() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String eventId = newEventId();

        inventoryListener.on(stockReservationExpired(eventId, orderId));

        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CREATED");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated");
    }

    @Test
    void otherInventoryEventsAreIgnoredWithoutTouchingTheDatabase() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        JsonNode before = order(customerId, orderId);
        List<ConsumerRecord<String, String>> others = List.of(
                inventoryEvent("StockReleased", newEventId(), orderId, Map.of("orderId", orderId.toString(),
                        "reservationId", UUID.randomUUID().toString(), "reason", "ORDER_CANCELLED")),
                record("inventory.events", "Stock", "StockAdjusted", newEventId(), UUID.randomUUID().toString(),
                        "{\"productId\":\"" + UUID.randomUUID() + "\",\"delta\":5}"),
                record("inventory.events", "Stock", "ProductAvailabilityChanged", newEventId(),
                        UUID.randomUUID().toString(), "{\"inStock\":false,\"stockVersion\":3}"),
                record("inventory.events", "Stock", "LowStockDetected", newEventId(), UUID.randomUUID().toString(),
                        "{\"available\":1,\"threshold\":5}"));

        for (ConsumerRecord<String, String> record : others) {
            inventoryListener.on(record);
            String eventId = new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
            assertThat(processed(eventId, CONSUMER)).isZero();
        }
        assertThat(order(customerId, orderId)).isEqualTo(before);
    }

    @Test
    void anEventForAnUnknownOrderIsMarkedAndIgnored() {
        UUID unknown = UUID.randomUUID();
        String eventId = newEventId();

        inventoryListener.on(stockReserved(eventId, unknown));

        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEventTypes(unknown)).isEmpty();
    }

    @Test
    void brokenEventsAreInvalidAndChangeNothing() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String noOrder = newEventId();

        assertThatThrownBy(() -> inventoryListener.on(inventoryEvent("StockReserved", noOrder, orderId,
                Map.of("reservationId", UUID.randomUUID().toString()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> inventoryListener.on(inventoryEvent("StockReservationFailed", newEventId(), orderId,
                Map.of("orderId", orderId.toString(), "unavailableItems", List.of(Map.of("requested", 1))))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> inventoryListener.on(record("inventory.events", "Reservation", "StockReserved",
                newEventId(), orderId.toString(), "not json")))
                .isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> headerless = new ConsumerRecord<>("inventory.events", 0, 0L,
                orderId.toString(), "{\"orderId\":\"" + orderId + "\"}");
        assertThatThrownBy(() -> inventoryListener.on(headerless)).isInstanceOf(InvalidEventException.class);

        assertThat(processed(noOrder, CONSUMER)).isZero();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CREATED");
    }
}
