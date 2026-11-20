package com.grocery.restaurant;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.restaurant.entity.RejectionReason;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9 (docs/api/phase-9-delivery.md §7): {@code OrderOutForDelivery} → the store order is PICKED_UP — the terminal
 * success, off the ACTIVE board, in the timeline, no event — and the sales report still counts it as accepted and
 * sold.
 */
class PickupIntegrationTest extends StoreOrderTestSupport {

    private ResultActions action(Store store, UUID orderId, String action) throws Exception {
        return mvc.perform(post("/api/restaurant/orders/{id}/" + action, orderId).with(owner(store)));
    }

    private List<String> board(Store store, String status) throws Exception {
        JsonNode page = body(mvc.perform(get("/api/restaurant/orders").param("status", status).with(owner(store)))
                .andExpect(status().isOk()));
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(order -> order.get("orderId").asString()).toList();
    }

    /** A store order the restaurant accepted and marked ready. */
    private UUID ready(Store store) {
        UUID orderId = confirm(store);
        storeOrders.accept(ownerUser(store), orderId, null);
        storeOrders.ready(ownerUser(store), orderId);
        return orderId;
    }

    @Test
    void orderOutForDeliveryTakesAReadyOrderOffTheBoardAsPickedUp() throws Exception {
        Store store = newStore();
        UUID orderId = ready(store);
        List<String> eventsBefore = storeEventTypes(orderId);
        String eventId = newEventId();

        pickUp(eventId, store, orderId, "2026-11-11T10:45:00.123456Z");

        assertThat(orderStatus(orderId)).isEqualTo("PICKED_UP");
        assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED", "READY_FOR_PICKUP", "PICKED_UP");
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(storeEventTypes(orderId)).isEqualTo(eventsBefore);                   // no event
        JsonNode view = body(mvc.perform(get("/api/restaurant/orders/{id}", orderId).with(owner(store)))
                .andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("PICKED_UP");
        JsonNode last = view.get("timeline").get(3);
        assertThat(last.get("status").asString()).isEqualTo("PICKED_UP");
        assertThat(Instant.parse(last.get("at").asString())).isEqualTo(Instant.parse("2026-11-11T10:45:00.123456Z"));
        assertThat(view.get("cancellation").isNull()).isTrue();
        assertThat(view.get("rejection").isNull()).isTrue();

        // off the ACTIVE board, still listed by its own status and in "all"
        assertThat(board(store, "ACTIVE")).doesNotContain(orderId.toString());
        assertThat(board(store, "PICKED_UP")).containsExactly(orderId.toString());
        assertThat(board(store, "")).contains(orderId.toString());

        // nothing more can happen to it: conflicts carry the store status as currentStatus
        action(store, orderId, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.status").value(409));
        action(store, orderId, "ready")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STORE_ORDER_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"));
        action(store, orderId, "preparing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"));
        // a redelivery, a second pickup event and a (late) cancellation change nothing
        pickUp(eventId, store, orderId, "2026-11-11T10:50:00Z");
        pickUp(newEventId(), store, orderId, "2026-11-11T10:50:00Z");
        cancel(newEventId(), store, orderId, "2026-11-11T10:55:00Z");
        assertThat(orderStatus(orderId)).isEqualTo("PICKED_UP");
        assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED", "READY_FOR_PICKUP", "PICKED_UP");
        assertThat(storeEventTypes(orderId)).isEqualTo(eventsBefore);
    }

    @Test
    void aPickupWithoutATimeUsesTheTimeItWasAppliedAndAnAcceptedOrderIsTakenToo() {
        Store store = newStore();
        UUID packing = confirm(store);
        storeOrders.accept(ownerUser(store), packing, null);
        storeOrders.preparing(ownerUser(store), packing);
        Instant before = Instant.now();

        pickUp(newEventId(), store, packing, null);

        assertThat(orderStatus(packing)).isEqualTo("PICKED_UP");
        assertThat(history(packing)).containsExactly("NEW", "ACCEPTED", "PREPARING", "PICKED_UP");
        Instant at = jdbc().sql("SELECT DATE_FORMAT(changed_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM "
                        + "restaurant_order_status_history WHERE order_id = UUID_TO_BIN(?) AND status = 'PICKED_UP'")
                .param(packing.toString()).query(String.class).single().transform(Instant::parse);
        assertThat(at).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void newTerminalUnknownAndGroceryOrdersAreLeftAlone() {
        Store store = newStore();
        UUID fresh = confirm(store);
        UUID rejected = confirm(store);
        storeOrders.reject(ownerUser(store), rejected, RejectionReason.TOO_BUSY, null);
        UUID cancelled = confirm(store);
        cancel(newEventId(), store, cancelled, "2026-11-11T10:06:00Z");

        for (UUID orderId : List.of(fresh, rejected, cancelled)) {
            String before = orderStatus(orderId);
            List<String> history = history(orderId);
            String eventId = newEventId();
            pickUp(eventId, store, orderId, "2026-11-11T10:45:00Z");
            assertThat(orderStatus(orderId)).isEqualTo(before);
            assertThat(history(orderId)).isEqualTo(history);
            assertThat(processed(eventId)).isEqualTo(1);
        }

        UUID unknown = Ids.newId();
        String unknownEvent = newEventId();
        pickUp(unknownEvent, store, unknown, "2026-11-11T10:45:00Z");
        assertThat(orderCount(unknown)).isZero();
        assertThat(processed(unknownEvent)).isEqualTo(1);

        UUID ready = ready(store);
        String restaurantEvent = newEventId();
        orderListener.on(orderEvent("OrderOutForDelivery", restaurantEvent, ready,
                outForDeliveryPayload(ready, store.restaurantId(), GROCERY, "2026-11-11T10:45:00Z")));
        assertThat(orderStatus(ready)).isEqualTo("READY_FOR_PICKUP");
        assertThat(processed(restaurantEvent)).isZero();

        assertThatThrownBy(() -> orderListener.on(record("order.events", "Order", "OrderOutForDelivery",
                newEventId(), ready.toString(), "{\"orderType\":\"RESTAURANT\"}")))
                .isInstanceOf(InvalidEventException.class);
        assertThat(orderStatus(ready)).isEqualTo("READY_FOR_PICKUP");
    }

    @Test
    void thePickedUpOrderCountsAsAcceptedAndSoldInTheReport() throws Exception {
        Store store = newStore();
        UUID biryani = Ids.newId();
        UUID dalMakhani = Ids.newId();
        // 01 Nov 10:00 India time: picked up (Veg Biryani 2 × 50.00 = 100.00)
        clock.set(Instant.parse("2026-11-01T04:30:00Z"));
        UUID picked = confirm(store, List.of(line(biryani, "Veg Biryani", 2, "50.00")));
        clock.set(Instant.parse("2026-11-01T04:31:00Z"));
        storeOrders.accept(ownerUser(store), picked, null);
        storeOrders.ready(ownerUser(store), picked);
        pickUp(newEventId(), store, picked, "2026-11-01T04:50:00Z");
        // 01 Nov 11:00: still ready (Dal Makhani 1 × 120.00)
        clock.set(Instant.parse("2026-11-01T05:30:00Z"));
        UUID waiting = confirm(store, List.of(line(dalMakhani, "Dal Makhani", 1, "120.00")));
        clock.set(Instant.parse("2026-11-01T05:31:00Z"));
        storeOrders.accept(ownerUser(store), waiting, null);
        storeOrders.ready(ownerUser(store), waiting);
        clock.reset();
        assertThat(orderStatus(picked)).isEqualTo("PICKED_UP");

        JsonNode report = body(mvc.perform(get("/api/restaurant/reports/sales").param("from", "2026-11-01")
                .param("to", "2026-11-01").with(owner(store))).andExpect(status().isOk()));
        assertThat(report.get("ordersReceived").asLong()).isEqualTo(2);
        assertThat(report.get("ordersAccepted").asLong()).isEqualTo(2);
        assertThat(report.get("ordersRejected").asLong()).isZero();
        assertThat(report.get("ordersCancelled").asLong()).isZero();
        assertThat(report.get("acceptanceRate").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(report.get("grossSales").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(report.get("itemsSold").asLong()).isEqualTo(3);
        assertThat(report.get("averageOrderValue").decimalValue()).isEqualByComparingTo("110.00");
        assertThat(StreamSupport.stream(report.get("topItems").spliterator(), false)
                .map(item -> item.get("name").asString() + " " + item.get("quantity").asLong()))
                .containsExactly("Veg Biryani 2", "Dal Makhani 1");
    }
}
