package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §3: returns and partial refunds — who may ask and when, the quantities, the refund math (prorated coupon
 * discount, the delivery fee only on a full return), the store's and the admins' decisions, the escalation of
 * undecided returns, REFUNDED on payment-service's RefundCompleted, and the events and timeline entries.
 *
 * <p>Green Basket (grocery): 2 × Basmati Rice at 50.00 (line 100.00) + 1 × Toor Dal 120.00 = 220.00 + fee 25.00.
 * Spice Route (restaurant): 1 × Chicken Biryani 249.00 + 2 × Raita 40.00 (line 80.00) = 329.00 + fee 30.00.
 */
class ReturnsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> RETURN_VIEW_FIELDS = List.of("id", "returnNumber", "orderId", "orderNumber",
            "orderType", "storeId", "storeName", "status", "reason", "note", "items", "refundAmount", "restock",
            "decideBy", "requestedAt", "decidedAt", "decidedByRole", "comment", "refundedAt");

    // ---------------------------------------------------------------- requesting

    @Test
    void theCustomerAsksToReturnPartOfADeliveredOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        JsonNode before = order(customerId, orderId);
        assertThat(before.get("returnable").asBoolean()).isTrue();
        assertThat(before.get("returns")).isEmpty();
        UUID rice = itemIds(customerId, orderId).getFirst();

        JsonNode view = body(requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", "  Torn bag  ")
                .andExpect(status().isCreated()));

        assertThat(fields(view)).containsExactlyElementsOf(RETURN_VIEW_FIELDS);
        assertThat(view.get("returnNumber").asString()).matches("RT-[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(view.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(view.get("orderNumber").asString()).isEqualTo(before.get("orderNumber").asString());
        assertThat(view.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(view.get("storeId").asString()).isEqualTo(storeIdOf(orderId).toString());
        assertThat(view.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(view.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(view.get("reason").asString()).isEqualTo("DAMAGED");
        assertThat(view.get("note").asString()).isEqualTo("Torn bag");
        JsonNode line = view.get("items").get(0);
        assertThat(fields(line)).containsExactly("itemId", "name", "quantity", "refundAmount");
        assertThat(line.get("itemId").asString()).isEqualTo(rice.toString());
        assertThat(line.get("name").asString()).isEqualTo("Basmati Rice");
        assertThat(line.get("quantity").asInt()).isEqualTo(1);
        assertThat(line.get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");   // 100.00 × 1/2
        assertThat(view.get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");  // no fee: not everything
        assertThat(view.get("restock").isNull()).isTrue();
        assertThat(view.get("decidedAt").isNull()).isTrue();
        assertThat(view.get("decidedByRole").isNull()).isTrue();
        assertThat(view.get("comment").isNull()).isTrue();
        assertThat(view.get("refundedAt").isNull()).isTrue();
        Instant requestedAt = Instant.parse(view.get("requestedAt").asString());
        assertThat(Instant.parse(view.get("decideBy").asString())).isEqualTo(requestedAt.plus(Duration.ofHours(24)));

        // the order shows it, and no second request is possible while it is open
        JsonNode after = order(customerId, orderId);
        assertThat(after.get("returnable").asBoolean()).isFalse();
        JsonNode summary = after.get("returns").get(0);
        assertThat(fields(summary)).containsExactly("id", "returnNumber", "status", "reason", "refundAmount",
                "requestedAt", "items");
        assertThat(fields(summary.get("items").get(0))).containsExactly("itemId", "quantity");
        assertThat(summary.get("items").get(0).get("itemId").asString()).isEqualTo(rice.toString());
        assertThat(summary.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(summary.get("id").asString()).isEqualTo(view.get("id").asString());
        assertThat(summary.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(summary.get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(after.get("status").asString()).isEqualTo("DELIVERED");                  // the order is unchanged

        // the customer's lists
        JsonNode ofOrder = body(mvc.perform(get("/api/orders/{id}/returns", orderId).with(customer(customerId)))
                .andExpect(status().isOk()));
        assertThat(ofOrder).hasSize(1);
        assertThat(ofOrder.get(0)).isEqualTo(view);
        JsonNode mine = body(mvc.perform(get("/api/returns").with(customer(customerId))).andExpect(status().isOk()));
        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.get("content").get(0).get("id").asString()).isEqualTo(view.get("id").asString());
        mvc.perform(get("/api/orders/{id}/returns", orderId).with(customer(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));

        // ReturnRequested, keyed by the order, and on the order's event timeline
        JsonNode event = orderEvents(orderId, "ReturnRequested").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("returnId", "returnNumber", "orderId", "orderNumber",
                "orderType", "customerId", "storeId", "items", "refundAmount", "reason", "note", "decideBy",
                "requestedAt");
        assertThat(event.get("returnId").asString()).isEqualTo(view.get("id").asString());
        assertThat(event.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(event.get("items").get(0).get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(outboxRow(orderId, "ReturnRequested")).containsEntry("aggregate_type", "Order")
                .containsEntry("aggregate_id", orderId.toString());
        JsonNode timeline = body(mvc.perform(get("/api/admin/orders/{id}/events", orderId)
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        JsonNode last = timeline.get(timeline.size() - 1);
        assertThat(last.get("eventType").asString()).isEqualTo("ReturnRequested");
        assertThat(last.get("direction").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void onlyTheCustomerOfADeliveredOrderMayAskWithinTheWindow() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID confirmed = confirmedGroceryOrder(customerId);
        UUID rice = itemIds(customerId, confirmed).getFirst();
        requestReturn(customerId, confirmed, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.currentStatus").value("CONFIRMED"));
        assertThat(order(customerId, confirmed).get("returnable").asBoolean()).isFalse();

        UUID delivered = deliver(confirmed);
        requestReturn(UUID.randomUUID(), delivered, Map.of(rice, 1), "DAMAGED", null)    // someone else's order
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));

        // 48 hours after delivery: closed
        Instant deliveredAt = Instant.now().minus(Duration.ofHours(48)).minusSeconds(60);
        movedBack(delivered, "delivered_at", null, deliveredAt);
        requestReturn(customerId, delivered, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.returnableUntil").exists());
        assertThat(order(customerId, delivered).get("returnable").asBoolean()).isFalse();

        // just inside the window: open
        movedBack(delivered, "delivered_at", null, Instant.now().minus(Duration.ofHours(47)));
        assertThat(order(customerId, delivered).get("returnable").asBoolean()).isTrue();
        requestReturn(customerId, delivered, Map.of(rice, 1), "DAMAGED", null).andExpect(status().isCreated());

        // one open request per order
        requestReturn(customerId, delivered, Map.of(rice, 1), "QUALITY", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.openReturnNumber").exists());

        // restaurant orders use the same flow
        UUID restaurantCustomer = UUID.randomUUID();
        UUID restaurant = deliveredOrder(restaurantCustomer);
        requestReturn(restaurantCustomer, restaurant, Map.of(itemIds(restaurantCustomer, restaurant).get(1), 1),
                "MISSING", "One raita was missing").andExpect(status().isCreated());

        // roles
        requestReturnAs(as(UUID.randomUUID(), "VENDOR"), delivered).andExpect(status().isForbidden());
    }

    @Test
    void theQuantitiesAreCheckedAgainstWhatIsLeft() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        List<UUID> items = itemIds(customerId, orderId);
        UUID rice = items.get(0);
        UUID dal = items.get(1);

        JsonNode tooMany = body(requestReturn(customerId, orderId, Map.of(rice, 3), "DAMAGED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_QUANTITY_EXCEEDED")));
        assertThat(tooMany.get("items")).hasSize(1);
        assertThat(tooMany.get("items").get(0).get("itemId").asString()).isEqualTo(rice.toString());
        assertThat(tooMany.get("items").get(0).get("requested").asInt()).isEqualTo(3);
        assertThat(tooMany.get("items").get(0).get("returnable").asInt()).isEqualTo(2);
        JsonNode notOnTheOrder = body(requestReturn(customerId, orderId, Map.of(UUID.randomUUID(), 1), "OTHER", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_QUANTITY_EXCEEDED")));
        assertThat(notOnTheOrder.get("items").get(0).get("returnable").asInt()).isZero();

        // the same item twice is added up: 1 + 2 > 2
        requestReturnBody(customerId, orderId, List.of(Map.of("itemId", rice.toString(), "quantity", 1),
                Map.of("itemId", rice.toString(), "quantity", 2)), "DAMAGED")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_QUANTITY_EXCEEDED"));

        // validation
        requestReturnBody(customerId, orderId, List.of(), "DAMAGED")
                .andExpect(status().isBadRequest());
        requestReturnBody(customerId, orderId, List.of(Map.of("itemId", rice.toString(), "quantity", 0)), "DAMAGED")
                .andExpect(status().isBadRequest());
        requestReturnBody(customerId, orderId, List.of(Map.of("itemId", rice.toString(), "quantity", 1)), null)
                .andExpect(status().isBadRequest());
        requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", "x".repeat(501))
                .andExpect(status().isBadRequest());

        // a rejected request frees its quantities; an approved one keeps them
        UUID vendorUser = UUID.randomUUID();
        UUID vendorId = storeIdOf(orderId);
        JsonNode first = body(requestReturn(customerId, orderId, Map.of(rice, 2), "DAMAGED", null)
                .andExpect(status().isCreated()));
        reject(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", first, "Not damaged")
                .andExpect(status().isOk());
        assertThat(order(customerId, orderId).get("returnable").asBoolean()).isTrue();
        JsonNode second = body(requestReturn(customerId, orderId, Map.of(rice, 2), "DAMAGED", null)
                .andExpect(status().isCreated()));
        approve(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", second, false)
                .andExpect(status().isOk());
        requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.items[0].returnable").value(0));
        requestReturn(customerId, orderId, Map.of(dal, 1), "QUALITY", null).andExpect(status().isCreated());
        assertThat(order(customerId, orderId).get("returns")).hasSize(3);
    }

    // ---------------------------------------------------------------- the refund math

    @Test
    void aPercentCouponIsProratedAndTheLastReturnAddsThePaidDeliveryFee() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliver(confirmedGroceryOrderWithCoupon(customerId, "22.00", false));   // 10 %: total 223.00
        List<UUID> items = itemIds(customerId, orderId);
        UUID rice = items.get(0);
        UUID dal = items.get(1);
        UUID vendorId = storeIdOf(orderId);

        // 1 of 2 rice: 100.00 × 1/2 × (1 − 22/220) = 45.00
        JsonNode partial = body(requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isCreated()));
        assertThat(partial.get("refundAmount").decimalValue()).isEqualByComparingTo("45.00");
        approve(storeUser(UUID.randomUUID(), "VENDOR", vendorId), "/api/store/returns", partial, true)
                .andExpect(status().isOk());

        // everything that is left: rice 45.00 + dal 120.00 × 0.9 = 108.00 + the delivery fee 25.00
        Map<UUID, Integer> rest = new LinkedHashMap<>();
        rest.put(rice, 1);
        rest.put(dal, 1);
        JsonNode remainder = body(requestReturn(customerId, orderId, rest, "QUALITY", null)
                .andExpect(status().isCreated()));
        assertThat(remainder.get("items").get(0).get("refundAmount").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(remainder.get("items").get(1).get("refundAmount").decimalValue()).isEqualByComparingTo("108.00");
        assertThat(remainder.get("refundAmount").decimalValue()).isEqualByComparingTo("178.00");
        // together exactly what was paid
        assertThat(partial.get("refundAmount").decimalValue().add(remainder.get("refundAmount").decimalValue()))
                .isEqualByComparingTo("223.00");
    }

    @Test
    void aFullReturnAtOnceRefundsTheFeeAndAFlatCouponIsRoundedHalfUp() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliver(confirmedGroceryOrderWithCoupon(customerId, "10.00", false));   // total 235.00
        List<UUID> items = itemIds(customerId, orderId);
        Map<UUID, Integer> everything = new LinkedHashMap<>();
        everything.put(items.get(0), 2);
        everything.put(items.get(1), 1);

        JsonNode view = body(requestReturn(customerId, orderId, everything, "WRONG_ITEM", null)
                .andExpect(status().isCreated()));

        // rice 100.00 × 210/220 = 95.4545… → 95.45; dal 120.00 × 210/220 = 114.5454… → 114.55; + fee 25.00
        assertThat(view.get("items").get(0).get("refundAmount").decimalValue()).isEqualByComparingTo("95.45");
        assertThat(view.get("items").get(1).get("refundAmount").decimalValue()).isEqualByComparingTo("114.55");
        assertThat(view.get("refundAmount").decimalValue()).isEqualByComparingTo("235.00");

        // a single rice of another order with the same coupon: 100.00 × 1/2 × 210/220 = 47.7272… → 47.73
        UUID other = UUID.randomUUID();
        UUID otherOrder = deliver(confirmedGroceryOrderWithCoupon(other, "10.00", false));
        JsonNode one = body(requestReturn(other, otherOrder, Map.of(itemIds(other, otherOrder).getFirst(), 1),
                "DAMAGED", null).andExpect(status().isCreated()));
        assertThat(one.get("refundAmount").decimalValue()).isEqualByComparingTo("47.73");
    }

    @Test
    void aFreeDeliveryCouponMeansNoFeeIsRefunded() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredRestaurantOrderWithCoupon(customerId, "0.00", true);           // total 329.00
        assertThat(order(customerId, orderId).get("total").decimalValue()).isEqualByComparingTo("329.00");
        List<UUID> items = itemIds(customerId, orderId);

        JsonNode raita = body(requestReturn(customerId, orderId, Map.of(items.get(1), 1), "QUALITY", null)
                .andExpect(status().isCreated()));
        assertThat(raita.get("refundAmount").decimalValue()).isEqualByComparingTo("40.00");    // 80.00 × 1/2
        reject(storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", storeIdOf(orderId)), "/api/store/returns", raita,
                "Raita was fine").andExpect(status().isOk());

        Map<UUID, Integer> everything = new LinkedHashMap<>();
        everything.put(items.get(0), 1);
        everything.put(items.get(1), 2);
        JsonNode all = body(requestReturn(customerId, orderId, everything, "QUALITY", null)
                .andExpect(status().isCreated()));
        // 249.00 + 80.00 + paid fee (30.00 − 30.00) = 329.00
        assertThat(all.get("refundAmount").decimalValue()).isEqualByComparingTo("329.00");

        // without a coupon the whole order comes back with its fee: 249.00 + 80.00 + 30.00
        UUID plain = UUID.randomUUID();
        UUID plainOrder = deliveredOrder(plain);
        Map<UUID, Integer> plainAll = new LinkedHashMap<>();
        plainAll.put(itemIds(plain, plainOrder).get(0), 1);
        plainAll.put(itemIds(plain, plainOrder).get(1), 2);
        JsonNode full = body(requestReturn(plain, plainOrder, plainAll, "OTHER", null)
                .andExpect(status().isCreated()));
        assertThat(full.get("refundAmount").decimalValue()).isEqualByComparingTo(RESTAURANT_TOTAL);
    }

    // ---------------------------------------------------------------- the store decides

    @Test
    void theStoreApprovesOrRejectsItsOwnRequestedReturns() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        UUID vendorUser = UUID.randomUUID();
        UUID rice = itemIds(customerId, orderId).getFirst();
        JsonNode requested = body(requestReturn(customerId, orderId, Map.of(rice, 2), "DAMAGED", null)
                .andExpect(status().isCreated()));
        String returnId = requested.get("id").asString();

        // the store's list
        JsonNode list = body(mvc.perform(get("/api/store/returns").param("status", "REQUESTED")
                .with(storeUser(vendorUser, "VENDOR", vendorId))).andExpect(status().isOk()));
        assertThat(list.get("content").valueStream().map(r -> r.get("id").asString())).containsExactly(returnId);
        JsonNode otherStore = body(mvc.perform(get("/api/store/returns")
                .with(storeUser(UUID.randomUUID(), "VENDOR", UUID.randomUUID()))).andExpect(status().isOk()));
        assertThat(otherStore.get("totalElements").asLong()).isZero();
        mvc.perform(get("/api/store/returns").param("status", "LOST").with(storeUser(vendorUser, "VENDOR", vendorId)))
                .andExpect(status().isBadRequest());

        // other stores, unapproved stores and other roles
        approve(storeUser(UUID.randomUUID(), "VENDOR", UUID.randomUUID()), "/api/store/returns", requested, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_FOUND"));
        approve(storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", vendorId), "/api/store/returns", requested, true)
                .andExpect(status().isNotFound());                                  // same id, not its store type
        approve(as(UUID.randomUUID(), "VENDOR"), "/api/store/returns", requested, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        approve(customer(customerId), "/api/store/returns", requested, true).andExpect(status().isForbidden());
        approve(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns/" + UUID.randomUUID(), null, true)
                .andExpect(status().isNotFound());

        // approved with restock
        JsonNode approved = body(approve(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", requested,
                true).andExpect(status().isOk()));
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("restock").asBoolean()).isTrue();
        assertThat(approved.get("decidedByRole").asString()).isEqualTo("STORE");
        assertThat(approved.get("decidedAt").isNull()).isFalse();
        assertThat(approved.get("refundAmount").decimalValue()).isEqualByComparingTo("100.00");
        approve(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", requested, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("APPROVED"));
        reject(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", requested, "Too late")
                .andExpect(status().isConflict());

        JsonNode event = orderEvents(orderId, "ReturnApproved").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("returnId", "returnNumber", "orderId", "orderNumber",
                "orderType", "customerId", "storeId", "items", "refundAmount", "restock", "decidedBy",
                "decidedByRole", "decidedAt");
        assertThat(event.get("restock").asBoolean()).isTrue();
        assertThat(event.get("decidedBy").asString()).isEqualTo(vendorUser.toString());
        assertThat(event.get("decidedByRole").asString()).isEqualTo("STORE");
        assertThat(event.get("refundAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(event.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(event.get("storeId").asString()).isEqualTo(vendorId.toString());

        // a rejection needs a comment, which the customer sees
        UUID dal = itemIds(customerId, orderId).get(1);
        JsonNode second = body(requestReturn(customerId, orderId, Map.of(dal, 1), "QUALITY", null)
                .andExpect(status().isCreated()));
        reject(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", second, " ")
                .andExpect(status().isBadRequest());
        reject(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", second, "x".repeat(301))
                .andExpect(status().isBadRequest());
        JsonNode rejected = body(reject(storeUser(vendorUser, "VENDOR", vendorId), "/api/store/returns", second,
                "  The dal was sealed  ").andExpect(status().isOk()));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("comment").asString()).isEqualTo("The dal was sealed");
        assertThat(rejected.get("restock").isNull()).isTrue();
        JsonNode rejection = orderEvents(orderId, "ReturnRejected").getFirst();
        assertThat(fields(rejection)).containsExactlyInAnyOrder("returnId", "returnNumber", "orderId", "orderNumber",
                "orderType", "customerId", "storeId", "comment", "decidedBy", "decidedByRole", "decidedAt");
        assertThat(rejection.get("comment").asString()).isEqualTo("The dal was sealed");
        assertThat(order(customerId, orderId).get("returns").valueStream().map(r -> r.get("status").asString()))
                .containsExactly("APPROVED", "REJECTED");
    }

    @Test
    void aRestaurantNeverRestocks() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredOrder(customerId);
        JsonNode requested = body(requestReturn(customerId, orderId, Map.of(itemIds(customerId, orderId).getFirst(), 1),
                "QUALITY", "Cold").andExpect(status().isCreated()));

        JsonNode approved = body(approve(storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", storeIdOf(orderId)),
                "/api/store/returns", requested, true).andExpect(status().isOk()));

        assertThat(approved.get("restock").asBoolean()).isFalse();
        assertThat(orderEvents(orderId, "ReturnApproved").getFirst().get("restock").asBoolean()).isFalse();
        assertThat(approved.get("orderType").asString()).isEqualTo("RESTAURANT");
    }

    // ---------------------------------------------------------------- admins, escalation

    @Test
    void anUndecidedReturnEscalatesToTheAdmins() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        List<UUID> items = itemIds(customerId, orderId);
        JsonNode overdue = body(requestReturn(customerId, orderId, Map.of(items.get(0), 1), "DAMAGED", null)
                .andExpect(status().isCreated()));
        String returnId = overdue.get("id").asString();

        escalation.escalateDue();                                                   // not due yet
        assertThat(returnStatus(returnId)).isEqualTo("REQUESTED");

        jdbc().sql("UPDATE order_return SET decide_by = ? WHERE id = UUID_TO_BIN(?)")
                .param(Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(returnId).update();
        assertThat(escalation.escalateDue()).isPositive();
        assertThat(returnStatus(returnId)).isEqualTo("ESCALATED");
        escalation.escalateDue();                                                   // once only
        assertThat(orderEvents(orderId, "ReturnEscalated")).hasSize(1);
        JsonNode event = orderEvents(orderId, "ReturnEscalated").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("returnId", "returnNumber", "orderId", "orderNumber",
                "orderType", "customerId", "storeId", "refundAmount", "requestedAt", "escalatedAt");
        assertThat(event.get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");

        // the store is too late; the admins see and decide it
        approve(storeUser(UUID.randomUUID(), "VENDOR", vendorId), "/api/store/returns", overdue, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("ESCALATED"));
        JsonNode queue = body(mvc.perform(get("/api/admin/returns").param("storeId", vendorId.toString())
                .with(as(UUID.randomUUID(), "FINANCE_ADMIN"))).andExpect(status().isOk()));
        assertThat(queue.get("content").valueStream().map(r -> r.get("id").asString())).containsExactly(returnId);
        approve(as(UUID.randomUUID(), "FINANCE_ADMIN"), "/api/admin/returns", overdue, true)
                .andExpect(status().isForbidden());
        UUID admin = UUID.randomUUID();
        JsonNode approved = body(approve(as(admin, "OPS_ADMIN"), "/api/admin/returns", overdue, true)
                .andExpect(status().isOk()));
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedByRole").asString()).isEqualTo("ADMIN");
        assertThat(approved.get("restock").asBoolean()).isTrue();
        assertThat(orderEvents(orderId, "ReturnApproved").getFirst().get("decidedBy").asString())
                .isEqualTo(admin.toString());

        // the default queue is REQUESTED + ESCALATED only; other statuses on request
        JsonNode open = body(mvc.perform(get("/api/admin/returns").param("storeId", vendorId.toString())
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(open.get("totalElements").asLong()).isZero();
        JsonNode done = body(mvc.perform(get("/api/admin/returns").param("storeId", vendorId.toString())
                .param("status", "APPROVED").with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(done.get("content").get(0).get("id").asString()).isEqualTo(returnId);

        // admins may also decide a REQUESTED one before the store does
        JsonNode requested = body(requestReturn(customerId, orderId, Map.of(items.get(1), 1), "QUALITY", null)
                .andExpect(status().isCreated()));
        JsonNode rejected = body(reject(as(UUID.randomUUID(), "SUPPORT"), "/api/admin/returns", requested,
                "Duplicate of a support ticket").andExpect(status().isOk()));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("decidedByRole").asString()).isEqualTo("ADMIN");
        reject(as(UUID.randomUUID(), "SUPPORT"), "/api/admin/returns", requested, "Again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
        mvc.perform(get("/api/admin/returns").with(customer(customerId))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- refunded

    @Test
    void refundCompletedWithTheReturnIdMarksItRefunded() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredGroceryOrder(customerId);
        UUID rice = itemIds(customerId, orderId).getFirst();
        JsonNode requested = body(requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isCreated()));
        UUID returnId = UUID.fromString(requested.get("id").asString());

        // not approved yet: the order's refund projection moves on, the return does not
        paymentListener.on(returnRefundCompleted(newEventId(), orderId, returnId, "1.00", "1.00", false));
        assertThat(returnStatus(returnId.toString())).isEqualTo("REQUESTED");

        approve(storeUser(UUID.randomUUID(), "VENDOR", storeIdOf(orderId)), "/api/store/returns", requested, false)
                .andExpect(status().isOk());
        String eventId = newEventId();
        paymentListener.on(returnRefundCompleted(eventId, orderId, returnId, "50.00", "51.00", false));

        JsonNode view = body(mvc.perform(get("/api/orders/{id}/returns", orderId).with(customer(customerId)))
                .andExpect(status().isOk())).get(0);
        assertThat(view.get("status").asString()).isEqualTo("REFUNDED");
        assertThat(view.get("refundedAt").isNull()).isFalse();
        JsonNode order = order(customerId, orderId);
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(order.get("payment").get("refundedAmount").decimalValue()).isEqualByComparingTo("51.00");
        assertThat(order.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(order.get("returnable").asBoolean()).isTrue();                    // more is left, nothing open

        // the timeline names the return; a redelivery changes nothing
        paymentListener.on(returnRefundCompleted(eventId, orderId, returnId, "50.00", "51.00", false));
        JsonNode timeline = body(mvc.perform(get("/api/admin/orders/{id}/events", orderId)
                .with(as(UUID.randomUUID(), "OPS_ADMIN"))).andExpect(status().isOk()));
        List<JsonNode> refunds = timeline.valueStream()
                .filter(e -> eventId.equals(e.get("eventId").asString())).toList();
        assertThat(refunds).extracting(e -> e.get("outcome").asString()).containsExactly("APPLIED", "DUPLICATE");
        assertThat(refunds.getFirst().get("detail").asString())
                .isEqualTo("return " + requested.get("returnNumber").asString() + " APPROVED → REFUNDED");
        assertThat(timeline.valueStream().map(e -> e.get("eventType").asString()))
                .contains("ReturnRequested", "ReturnApproved", "RefundCompleted");

        // a refund in full closes the returns
        paymentListener.on(refundCompleted(newEventId(), orderId, "194.00", "245.00", true));
        assertThat(order(customerId, orderId).get("returnable").asBoolean()).isFalse();
        requestReturn(customerId, orderId, Map.of(rice, 1), "DAMAGED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_ALLOWED"));
    }

    // ---------------------------------------------------------------- helpers

    private String returnStatus(String returnId) {
        return jdbc().sql("SELECT status FROM order_return WHERE id = UUID_TO_BIN(?)")
                .param(returnId).query(String.class).single();
    }

    /** POST {@code base}/{id}/approve with {@code {restock}}; {@code request} null = {@code base} is the full path. */
    private ResultActions approve(org.springframework.test.web.servlet.request.RequestPostProcessor caller,
                                  String base, JsonNode request, boolean restock) throws Exception {
        String path = request == null ? base + "/approve" : base + "/" + request.get("id").asString() + "/approve";
        MockHttpServletRequestBuilder builder = post(path).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"restock\":" + restock + "}");
        return mvc.perform(builder);
    }

    private ResultActions reject(org.springframework.test.web.servlet.request.RequestPostProcessor caller,
                                 String base, JsonNode request, String comment) throws Exception {
        return mvc.perform(post(base + "/" + request.get("id").asString() + "/reject").with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("comment", comment))));
    }

    private ResultActions requestReturnBody(UUID customerId, UUID orderId, List<Map<String, Object>> items,
                                            String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mvc.perform(post("/api/orders/{id}/returns", orderId).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions requestReturnAs(org.springframework.test.web.servlet.request.RequestPostProcessor caller,
                                          UUID orderId) throws Exception {
        return mvc.perform(post("/api/orders/{id}/returns", orderId).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}],\"reason\":\"OTHER\"}"));
    }

    /** A restaurant order placed with a coupon, paid, prepared, picked up and delivered: DELIVERED. */
    private UUID deliveredRestaurantOrderWithCoupon(UUID customerId, String discount, boolean freeDelivery)
            throws Exception {
        defaultAddress(customerId);
        restaurantCart(customerId);
        coupon(customerId, newCouponCode(), discount, freeDelivery);
        BigDecimal total = RESTAURANT_TOTAL.subtract(new BigDecimal(discount))
                .subtract(freeDelivery ? StubCart.RESTAURANT_FEE : BigDecimal.ZERO);
        UUID orderId = id(body(place(customerId, newKey(), total).andExpect(status().isCreated())));
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        UUID restaurantId = storeIdOf(orderId);
        restaurantListener.on(storeAccepted(RESTAURANT, newEventId(), orderId, restaurantId, 20, Instant.now()));
        restaurantListener.on(storeReady(RESTAURANT, newEventId(), orderId, restaurantId, Instant.now()));
        UUID partnerId = UUID.randomUUID();
        deliveryListener.on(deliveryPickedUp(newEventId(), orderId, partnerId, Instant.now()));
        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partnerId, Instant.now()));
        return orderId;
    }
}
