package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/orders} happy paths (contract §2): the snapshot, the first statuses and the outbox events. */
class PlaceOrderIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aGroceryOrderIsAPriceSnapshotThatWaitsForStock() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        StubCart.Cart cart = groceryCart(customerId);
        String key = newKey();

        String raw = placeRequest(customerId, key, placeBody(null, new BigDecimal("245"), "Ring the bell twice"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        JsonNode order = json.readTree(raw);
        UUID orderId = id(order);

        assertThat(order.get("orderNumber").asString()).matches("GA-[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(order.get("type").asString()).isEqualTo("GROCERY");
        assertThat(order.get("status").asString()).isEqualTo("CREATED");
        assertThat(order.get("store").get("id").asString()).isEqualTo(cart.storeId().toString());
        assertThat(order.get("store").get("type").asString()).isEqualTo("GROCERY");
        assertThat(order.get("store").get("name").asString()).isEqualTo("Green Basket");
        JsonNode rice = order.get("items").get(0);
        assertThat(fields(rice)).containsExactly("itemId", "type", "name", "unitLabel", "imageUrl", "foodType",
                "quantity", "unitPrice", "mrp", "lineTotal");
        assertThat(rice.get("itemId").asString()).isEqualTo(cart.lines().getFirst().itemId().toString());
        assertThat(rice.get("type").asString()).isEqualTo("PRODUCT");
        assertThat(rice.get("name").asString()).isEqualTo("Basmati Rice");
        assertThat(rice.get("unitLabel").asString()).isEqualTo("1 kg");
        assertThat(rice.get("foodType").asString()).isEqualTo("VEG");
        assertThat(rice.get("quantity").asInt()).isEqualTo(2);
        assertThat(rice.get("unitPrice").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(rice.get("mrp").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(rice.get("lineTotal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("items").get(1).get("name").asString()).isEqualTo("Toor Dal");
        assertThat(order.get("itemCount").asInt()).isEqualTo(3);
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(order.get("mrpTotal").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(order.get("savings").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(raw).contains("\"total\":245.00", "\"refundedAmount\":0.00");   // 2 decimals on the wire
        assertThat(order.get("currency").asString()).isEqualTo("INR");
        JsonNode address = order.get("deliveryAddress");
        assertThat(fields(address)).containsExactly("label", "recipientName", "phone", "line1", "line2", "landmark",
                "city", "state", "postalCode", "latitude", "longitude");
        assertThat(address.get("label").asString()).isEqualTo("Home");
        assertThat(address.get("recipientName").asString()).isEqualTo("Asha Rao");
        assertThat(address.get("phone").asString()).isEqualTo("+919876543210");
        assertThat(address.get("line2").isNull()).isTrue();
        assertThat(address.get("postalCode").asString()).isEqualTo("560001");
        assertThat(address.get("latitude").asDouble()).isEqualTo(12.9716);
        assertThat(order.get("deliveryDistanceKm").decimalValue()).isEqualByComparingTo("4.6");
        assertThat(order.get("note").asString()).isEqualTo("Ring the bell twice");
        JsonNode payment = order.get("payment");
        assertThat(payment.get("status").asString()).isEqualTo("NOT_STARTED");
        Instant createdAt = Instant.parse(order.get("createdAt").asString());
        assertThat(Instant.parse(payment.get("dueAt").asString())).isEqualTo(createdAt.plus(Duration.ofMinutes(15)));
        assertThat(payment.get("paidAt").isNull()).isTrue();
        assertThat(payment.get("refundedAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(payment.get("lastFailure").isNull()).isTrue();
        assertThat(order.get("cancellable").asBoolean()).isTrue();
        assertThat(order.get("cancellation").isNull()).isTrue();
        assertThat(order.get("rejection").isNull()).isTrue();
        assertThat(order.get("timeline")).hasSize(1);
        assertThat(order.get("timeline").get(0).get("status").asString()).isEqualTo("CREATED");
        assertThat(order.get("timeline").get(0).get("at").asString()).isEqualTo(order.get("createdAt").asString());
        assertThat(order.get("timeline").get(0).get("reason").isNull()).isTrue();
        assertThat(order.get("updatedAt").asString()).isEqualTo(order.get("createdAt").asString());

        // the stored order reads back the same
        assertThat(order(customerId, orderId)).isEqualTo(order);

        // the live preview was read with the customer's own token and the request's correlation id
        List<StubCart.Call> calls = CART.callsOf(customerId);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().path()).isEqualTo("/api/cart/preview");
        assertThat(calls.getFirst().query()).isNull();                            // default address
        assertThat(calls.getFirst().correlationId()).isNotBlank();

        // OrderCreated, in the same transaction; nothing else yet (inventory reserves the stock first)
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated");
        assertThat(outboxRow(orderId, "OrderCreated"))
                .containsEntry("topic", "order.events")
                .containsEntry("aggregate_type", "Order")
                .containsEntry("aggregate_id", orderId.toString())
                .containsEntry("event_version", 1);
        JsonNode created = orderEvents(orderId, "OrderCreated").getFirst();
        assertThat(fields(created)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "storeName", "items", "subtotal", "deliveryFee", "total", "currency", "cartUpdatedAt",
                "paymentDueAt", "createdAt", "couponCode", "discount", "deliveryFeeDiscount",      // + Phase 12
                "couponName", "couponFundedBy");                                                   // + 1.7.0
        assertThat(created.get("couponCode").isNull()).isTrue();                    // no coupon: all five null
        assertThat(created.get("discount").isNull()).isTrue();
        assertThat(created.get("deliveryFeeDiscount").isNull()).isTrue();
        assertThat(created.get("couponName").isNull()).isTrue();
        assertThat(created.get("couponFundedBy").isNull()).isTrue();
        assertThat(order.get("couponCode").isNull()).isTrue();
        assertThat(order.get("discount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("returnable").asBoolean()).isFalse();
        assertThat(order.get("returns")).isEmpty();
        assertThat(PRICING.callsOf(customerId)).isEmpty();                          // nothing to redeem
        assertThat(created.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(created.get("orderNumber").asString()).isEqualTo(order.get("orderNumber").asString());
        assertThat(created.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(created.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(created.get("storeId").asString()).isEqualTo(cart.storeId().toString());
        assertThat(created.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(created.get("items")).hasSize(2);
        assertThat(fields(created.get("items").get(0))).containsExactlyInAnyOrder("itemId", "type", "name",
                "quantity", "unitPrice", "lineTotal");
        assertThat(created.get("items").get(0).get("itemId").asString())
                .isEqualTo(cart.lines().getFirst().itemId().toString());
        assertThat(created.get("items").get(0).get("type").asString()).isEqualTo("PRODUCT");
        assertThat(created.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(created.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(created.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(created.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(created.get("deliveryFee").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(created.get("total").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(created.get("currency").asString()).isEqualTo("INR");
        assertThat(Instant.parse(created.get("cartUpdatedAt").asString())).isEqualTo(cart.updatedAt());
        assertThat(Instant.parse(created.get("paymentDueAt").asString()))
                .isEqualTo(Instant.parse(payment.get("dueAt").asString()));
        assertThat(Instant.parse(created.get("createdAt").asString())).isEqualTo(createdAt);

        // the key is stored for this customer, pointing at the order
        assertThat(jdbc().sql("SELECT result_ref FROM idempotency_key WHERE scope = ? AND idem_key = ?")
                .param("order:" + customerId).param(key).query(String.class).single()).isEqualTo(orderId.toString());
    }

    @Test
    void aRestaurantOrderGoesStraightToAwaitingPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        StubCart.Cart cart = restaurantCart(customerId);

        JsonNode order = body(place(customerId, newKey(), RESTAURANT_TOTAL).andExpect(status().isCreated()));
        UUID orderId = id(order);

        assertThat(order.get("type").asString()).isEqualTo("RESTAURANT");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("store").get("type").asString()).isEqualTo("RESTAURANT");
        assertThat(order.get("items").get(0).get("type").asString()).isEqualTo("MENU_ITEM");
        assertThat(order.get("items").get(0).get("mrp").isNull()).isTrue();
        assertThat(order.get("items").get(0).get("unitLabel").isNull()).isTrue();
        assertThat(order.get("items").get(0).get("foodType").asString()).isEqualTo("NON_VEG");
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("329.00");
        assertThat(order.get("mrpTotal").decimalValue()).isEqualByComparingTo("329.00");
        assertThat(order.get("savings").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("359.00");
        assertThat(order.get("note").isNull()).isTrue();
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PENDING");
        assertThat(order.get("timeline").valueStream().map(e -> e.get("status").asString()))
                .containsExactly("CREATED", "AWAITING_PAYMENT");

        // both events in the creating transaction, in this order
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment");
        assertThat(orderEvents(orderId, "OrderCreated").getFirst().get("orderType").asString())
                .isEqualTo("RESTAURANT");
        assertThat(orderEvents(orderId, "OrderCreated").getFirst().get("storeId").asString())
                .isEqualTo(cart.storeId().toString());
        JsonNode awaiting = orderEvents(orderId, "OrderAwaitingPayment").getFirst();
        assertThat(fields(awaiting)).containsExactlyInAnyOrder("orderId", "orderNumber", "customerId", "amount",
                "currency", "expiresAt", "contactPhone");
        assertThat(awaiting.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(awaiting.get("orderNumber").asString()).isEqualTo(order.get("orderNumber").asString());
        assertThat(awaiting.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(awaiting.get("amount").decimalValue()).isEqualByComparingTo("359.00");
        assertThat(awaiting.get("currency").asString()).isEqualTo("INR");
        assertThat(Instant.parse(awaiting.get("expiresAt").asString()))
                .isEqualTo(Instant.parse(order.get("payment").get("dueAt").asString()));
        assertThat(awaiting.get("contactPhone").asString()).isEqualTo("+919876543210");
    }

    @Test
    void theSnapshotNeverChangesWhenTheCartOrTheAddressChange() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        UUID office = address(customerId, "Office", false);
        groceryCart(customerId);

        JsonNode order = body(placeRequest(customerId, newKey(), placeBody(office, GROCERY_TOTAL, null))
                .andExpect(status().isCreated()));
        assertThat(CART.callsOf(customerId).getFirst().query()).isEqualTo("addressId=" + office);
        assertThat(order.get("deliveryAddress").get("label").asString()).isEqualTo("Office");

        // later: new prices, another cart, the address deleted
        StubCart.Cart changed = new StubCart.Cart(UUID.randomUUID(), "GROCERY", "Other Store", List.of(
                new StubCart.Line(UUID.randomUUID(), "PRODUCT", "Sugar", "1 kg", "VEG", 5, new BigDecimal("99.00"),
                        new BigDecimal("99.00"))), List.of(), Instant.now());
        CART.carts.put(customerId, changed);
        CART.addresses.remove(customerId);

        assertThat(order(customerId, id(order))).isEqualTo(order);
    }

    @Test
    void theBodyIsValidated() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        placeRequest(customerId, newKey(), placeBody(null, GROCERY_TOTAL, "x".repeat(301)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        placeRequest(customerId, newKey(), Map.of("note", "no total"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("expectedTotal"));
        placeRequest(customerId, newKey(), placeBody(null, new BigDecimal("-1.00"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expectedTotal"));
        mvc.perform(post("/api/orders").with(customer(customerId)).header("Idempotency-Key", newKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/orders").with(customer(customerId)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\": \"lots\"}"))
                .andExpect(status().isBadRequest());

        assertThat(CART.callsOf(customerId)).isEmpty();
        assertThat(orderCount(customerId)).isZero();
    }

    @Test
    void onlyCustomersMayPlaceOrders() throws Exception {
        UUID adminId = UUID.randomUUID();
        defaultAddress(adminId);
        groceryCart(adminId);

        mvc.perform(post("/api/orders").with(as(adminId, "OPS_ADMIN")).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":245.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(post("/api/orders").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":245.00}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        assertThat(orderCount(adminId)).isZero();
        assertThat(CART.callsOf(adminId)).isEmpty();
    }
}
