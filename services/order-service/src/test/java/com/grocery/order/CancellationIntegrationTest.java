package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancellation by the customer and by an admin (contract §2, §4): statuses, idempotency, ownership and roles. */
class CancellationIntegrationTest extends AbstractIntegrationTest {

    private ResultActions cancel(UUID customerId, UUID orderId, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orders/{id}/cancel", orderId).with(customer(customerId));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private ResultActions adminCancel(UUID adminId, String role, UUID orderId, String reason) throws Exception {
        return mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).with(as(adminId, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reason == null ? Map.of() : Map.of("reason", reason))));
    }

    @Test
    void theCustomerCancelsACreatedOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        JsonNode order = body(cancel(customerId, orderId, "{\"reason\":\"  Ordered by mistake \"}")
                .andExpect(status().isOk()));

        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(order.get("payment").get("status").asString()).isEqualTo("NOT_REQUIRED");
        JsonNode cancellation = order.get("cancellation");
        assertThat(cancellation.get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(cancellation.get("note").asString()).isEqualTo("Ordered by mistake");
        assertThat(cancellation.get("by").asString()).isEqualTo("CUSTOMER");
        assertThat(cancellation.get("at").asString()).isEqualTo(order.get("updatedAt").asString());
        assertThat(order.get("timeline").get(1).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("timeline").get(1).get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(order(customerId, orderId)).isEqualTo(order);

        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderCancelled");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("previousStatus").asString()).isEqualTo("CREATED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("CUSTOMER");
        assertThat(cancelled.get("actorUserId").asString()).isEqualTo(customerId.toString());
        assertThat(cancelled.get("note").asString()).isEqualTo("Ordered by mistake");
        assertThat(cancelled.get("customerId").asString()).isEqualTo(customerId.toString());
    }

    @Test
    void cancellingTwiceReturnsTheCancelledOrderOnce() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);

        JsonNode first = body(cancel(customerId, orderId, null).andExpect(status().isOk()));   // no body at all
        JsonNode second = body(cancel(customerId, orderId, "{}").andExpect(status().isOk()));
        JsonNode third = body(cancel(customerId, orderId, "{\"reason\":\"again\"}").andExpect(status().isOk()));

        assertThat(first.get("cancellation").get("note").isNull()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
        assertThat(orderEvents(orderId, "OrderCancelled").getFirst().get("previousStatus").asString())
                .isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void aPaidOrderCanBeCancelledAndIsRefunded() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedOrder(customerId);

        cancel(customerId, orderId, "{\"reason\":\"Need it earlier\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.payment.paidAt").isNotEmpty());
        assertThat(orderEvents(orderId, "OrderCancelled").getFirst().get("previousStatus").asString())
                .isEqualTo("CONFIRMED");

        paymentListener.on(refundCompleted(newEventId(), orderId, "359.00", "359.00", true));
        assertThat(order(customerId, orderId).get("payment").get("status").asString()).isEqualTo("REFUNDED");
    }

    @Test
    void aRejectedOrderCannotBeCancelled() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        inventoryListener.on(stockReservationFailed(newEventId(), orderId, Map.of(UUID.randomUUID(), new int[]{2, 0})));

        cancel(customerId, orderId, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"));
        adminCancel(UUID.randomUUID(), "OPS_ADMIN", orderId, "Customer called")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"));
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("REJECTED");
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderRejected");
    }

    @Test
    void customersCancelOnlyTheirOwnOrders() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        cancel(UUID.randomUUID(), orderId, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        cancel(customerId, UUID.randomUUID(), "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        cancel(customerId, orderId, "{\"reason\":\"" + "x".repeat(301) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/orders/{id}/cancel", orderId).with(as(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isUnauthorized());
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CREATED");
    }

    @Test
    void anAdminCancelsAnyOrderWithAReason() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        UUID adminId = UUID.randomUUID();

        JsonNode view = body(adminCancel(adminId, "OPS_ADMIN", orderId, "Store asked to cancel")
                .andExpect(status().isOk()));

        assertThat(fields(view).getFirst()).isEqualTo("customerId");
        assertThat(view.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(view.get("id").asString()).isEqualTo(orderId.toString());
        assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(view.get("cancellation").get("reason").asString()).isEqualTo("ADMIN_CANCELLED");
        assertThat(view.get("cancellation").get("by").asString()).isEqualTo("ADMIN");
        assertThat(view.get("cancellation").get("note").asString()).isEqualTo("Store asked to cancel");
        JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("ADMIN");
        assertThat(cancelled.get("actorUserId").asString()).isEqualTo(adminId.toString());
        assertThat(cancelled.get("reason").asString()).isEqualTo("ADMIN_CANCELLED");

        // idempotent: the cancelled order again (also for SUPPORT), one event
        adminCancel(UUID.randomUUID(), "SUPPORT", orderId, "Again")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation.note").value("Store asked to cancel"));
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
        // the customer sees the admin's cancellation
        cancel(customerId, orderId, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation.reason").value("ADMIN_CANCELLED"));
    }

    @Test
    void onlyOperationsAndSupportMayCancelAndAReasonIsRequired() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        adminCancel(UUID.randomUUID(), "FINANCE_ADMIN", orderId, "No")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        adminCancel(UUID.randomUUID(), "DELIVERY_MANAGER", orderId, "No").andExpect(status().isForbidden());
        adminCancel(customerId, "CUSTOMER", orderId, "No").andExpect(status().isForbidden());
        adminCancel(UUID.randomUUID(), "SUPER_ADMIN", orderId, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        adminCancel(UUID.randomUUID(), "SUPER_ADMIN", orderId, "   ")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        adminCancel(UUID.randomUUID(), "SUPER_ADMIN", orderId, "x".repeat(301)).andExpect(status().isBadRequest());
        adminCancel(UUID.randomUUID(), "SUPER_ADMIN", UUID.randomUUID(), "Unknown")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CREATED");

        adminCancel(UUID.randomUUID(), "SUPER_ADMIN", orderId, "Duplicate order").andExpect(status().isOk());
        mvc.perform(get("/api/admin/orders/{id}", orderId).with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    /** Phase 8: the customer may cancel only until the store accepts; later → 409 (the customer contacts support). */
    @Test
    void theCustomerCannotCancelOnceTheStoreAccepted() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID confirmed = confirmedOrder(customerId);
        assertThat(order(customerId, confirmed).get("cancellable").asBoolean()).isTrue();

        for (UUID orderId : List.of(acceptedOrder(customerId), preparingOrder(customerId), readyOrder(customerId))) {
            JsonNode before = order(customerId, orderId);
            assertThat(before.get("cancellable").asBoolean()).isFalse();

            cancel(customerId, orderId, "{\"reason\":\"Too slow\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"));
            assertThat(order(customerId, orderId)).isEqualTo(before);
            assertThat(orderEventTypes(orderId)).doesNotContain("OrderCancelled");
        }
    }

    /** Phase 8: an admin may cancel any non-terminal order up to READY_FOR_PICKUP; full refund, stock released. */
    @Test
    void anAdminCancelsAcceptedPreparingAndReadyOrders() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID accepted = acceptedOrder(customerId);
        UUID preparing = preparingOrder(customerId);
        UUID ready = readyOrder(customerId);
        Map<UUID, String> previous = Map.of(accepted, "ACCEPTED", preparing, "PREPARING", ready, "READY_FOR_PICKUP");

        for (UUID orderId : List.of(accepted, preparing, ready)) {
            UUID adminId = UUID.randomUUID();
            JsonNode before = body(mvc.perform(get("/api/admin/orders/{id}", orderId).with(as(adminId, "SUPPORT")))
                    .andExpect(status().isOk()));
            assertThat(before.get("adminCancellable").asBoolean()).isTrue();
            assertThat(before.get("cancellable").asBoolean()).isFalse();

            JsonNode view = body(adminCancel(adminId, "OPS_ADMIN", orderId, "Store called: kitchen fire")
                    .andExpect(status().isOk()));

            assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(view.get("adminCancellable").asBoolean()).isFalse();
            assertThat(view.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");
            assertThat(view.get("cancellation").get("reason").asString()).isEqualTo("ADMIN_CANCELLED");
            assertThat(view.get("cancellation").get("by").asString()).isEqualTo("ADMIN");
            assertThat(view.get("timeline").get(view.get("timeline").size() - 2).get("status").asString())
                    .isEqualTo(previous.get(orderId));
            JsonNode cancelled = orderEvents(orderId, "OrderCancelled").getFirst();
            assertThat(cancelled.get("previousStatus").asString()).isEqualTo(previous.get(orderId));
            assertThat(cancelled.get("reason").asString()).isEqualTo("ADMIN_CANCELLED");
            assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("ADMIN");
            assertThat(cancelled.get("actorUserId").asString()).isEqualTo(adminId.toString());
            assertThat(cancelled.get("note").asString()).isEqualTo("Store called: kitchen fire");

            // idempotent, and later store events change nothing
            adminCancel(UUID.randomUUID(), "SUPER_ADMIN", orderId, "Again").andExpect(status().isOk());
            onStoreEvent(storeReady(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), Instant.now()));
            assertThat(orderEventTypes(orderId).stream().filter("OrderCancelled"::equals).count()).isEqualTo(1);
            assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void adminCancellableIsFalseForTerminalOrders() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID rejected = newGroceryOrder(customerId);
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, Map.of(UUID.randomUUID(), new int[]{2, 0})));
        UUID storeRejected = confirmedOrder(customerId);
        onStoreEvent(storeRejected(RESTAURANT, newEventId(), storeRejected, storeIdOf(storeRejected), "STORE_CLOSED",
                null, false, UUID.randomUUID()));

        for (UUID orderId : List.of(rejected, storeRejected)) {
            mvc.perform(get("/api/admin/orders/{id}", orderId).with(as(UUID.randomUUID(), "SUPPORT")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.adminCancellable").value(false))
                    .andExpect(jsonPath("$.cancellable").value(false));
        }
        // the store-cancelled order: an admin cancel answers with it unchanged (idempotent), the customer's too
        adminCancel(UUID.randomUUID(), "OPS_ADMIN", storeRejected, "Duplicate")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation.reason").value("STORE_REJECTED"))
                .andExpect(jsonPath("$.cancellation.by").value("STORE"))
                .andExpect(jsonPath("$.cancellation.note").value("STORE_CLOSED"));
        cancel(customerId, storeRejected, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation.reason").value("STORE_REJECTED"));
        assertThat(orderEventTypes(storeRejected).stream().filter("OrderCancelled"::equals).count()).isEqualTo(1);
    }

    /**
     * A customer cancel and a PaymentCaptured for the same order at the same moment: both lock the order row, so one
     * waits for the other. Whichever goes first, the result is the same — CANCELLED, the payment to be refunded, and
     * exactly one OrderCancelled.
     */
    @Test
    void aCancelRacingACaptureEndsCancelledAndRefunded() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID customerId = UUID.randomUUID();
            UUID orderId = newRestaurantOrder(customerId);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cancel(customerId, orderId, "{}").andReturn().getResponse().getStatus();
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    paymentListener.on(paymentCaptured(newEventId(), orderId, false));
                    return null;
                }));
                start.countDown();
            }
            assertThat(futures.getFirst().get(30, TimeUnit.SECONDS)).isEqualTo(200);
            futures.get(1).get(30, TimeUnit.SECONDS);

            JsonNode order = order(customerId, orderId);
            assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(order.get("payment").get("status").asString()).isEqualTo("REFUND_PENDING");
            assertThat(orderEventTypes(orderId).stream().filter("OrderCancelled"::equals).count()).isEqualTo(1);
        }
    }
}
