package com.grocery.restaurant;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.restaurant.event.RestaurantOrderEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order.events → the restaurant order board (contract §2 "Created from"): the listener is called directly, as Kafka would,
 * with the routing data in the record headers.
 */
class OrderEventsProjectionIntegrationTest extends StoreOrderTestSupport {

    private static final Instant RECEIVED = Instant.parse("2026-11-11T09:00:00Z");

    @Test
    void orderConfirmedPutsANewOrderOnTheBoardAndPublishesRestaurantOrderReceived() throws Exception {
        Store store = newStore();
        UUID orderId = Ids.newId();
        UUID rice = Ids.newId();
        UUID dal = Ids.newId();
        String eventId = newEventId();
        clock.set(RECEIVED);

        orderListener.on(orderConfirmed(eventId, confirmedPayload(orderId, store.restaurantId(), RESTAURANT,
                List.of(line(rice, "Veg Biryani", 2, "50.00"), line(dal, "Dal Makhani", 1, "120.00")))));

        assertThat(orderStatus(orderId)).isEqualTo("NEW");
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(history(orderId)).containsExactly("NEW");
        assertThat(instantColumn(orderId, "received_at")).isEqualTo(RECEIVED);
        assertThat(instantColumn(orderId, "accept_by")).isEqualTo(RECEIVED.plus(Duration.ofMinutes(5)));
        assertThat(instantColumn(orderId, "confirmed_at")).isEqualTo(Instant.parse("2026-11-11T10:05:00.123456Z"));

        // RestaurantOrderReceived: exactly the schema's fields, keyed by the order id, aggregate RestaurantOrder
        assertThat(storeEventTypes(orderId)).containsExactly(RestaurantOrderEvents.RECEIVED);
        assertThat(outboxRow(orderId, RestaurantOrderEvents.RECEIVED))
                .containsEntry("topic", "restaurant.events")
                .containsEntry("aggregate_type", "RestaurantOrder")
                .containsEntry("aggregate_id", orderId.toString());
        JsonNode received = storeEvent(orderId, RestaurantOrderEvents.RECEIVED);
        assertThat(fieldNames(received)).containsExactlyInAnyOrder("orderId", "orderNumber", "restaurantId", "ownerUserId",
                "itemCount", "total", "acceptBy", "receivedAt");
        assertThat(received.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(received.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
        assertThat(received.get("restaurantId").asString()).isEqualTo(store.restaurantId().toString());
        assertThat(received.get("ownerUserId").asString()).isEqualTo(store.ownerUserId().toString());
        assertThat(received.get("itemCount").asInt()).isEqualTo(3);
        assertThat(received.get("total").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(Instant.parse(received.get("acceptBy").asString())).isEqualTo(RECEIVED.plus(Duration.ofMinutes(5)));
        assertThat(Instant.parse(received.get("receivedAt").asString())).isEqualTo(RECEIVED);

        // The board shows the snapshot: lines, totals, the customer's name and area (no phone, no street), the note
        JsonNode view = body(mvc.perform(get("/api/restaurant/orders/{id}", orderId).with(owner(store)))
                .andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("NEW");
        assertThat(view.get("storeId").asString()).isEqualTo(store.restaurantId().toString());
        assertThat(view.get("items")).hasSize(2);
        assertThat(view.get("items").get(0).get("itemId").asString()).isEqualTo(rice.toString());
        assertThat(view.get("items").get(0).get("name").asString()).isEqualTo("Veg Biryani");
        assertThat(view.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(view.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(view.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(view.get("items").get(1).get("name").asString()).isEqualTo("Dal Makhani");
        assertThat(view.get("itemCount").asInt()).isEqualTo(3);
        assertThat(view.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(view.get("total").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(view.get("customer").get("name").asString()).isEqualTo("Ravi Kumar");
        assertThat(view.get("customer").get("area").asString()).isEqualTo("Indiranagar, Bengaluru");
        assertThat(fieldNames(view.get("customer"))).containsExactlyInAnyOrder("name", "area");
        assertThat(view.get("note").asString()).isEqualTo("Ring the bell twice");
        assertThat(view.toString()).doesNotContain("+919876543210").doesNotContain("HAL 2nd Stage");
    }

    @Test
    void onlyRestaurantOrdersOfKnownRestaurantsAreProjected() {
        Store store = newStore();
        // A grocery order: vendor-service's board; not even marked processed here
        UUID otherTypeOrder = Ids.newId();
        String otherTypeEvent = newEventId();
        orderListener.on(orderConfirmed(otherTypeEvent,
                confirmedPayload(otherTypeOrder, store.restaurantId(), GROCERY, defaultLines())));
        assertThat(orderCount(otherTypeOrder)).isZero();
        assertThat(processed(otherTypeEvent)).isZero();

        // An unknown restaurant: logged and skipped (the event is done with)
        UUID unknownStoreOrder = Ids.newId();
        String unknownStoreEvent = newEventId();
        orderListener.on(orderConfirmed(unknownStoreEvent,
                confirmedPayload(unknownStoreOrder, UUID.randomUUID(), RESTAURANT, defaultLines())));
        assertThat(orderCount(unknownStoreOrder)).isZero();
        assertThat(storeEventTypes(unknownStoreOrder)).isEmpty();
        assertThat(processed(unknownStoreEvent)).isEqualTo(1);

        // Event types this service does not use
        UUID other = Ids.newId();
        orderListener.on(orderEvent("OrderCreated", newEventId(), other, Map.of("orderId", other.toString(),
                "orderType", RESTAURANT)));
        orderListener.on(orderEvent("OrderAccepted", newEventId(), other, Map.of("orderId", other.toString())));
        assertThat(orderCount(other)).isZero();
    }

    @Test
    void duplicatesAreIgnored() {
        Store store = newStore();
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        Map<String, Object> payload = confirmedPayload(orderId, store.restaurantId(), RESTAURANT, defaultLines());

        orderListener.on(orderConfirmed(eventId, payload));
        orderListener.on(orderConfirmed(eventId, payload));                    // Kafka redelivery: processed_event
        orderListener.on(orderConfirmed(newEventId(), payload));               // a replay with another event id

        assertThat(orderCount(orderId)).isEqualTo(1);
        assertThat(history(orderId)).containsExactly("NEW");
        assertThat(storeEventTypes(orderId)).containsExactly(RestaurantOrderEvents.RECEIVED);
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void anOlderOrderConfirmedWithoutSubtotalAndNoteFallsBackToTheLineTotals() throws Exception {
        Store store = newStore();
        UUID orderId = Ids.newId();
        Map<String, Object> payload = confirmedPayload(orderId, store.restaurantId(), RESTAURANT,
                List.of(line(Ids.newId(), "Gulab Jamun", 1, "300.00"), line(Ids.newId(), "Sweet Lassi", 3, "20.50")));
        payload.remove("subtotal");                                            // a v1 event from before Phase 8
        payload.remove("note");
        // an address with a landmark: the landmark is the area; no recipient name
        Map<String, Object> address = new HashMap<>(castMap(payload.get("deliveryAddress")));
        address.put("landmark", "Near Metro Station");
        address.put("recipientName", null);
        payload.put("deliveryAddress", address);

        orderListener.on(orderConfirmed(newEventId(), payload));

        JsonNode view = body(mvc.perform(get("/api/restaurant/orders/{id}", orderId).with(owner(store)))
                .andExpect(status().isOk()));
        assertThat(view.get("subtotal").decimalValue()).isEqualByComparingTo("361.50");   // 300.00 + 3 × 20.50
        assertThat(view.get("total").decimalValue()).isEqualByComparingTo("386.50");
        assertThat(view.get("itemCount").asInt()).isEqualTo(4);
        assertThat(view.get("note").isNull()).isTrue();
        assertThat(view.get("customer").get("name").isNull()).isTrue();
        assertThat(view.get("customer").get("area").asString()).isEqualTo("Near Metro Station, Bengaluru");
        assertThat(mvc.perform(get("/api/restaurant/orders/{id}", orderId).with(owner(store)))
                .andReturn().getResponse().getContentAsString()).contains("\"subtotal\":361.50");
    }

    @Test
    void theAreaIsTheCityAloneWithoutLine2OrLandmarkAndNullWithoutAnAddress() throws Exception {
        Store store = newStore();
        UUID cityOnly = Ids.newId();
        Map<String, Object> payload = confirmedPayload(cityOnly, store.restaurantId(), RESTAURANT, defaultLines());
        Map<String, Object> address = new HashMap<>(castMap(payload.get("deliveryAddress")));
        address.put("line2", "  ");
        payload.put("deliveryAddress", address);
        orderListener.on(orderConfirmed(newEventId(), payload));

        UUID noAddress = Ids.newId();
        Map<String, Object> bare = confirmedPayload(noAddress, store.restaurantId(), RESTAURANT, defaultLines());
        bare.put("deliveryAddress", null);
        bare.put("note", "   ");
        orderListener.on(orderConfirmed(newEventId(), bare));

        JsonNode first = body(mvc.perform(get("/api/restaurant/orders/{id}", cityOnly).with(owner(store))));
        assertThat(first.get("customer").get("area").asString()).isEqualTo("Bengaluru");
        JsonNode second = body(mvc.perform(get("/api/restaurant/orders/{id}", noAddress).with(owner(store))));
        assertThat(second.get("customer").get("name").isNull()).isTrue();
        assertThat(second.get("customer").get("area").isNull()).isTrue();
        assertThat(second.get("note").isNull()).isTrue();
    }

    @Test
    void orderCancelledTakesAnyNonTerminalOrderOffTheBoardWithoutAnEvent() throws Exception {
        Store store = newStore();
        // NEW → CANCELLED
        UUID fresh = confirm(store);
        String cancelEvent = newEventId();
        cancel(cancelEvent, store, fresh, "2026-11-11T10:06:00Z");
        assertThat(orderStatus(fresh)).isEqualTo("CANCELLED");
        assertThat(history(fresh)).containsExactly("NEW", "CANCELLED");
        assertThat(processed(cancelEvent)).isEqualTo(1);
        assertThat(storeEventTypes(fresh)).containsExactly(RestaurantOrderEvents.RECEIVED);
        JsonNode view = body(mvc.perform(get("/api/restaurant/orders/{id}", fresh).with(owner(store)))
                .andExpect(status().isOk()));
        assertThat(view.get("cancellation").get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(view.get("cancellation").get("by").asString()).isEqualTo("CUSTOMER");
        assertThat(Instant.parse(view.get("cancellation").get("at").asString()))
                .isEqualTo(Instant.parse("2026-11-11T10:06:00Z"));
        assertThat(view.get("rejection").isNull()).isTrue();
        assertThat(view.get("timeline").get(1).get("status").asString()).isEqualTo("CANCELLED");

        // ACCEPTED / PREPARING / READY_FOR_PICKUP → CANCELLED (an admin cancels after acceptance)
        for (String step : List.of("accept", "preparing", "ready")) {
            UUID orderId = confirm(store);
            storeOrders.accept(ownerUser(store), orderId, null);
            if (!step.equals("accept")) {
                storeOrders.preparing(ownerUser(store), orderId);
            }
            if (step.equals("ready")) {
                storeOrders.ready(ownerUser(store), orderId);
            }
            List<String> eventsBefore = storeEventTypes(orderId);
            orderListener.on(orderEvent("OrderCancelled", newEventId(), orderId, cancelledPayload(orderId,
                    store.restaurantId(), RESTAURANT, "ACCEPTED", "ADMIN_CANCELLED", "ADMIN", "2026-11-11T10:30:00Z")));
            assertThat(orderStatus(orderId)).as(step).isEqualTo("CANCELLED");
            assertThat(history(orderId)).as(step).endsWith("CANCELLED");
            assertThat(storeEventTypes(orderId)).as(step).isEqualTo(eventsBefore);
            assertThat(orderRow(orderId).get("accepted_at")).as(step).isNotNull();   // accepted, later cancelled
        }

        // Missing cancelledAt: the time it was applied
        UUID noTime = confirm(store);
        orderListener.on(orderEvent("OrderCancelled", newEventId(), noTime, cancelledPayload(noTime, store.restaurantId(),
                RESTAURANT, "CONFIRMED", "ADMIN_CANCELLED", "ADMIN", null)));
        assertThat(orderRow(noTime).get("cancelled_at")).isNotNull();
    }

    @Test
    void orderCancelledLeavesTerminalOrdersAndUnknownOrdersAlone() throws Exception {
        Store store = newStore();
        UUID rejected = confirm(store);
        storeOrders.reject(ownerUser(store), rejected, com.grocery.restaurant.entity.RejectionReason.TOO_BUSY, null);
        // order-service answers the rejection with OrderCancelled(STORE_REJECTED): the store order stays REJECTED
        orderListener.on(orderEvent("OrderCancelled", newEventId(), rejected, cancelledPayload(rejected,
                store.restaurantId(), RESTAURANT, "CONFIRMED", "STORE_REJECTED", "STORE", "2026-11-11T10:07:00Z")));
        assertThat(orderStatus(rejected)).isEqualTo("REJECTED");
        assertThat(history(rejected)).containsExactly("NEW", "REJECTED");

        UUID cancelled = confirm(store);
        cancel(newEventId(), store, cancelled, "2026-11-11T10:06:00Z");
        cancel(newEventId(), store, cancelled, "2026-11-11T10:09:00Z");          // another cancellation: no-op
        assertThat(history(cancelled)).containsExactly("NEW", "CANCELLED");
        assertThat(instantColumn(cancelled, "cancelled_at")).isEqualTo(Instant.parse("2026-11-11T10:06:00Z"));

        // An order cancelled before it was paid never reached the board; a restaurant order is not ours
        UUID neverConfirmed = Ids.newId();
        String event = newEventId();
        cancel(event, store, neverConfirmed, "2026-11-11T10:06:00Z");
        assertThat(orderCount(neverConfirmed)).isZero();
        assertThat(processed(event)).isEqualTo(1);
        String otherTypeEvent = newEventId();
        UUID mine = confirm(store);
        orderListener.on(orderEvent("OrderCancelled", otherTypeEvent, mine, cancelledPayload(mine, store.restaurantId(),
                GROCERY, "CONFIRMED", "CUSTOMER_CANCELLED", "CUSTOMER", "2026-11-11T10:06:00Z")));
        assertThat(orderStatus(mine)).isEqualTo("NEW");
        assertThat(processed(otherTypeEvent)).isZero();
    }

    @Test
    void unreadableEventsArePoisonForTheDeadLetterTopic() {
        Store store = newStore();
        UUID orderId = Ids.newId();
        Map<String, Object> noItems = confirmedPayload(orderId, store.restaurantId(), RESTAURANT, defaultLines());
        noItems.put("items", List.of());
        assertThatThrownBy(() -> orderListener.on(orderConfirmed(newEventId(), noItems)))
                .isInstanceOf(InvalidEventException.class);

        Map<String, Object> badLine = confirmedPayload(orderId, store.restaurantId(), RESTAURANT, defaultLines());
        Map<String, Object> item = new LinkedHashMap<>(castMap(((List<?>) badLine.get("items")).getFirst()));
        item.put("quantity", 0);
        badLine.put("items", List.of(item));
        assertThatThrownBy(() -> orderListener.on(orderConfirmed(newEventId(), badLine)))
                .isInstanceOf(InvalidEventException.class);

        Map<String, Object> noStore = confirmedPayload(orderId, store.restaurantId(), RESTAURANT, defaultLines());
        noStore.remove("storeId");
        assertThatThrownBy(() -> orderListener.on(orderConfirmed(newEventId(), noStore)))
                .isInstanceOf(InvalidEventException.class);

        assertThatThrownBy(() -> orderListener.on(record("order.events", "Order", "OrderCancelled", newEventId(),
                orderId.toString(), "{\"orderType\":\"RESTAURANT\"}")))
                .isInstanceOf(InvalidEventException.class);
        assertThat(orderCount(orderId)).isZero();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
