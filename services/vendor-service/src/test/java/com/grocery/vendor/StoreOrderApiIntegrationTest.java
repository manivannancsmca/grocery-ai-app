package com.grocery.vendor;

import com.grocery.common.id.Ids;
import com.grocery.vendor.entity.RejectionReason;
import com.grocery.vendor.event.VendorOrderEvents;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The vendor order board API (contract §2): views, transitions, events, idempotent repeats, every 409, ownership. */
class StoreOrderApiIntegrationTest extends StoreOrderTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-10T04:30:00Z");       // 10:00 India time

    private ResultActions action(Store store, UUID orderId, String action, String body) throws Exception {
        var request = post("/api/vendor/orders/{id}/" + action, orderId).with(vendor(store));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private ResultActions action(Store store, UUID orderId, String action) throws Exception {
        return action(store, orderId, action, null);
    }

    private static List<String> orderIds(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(order -> order.get("orderId").asString()).toList();
    }

    @Test
    void acceptPublishesVendorOrderAcceptedAndReturnsTheWholeView() throws Exception {
        Store store = newStore();
        clock.set(T0);
        UUID orderId = confirm(store);
        Instant acceptedAt = T0.plusSeconds(90);
        clock.set(acceptedAt);

        String response = action(store, orderId, "accept", "{\"readyInMinutes\":20}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(response);
        assertThat(fieldNames(view)).containsExactly("orderId", "orderNumber", "storeId", "status", "items",
                "itemCount", "subtotal", "total", "currency", "customer", "note", "receivedAt", "acceptBy",
                "acceptedAt", "readyInMinutes", "readyBy", "preparingAt", "readyAt", "rejection", "cancellation",
                "timeline");
        assertThat(view.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(view.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
        assertThat(view.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(fieldNames(view.get("items").get(0)))
                .containsExactly("itemId", "name", "quantity", "unitPrice", "lineTotal");
        assertThat(view.get("currency").asString()).isEqualTo("INR");
        assertThat(Instant.parse(view.get("receivedAt").asString())).isEqualTo(T0);
        assertThat(Instant.parse(view.get("acceptBy").asString())).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(Instant.parse(view.get("acceptedAt").asString())).isEqualTo(acceptedAt);
        assertThat(view.get("readyInMinutes").asInt()).isEqualTo(20);
        assertThat(Instant.parse(view.get("readyBy").asString())).isEqualTo(acceptedAt.plus(Duration.ofMinutes(20)));
        assertThat(view.get("preparingAt").isNull()).isTrue();
        assertThat(view.get("readyAt").isNull()).isTrue();
        assertThat(view.get("rejection").isNull()).isTrue();
        assertThat(view.get("cancellation").isNull()).isTrue();
        assertThat(view.get("timeline")).hasSize(2);
        assertThat(fieldNames(view.get("timeline").get(0))).containsExactly("status", "at");
        assertThat(view.get("timeline").get(0).get("status").asString()).isEqualTo("NEW");
        assertThat(Instant.parse(view.get("timeline").get(0).get("at").asString())).isEqualTo(T0);
        assertThat(view.get("timeline").get(1).get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(Instant.parse(view.get("timeline").get(1).get("at").asString())).isEqualTo(acceptedAt);
        assertThat(response).contains("\"subtotal\":220.00", "\"total\":245.00", "\"unitPrice\":50.00");

        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED);
        assertThat(outboxRow(orderId, VendorOrderEvents.ACCEPTED))
                .containsEntry("topic", "vendor.events")
                .containsEntry("aggregate_type", "VendorOrder")
                .containsEntry("aggregate_id", orderId.toString());
        JsonNode accepted = storeEvent(orderId, VendorOrderEvents.ACCEPTED);
        assertThat(fieldNames(accepted)).containsExactlyInAnyOrder("orderId", "orderNumber", "vendorId",
                "readyInMinutes", "readyBy", "acceptedAt", "actorUserId");
        assertThat(accepted.get("vendorId").asString()).isEqualTo(store.vendorId().toString());
        assertThat(accepted.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
        assertThat(accepted.get("readyInMinutes").asInt()).isEqualTo(20);
        assertThat(Instant.parse(accepted.get("readyBy").asString())).isEqualTo(acceptedAt.plus(Duration.ofMinutes(20)));
        assertThat(Instant.parse(accepted.get("acceptedAt").asString())).isEqualTo(acceptedAt);
        assertThat(accepted.get("actorUserId").asString()).isEqualTo(store.ownerUserId().toString());

        // Accepting again: 200, unchanged, nothing published (also with another readyInMinutes)
        clock.set(acceptedAt.plusSeconds(30));
        action(store, orderId, "accept", "{\"readyInMinutes\":45}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.readyInMinutes").value(20))
                .andExpect(jsonPath("$.timeline.length()").value(2));
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED);
        assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED");
    }

    @Test
    void acceptWithoutBodyUsesTheDefaultAndValidatesReadyInMinutes() throws Exception {
        Store store = newStore();
        UUID orderId = confirm(store);
        action(store, orderId, "accept", "{\"readyInMinutes\":4}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("readyInMinutes"));
        action(store, orderId, "accept", "{\"readyInMinutes\":181}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(orderStatus(orderId)).isEqualTo("NEW");

        action(store, orderId, "accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyInMinutes").value(15));                // grocery default
        UUID other = confirm(store);
        action(store, other, "accept", "{}").andExpect(jsonPath("$.readyInMinutes").value(15));
        UUID bounds = confirm(store);
        action(store, bounds, "accept", "{\"readyInMinutes\":180}").andExpect(jsonPath("$.readyInMinutes").value(180));
        UUID low = confirm(store);
        action(store, low, "accept", "{\"readyInMinutes\":5}").andExpect(jsonPath("$.readyInMinutes").value(5));
    }

    @Test
    void acceptIsOnlyPossibleWhileNewAndBeforeTheDeadline() throws Exception {
        Store store = newStore();
        clock.set(T0);
        UUID late = confirm(store);
        UUID onTime = confirm(store);

        clock.set(T0.plus(Duration.ofMinutes(5)).plusNanos(1_000));                  // 1 µs past acceptBy
        action(store, late, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCEPTANCE_EXPIRED"));
        assertThat(orderStatus(late)).isEqualTo("NEW");                                    // the poller rejects it
        assertThat(storeEventTypes(late)).containsExactly(VendorOrderEvents.RECEIVED);

        clock.set(T0.plus(Duration.ofMinutes(5)));                                    // exactly at acceptBy: still in time
        action(store, onTime, "accept").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Not NEW: 409 STORE_ORDER_NOT_PENDING with the current status
        clock.reset();
        UUID rejected = confirm(store);
        storeOrders.reject(vendorUser(store), rejected, RejectionReason.TOO_BUSY, null);
        String problem = action(store, rejected, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"))
                .andReturn().getResponse().getContentAsString();
        // the store status is "currentStatus"; "status" stays RFC 9457's HTTP status, once
        assertThat(json.readTree(problem).get("status").asInt()).isEqualTo(409);
        assertThat(problem.split("\"status\":", -1)).hasSize(2);
        UUID cancelled = confirm(store);
        cancel(newEventId(), store, cancelled, "2026-11-11T10:06:00Z");
        action(store, cancelled, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        UUID preparing = confirm(store);
        action(store, preparing, "accept").andExpect(status().isOk());
        action(store, preparing, "preparing").andExpect(status().isOk());
        action(store, preparing, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("PREPARING"));
        assertThat(storeEventTypes(preparing)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED,
                VendorOrderEvents.PREPARING);
    }

    @Test
    void rejectPublishesVendorOrderRejectedAndIsIdempotent() throws Exception {
        Store store = newStore();
        UUID orderId = confirm(store);

        JsonNode view = body(action(store, orderId, "reject", "{\"reason\":\"OUT_OF_STOCK\",\"note\":\" Dal sold out \"}")
                .andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("REJECTED");
        assertThat(fieldNames(view.get("rejection"))).containsExactly("reason", "note", "auto", "at");
        assertThat(view.get("rejection").get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(view.get("rejection").get("note").asString()).isEqualTo("Dal sold out");
        assertThat(view.get("rejection").get("auto").asBoolean()).isFalse();
        assertThat(view.get("acceptedAt").isNull()).isTrue();
        assertThat(view.get("timeline").get(1).get("status").asString()).isEqualTo("REJECTED");

        JsonNode rejected = storeEvent(orderId, VendorOrderEvents.REJECTED);
        assertThat(fieldNames(rejected)).containsExactlyInAnyOrder("orderId", "orderNumber", "vendorId", "reason",
                "note", "auto", "rejectedAt", "actorUserId");
        assertThat(rejected.get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(rejected.get("note").asString()).isEqualTo("Dal sold out");
        assertThat(rejected.get("auto").asBoolean()).isFalse();
        assertThat(rejected.get("actorUserId").asString()).isEqualTo(store.ownerUserId().toString());
        assertThat(Instant.parse(rejected.get("rejectedAt").asString()))
                .isEqualTo(Instant.parse(view.get("rejection").get("at").asString()));
        assertThat(outboxRow(orderId, VendorOrderEvents.REJECTED)).containsEntry("aggregate_type", "VendorOrder");

        // Rejecting again (any reason): 200 unchanged, nothing published
        action(store, orderId, "reject", "{\"reason\":\"TOO_BUSY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejection.reason").value("OUT_OF_STOCK"));
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        assertThat(history(orderId)).containsExactly("NEW", "REJECTED");

        // A store reason without a note: note null in the view and the event
        UUID busy = confirm(store);
        action(store, busy, "reject", "{\"reason\":\"STORE_CLOSED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejection.note").isEmpty());
        assertThat(storeEvent(busy, VendorOrderEvents.REJECTED).get("note").isNull()).isTrue();
    }

    @Test
    void rejectValidatesTheReasonAndTheNoteAndIsOnlyPossibleWhileNew() throws Exception {
        Store store = newStore();
        UUID orderId = confirm(store);
        action(store, orderId, "reject", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        action(store, orderId, "reject", "{\"reason\":\"NO_RESPONSE\"}")              // only the poller says that
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        action(store, orderId, "reject", "{\"reason\":\"BORED\"}")
                .andExpect(status().isBadRequest());
        action(store, orderId, "reject", "{\"reason\":\"OTHER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        action(store, orderId, "reject", "{\"reason\":\"OTHER\",\"note\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        action(store, orderId, "reject", "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(301) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        action(store, orderId, "reject").andExpect(status().isBadRequest());           // no body
        assertThat(orderStatus(orderId)).isEqualTo("NEW");
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED);

        action(store, orderId, "reject", "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(300) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejection.reason").value("OTHER"));

        UUID accepted = confirm(store);
        action(store, accepted, "accept").andExpect(status().isOk());
        action(store, accepted, "reject", "{\"reason\":\"TOO_BUSY\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("ACCEPTED"));
        UUID cancelled = confirm(store);
        cancel(newEventId(), store, cancelled, "2026-11-11T10:06:00Z");
        action(store, cancelled, "reject", "{\"reason\":\"TOO_BUSY\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
    }

    @Test
    void packingAndReadyMoveAnAcceptedOrderOnAndPublishTheirEvents() throws Exception {
        Store store = newStore();
        clock.set(T0);
        UUID orderId = confirm(store);
        clock.set(T0.plusSeconds(60));
        action(store, orderId, "accept").andExpect(status().isOk());

        clock.set(T0.plusSeconds(120));
        action(store, orderId, "preparing")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.preparingAt").value("2026-11-10T04:32:00Z"));
        action(store, orderId, "preparing").andExpect(status().isOk());                  // repeat: unchanged
        clock.set(T0.plusSeconds(600));
        action(store, orderId, "ready")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.readyAt").value("2026-11-10T04:40:00Z"))
                .andExpect(jsonPath("$.preparingAt").value("2026-11-10T04:32:00Z"))
                .andExpect(jsonPath("$.timeline.length()").value(4));
        action(store, orderId, "ready").andExpect(status().isOk());                      // repeat: unchanged
        action(store, orderId, "preparing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STORE_ORDER_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("READY_FOR_PICKUP"));

        assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP");
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED,
                VendorOrderEvents.PREPARING, VendorOrderEvents.READY_FOR_PICKUP);
        JsonNode preparing = storeEvent(orderId, VendorOrderEvents.PREPARING);
        assertThat(fieldNames(preparing)).containsExactlyInAnyOrder("orderId", "orderNumber", "vendorId",
                "preparingAt", "actorUserId");
        assertThat(Instant.parse(preparing.get("preparingAt").asString())).isEqualTo(T0.plusSeconds(120));
        assertThat(preparing.get("actorUserId").asString()).isEqualTo(store.ownerUserId().toString());
        JsonNode ready = storeEvent(orderId, VendorOrderEvents.READY_FOR_PICKUP);
        assertThat(fieldNames(ready)).containsExactlyInAnyOrder("orderId", "orderNumber", "vendorId", "readyAt",
                "actorUserId");
        assertThat(Instant.parse(ready.get("readyAt").asString())).isEqualTo(T0.plusSeconds(600));
        assertThat(ready.get("vendorId").asString()).isEqualTo(store.vendorId().toString());
        assertThat(outboxRow(orderId, VendorOrderEvents.READY_FOR_PICKUP))
                .containsEntry("topic", "vendor.events").containsEntry("aggregate_id", orderId.toString());

        // A small order goes from ACCEPTED straight to READY_FOR_PICKUP
        UUID small = confirm(store);
        action(store, small, "accept").andExpect(status().isOk());
        action(store, small, "ready")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preparingAt").isEmpty());
        assertThat(history(small)).containsExactly("NEW", "ACCEPTED", "READY_FOR_PICKUP");
    }

    @Test
    void packingAndReadyNeedAnAcceptedOrder() throws Exception {
        Store store = newStore();
        UUID fresh = confirm(store);
        action(store, fresh, "preparing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STORE_ORDER_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("NEW"));
        action(store, fresh, "ready")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STORE_ORDER_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("NEW"));

        UUID rejected = confirm(store);
        action(store, rejected, "reject", "{\"reason\":\"TOO_BUSY\"}").andExpect(status().isOk());
        action(store, rejected, "preparing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
        action(store, rejected, "ready")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STORE_ORDER_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));

        UUID cancelled = confirm(store);
        action(store, cancelled, "accept").andExpect(status().isOk());
        cancel(newEventId(), store, cancelled, "2026-11-11T10:06:00Z");
        action(store, cancelled, "ready")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        action(store, cancelled, "preparing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        assertThat(storeEventTypes(fresh)).containsExactly(VendorOrderEvents.RECEIVED);
        assertThat(storeEventTypes(cancelled)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED);
    }

    @Test
    void theBoardListsNewOrdersByDeadlineFirstThenTheRestNewestFirst() throws Exception {
        Store store = newStore();
        Store neighbour = newStore();
        clock.set(T0);
        UUID a = confirm(store);
        clock.set(T0.plusSeconds(60));
        UUID b = confirm(store);
        clock.set(T0.plusSeconds(120));
        UUID c = confirm(store);
        action(store, c, "accept").andExpect(status().isOk());
        clock.set(T0.plusSeconds(180));
        UUID d = confirm(store);
        action(store, d, "reject", "{\"reason\":\"TOO_BUSY\"}").andExpect(status().isOk());
        clock.set(T0.plusSeconds(240));
        UUID e = confirm(store);
        cancel(newEventId(), store, e, "2026-11-10T04:35:00Z");
        confirm(neighbour);                                                           // another store's order

        JsonNode all = body(mvc.perform(get("/api/vendor/orders").with(vendor(store))).andExpect(status().isOk()));
        assertThat(orderIds(all)).containsExactly(a.toString(), b.toString(), e.toString(), d.toString(), c.toString());
        assertThat(all.get("totalElements").asLong()).isEqualTo(5);
        assertThat(fieldNames(all)).containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");

        JsonNode active = body(mvc.perform(get("/api/vendor/orders").param("status", "ACTIVE").with(vendor(store))));
        assertThat(orderIds(active)).containsExactly(a.toString(), b.toString(), c.toString());
        JsonNode fresh = body(mvc.perform(get("/api/vendor/orders").param("status", "NEW").with(vendor(store))));
        assertThat(orderIds(fresh)).containsExactly(a.toString(), b.toString());
        JsonNode rejected = body(mvc.perform(get("/api/vendor/orders").param("status", "REJECTED").with(vendor(store))));
        assertThat(orderIds(rejected)).containsExactly(d.toString());
        JsonNode cancelled = body(mvc.perform(get("/api/vendor/orders").param("status", "CANCELLED").with(vendor(store))));
        assertThat(orderIds(cancelled)).containsExactly(e.toString());

        JsonNode page0 = body(mvc.perform(get("/api/vendor/orders").param("size", "2").with(vendor(store))));
        assertThat(orderIds(page0)).containsExactly(a.toString(), b.toString());
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        JsonNode page1 = body(mvc.perform(get("/api/vendor/orders").param("size", "2").param("page", "1")
                .with(vendor(store))));
        assertThat(orderIds(page1)).containsExactly(e.toString(), d.toString());
        mvc.perform(get("/api/vendor/orders").param("size", "1000").with(vendor(store)))
                .andExpect(jsonPath("$.size").value(100));

        mvc.perform(get("/api/vendor/orders").param("status", "SHIPPED").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/orders").param("page", "abc").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void onlyTheOwningVendorSeesOrChangesAnOrder() throws Exception {
        Store store = newStore();
        Store other = newStore();
        UUID orderId = confirm(store);

        mvc.perform(get("/api/vendor/orders/{id}", orderId).with(vendor(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_FOUND"));
        for (String step : List.of("accept", "preparing", "ready")) {
            action(other, orderId, step)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_FOUND"));
        }
        action(other, orderId, "reject", "{\"reason\":\"TOO_BUSY\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_FOUND"));
        mvc.perform(get("/api/vendor/orders/{id}", Ids.newId()).with(vendor(store)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STORE_ORDER_NOT_FOUND"));
        action(store, Ids.newId(), "accept").andExpect(status().isNotFound());
        assertThat(orderIds(body(mvc.perform(get("/api/vendor/orders").with(vendor(other)))))).isEmpty();
        assertThat(orderStatus(orderId)).isEqualTo("NEW");
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED);

        // Role and claim: a VENDOR without an approved vendor (no vendor_id claim) → 403; other roles → 403
        mvc.perform(get("/api/vendor/orders").with(as(store.ownerUserId(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(post("/api/vendor/orders/{id}/accept", orderId).with(as(store.ownerUserId(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/orders/{id}", orderId)
                        .with(as(UUID.randomUUID(), store.vendorId(), "RESTAURANT_OWNER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/orders").with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/vendor/orders/{id}", "not-a-uuid").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        assertThat(orderStatus(orderId)).isEqualTo("NEW");
    }
}
