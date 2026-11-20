package com.grocery.order;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §2 (order-service paragraph): placement reads the coupon from the cart preview and redeems it at
 * pricing-service ({@code POST /internal/coupons/redeem}, stubbed by {@link StubPricing}) with the new order id before
 * saving the order; a refused redemption places no order; {@code expectedTotal} is the discounted total; the order,
 * its summary and OrderCreated / OrderConfirmed carry {@code couponCode}, {@code discount}, {@code deliveryFeeDiscount};
 * the payment amount is the discounted total. Schemas 1.7.0: the events also carry the coupon's {@code couponName} and
 * {@code couponFundedBy} from the redemption (null when pricing-service did not send them).
 */
class CouponPlacementIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aPercentCouponIsRedeemedForTheNewOrderAndSnapshotted() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        StubCart.Cart cart = groceryCart(customerId);
        String code = newCouponCode();
        coupon(customerId, code, "22.00", false);                     // 10 % of 220.00

        JsonNode order = body(place(customerId, newKey(), new BigDecimal("223.00"))   // 245.00 - 22.00
                .andExpect(status().isCreated()));
        UUID orderId = id(order);

        assertThat(order.get("couponCode").asString()).isEqualTo(code);
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(order.get("discount").decimalValue()).isEqualByComparingTo("22.00");
        assertThat(order.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("223.00");
        List<String> viewFields = fields(order);
        assertThat(viewFields.subList(viewFields.indexOf("deliveryFee"), viewFields.indexOf("total") + 1))
                .containsExactly("deliveryFee", "couponCode", "discount", "deliveryFeeDiscount", "total");
        assertThat(order(customerId, orderId)).isEqualTo(order);

        // redeemed once, with the order's id, the customer's own token and the preview's amounts
        List<StubPricing.Call> calls = PRICING.callsOf(customerId);
        assertThat(calls).hasSize(1);
        JsonNode redeem = calls.getFirst().body();
        assertThat(calls.getFirst().path()).isEqualTo("/internal/coupons/redeem");
        assertThat(fields(redeem)).containsExactly("code", "orderId", "customerId", "storeId", "orderType",
                "subtotal", "deliveryFee");
        assertThat(redeem.get("code").asString()).isEqualTo(code);
        assertThat(redeem.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(redeem.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(redeem.get("storeId").asString()).isEqualTo(cart.storeId().toString());
        assertThat(redeem.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(redeem.get("subtotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(redeem.get("deliveryFee").decimalValue()).isEqualByComparingTo("25.00");
        String redemptionId = (String) PRICING.redemptions.get(orderId.toString()).get("redemptionId");
        assertThat(jdbc().sql("SELECT BIN_TO_UUID(coupon_redemption_id) FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single()).isEqualTo(redemptionId);

        // the coupon's name and funding from the redemption are kept with the order (for its events only)
        assertThat(jdbc().sql("SELECT coupon_name, coupon_funded_by FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query().singleRow())
                .containsEntry("coupon_name", "Offer " + code).containsEntry("coupon_funded_by", "PLATFORM");

        JsonNode created = orderEvents(orderId, "OrderCreated").getFirst();
        assertThat(created.get("couponCode").asString()).isEqualTo(code);
        assertThat(created.get("discount").decimalValue()).isEqualByComparingTo("22.00");
        assertThat(created.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(created.get("total").decimalValue()).isEqualByComparingTo("223.00");
        assertThat(created.get("couponName").asString()).isEqualTo("Offer " + code);
        assertThat(created.get("couponFundedBy").asString()).isEqualTo("PLATFORM");

        // the payment is for the discounted total; OrderConfirmed carries the coupon too
        inventoryListener.on(stockReserved(newEventId(), orderId));
        assertThat(orderEvents(orderId, "OrderAwaitingPayment").getFirst().get("amount").decimalValue())
                .isEqualByComparingTo("223.00");
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        JsonNode confirmed = orderEvents(orderId, "OrderConfirmed").getFirst();
        assertThat(confirmed.get("couponCode").asString()).isEqualTo(code);
        assertThat(confirmed.get("discount").decimalValue()).isEqualByComparingTo("22.00");
        assertThat(confirmed.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(confirmed.get("total").decimalValue()).isEqualByComparingTo("223.00");
        assertThat(confirmed.get("couponName").asString()).isEqualTo("Offer " + code);
        assertThat(confirmed.get("couponFundedBy").asString()).isEqualTo("PLATFORM");

        // the lists show it as well
        JsonNode summary = body(mvc.perform(get("/api/orders").with(customer(customerId))).andExpect(status().isOk()))
                .get("content").get(0);
        assertThat(summary.get("couponCode").asString()).isEqualTo(code);
        assertThat(summary.get("discount").decimalValue()).isEqualByComparingTo("22.00");
        assertThat(summary.get("total").decimalValue()).isEqualByComparingTo("223.00");
        JsonNode adminView = body(mvc.perform(get("/api/admin/orders/{id}", orderId)
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(adminView.get("couponCode").asString()).isEqualTo(code);
        assertThat(adminView.get("discount").decimalValue()).isEqualByComparingTo("22.00");
    }

    @Test
    void aFreeDeliveryCouponTakesTheFeeOffARestaurantOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        restaurantCart(customerId);
        coupon(customerId, newCouponCode(), "0.00", true, "Free delivery on us", "STORE");   // the restaurant's own

        JsonNode order = body(place(customerId, newKey(), new BigDecimal("329.00"))   // 359.00 - 30.00
                .andExpect(status().isCreated()));
        UUID orderId = id(order);

        assertThat(order.get("discount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(order.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("329.00");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(orderEvents(orderId, "OrderAwaitingPayment").getFirst().get("amount").decimalValue())
                .isEqualByComparingTo("329.00");
        JsonNode created = orderEvents(orderId, "OrderCreated").getFirst();
        assertThat(created.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(created.get("discount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(created.get("couponName").asString()).isEqualTo("Free delivery on us");
        assertThat(created.get("couponFundedBy").asString()).isEqualTo("STORE");
        assertThat(PRICING.callsOf(customerId).getFirst().body().get("orderType").asString())
                .isEqualTo("RESTAURANT");
    }

    @Test
    void aRedemptionWithoutNameOrFundingStillPlacesTheOrderWithBothNull() throws Exception {
        // a pricing-service from before schemas 1.7.0 (a rolling deploy), or one answering an unknown funder
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String code = newCouponCode();
        coupon(customerId, code, "22.00", false, null, null);

        UUID orderId = id(body(place(customerId, newKey(), new BigDecimal("223.00")).andExpect(status().isCreated())));

        JsonNode created = orderEvents(orderId, "OrderCreated").getFirst();
        assertThat(created.get("couponCode").asString()).isEqualTo(code);
        assertThat(created.get("couponName").isNull()).isTrue();
        assertThat(created.get("couponFundedBy").isNull()).isTrue();

        UUID other = UUID.randomUUID();
        defaultAddress(other);
        groceryCart(other);
        coupon(other, newCouponCode(), "22.00", false, "  Weekend deal ", "SPONSOR");
        UUID otherOrder = id(body(place(other, newKey(), new BigDecimal("223.00")).andExpect(status().isCreated())));
        assertThat(jdbc().sql("SELECT coupon_name, coupon_funded_by FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(otherOrder.toString()).query().singleRow())
                .containsEntry("coupon_name", "Weekend deal").containsEntry("coupon_funded_by", null);
    }

    @Test
    void expectedTotalIsComparedWithTheDiscountedTotal() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        coupon(customerId, newCouponCode(), "22.00", false);

        place(customerId, newKey(), GROCERY_TOTAL)                    // the undiscounted 245.00
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOTAL_CHANGED"))
                .andExpect(jsonPath("$.expectedTotal").value(245.00))
                .andExpect(jsonPath("$.currentTotal").value(223.00));

        assertThat(PRICING.callsOf(customerId)).isEmpty();            // checked before redeeming
        assertThat(orderCount(customerId)).isZero();
    }

    @Test
    void aRefusedRedemptionPlacesNoOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String code = newCouponCode();
        coupon(customerId, code, "22.00", false);
        PRICING.refusals.put(code, new StubPricing.Refusal(409, "COUPON_USAGE_LIMIT", null));

        place(customerId, newKey(), new BigDecimal("223.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_USAGE_LIMIT"))
                .andExpect(jsonPath("$.minOrderSubtotal").doesNotExist());
        assertThat(orderCount(customerId)).isZero();
        assertThat(PRICING.callsOf(customerId)).hasSize(1);

        // the minimum-order reason carries the minimum
        PRICING.refusals.put(code, new StubPricing.Refusal(409, "COUPON_MIN_ORDER", new BigDecimal("499.00")));
        place(customerId, newKey(), new BigDecimal("223.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_MIN_ORDER"))
                .andExpect(jsonPath("$.minOrderSubtotal").value(499.00));
        assertThat(orderCount(customerId)).isZero();

        // once it applies again, the same customer can order (a new key: the failed ones stored nothing)
        PRICING.refusals.remove(code);
        place(customerId, newKey(), new BigDecimal("223.00")).andExpect(status().isCreated());
        assertThat(orderCount(customerId)).isOne();
    }

    @Test
    void aCouponTheCartReportsInvalidBlocksTheCheckoutWithoutARedemption() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        CART.coupons.put(customerId, new StubCart.Coupon(newCouponCode(), false, BigDecimal.ZERO, false,
                "COUPON_EXPIRED"));

        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED"))
                .andExpect(jsonPath("$.issues[0].code").value("COUPON_NOT_APPLICABLE"));
        assertThat(PRICING.callsOf(customerId)).isEmpty();
        assertThat(orderCount(customerId)).isZero();
    }

    @Test
    void pricingUnreachableIs503AndNothingIsStored() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        coupon(customerId, newCouponCode(), "22.00", false);
        PRICING.down = true;

        place(customerId, newKey(), new BigDecimal("223.00"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("pricing"));
        assertThat(orderCount(customerId)).isZero();
    }

    @Test
    void aRedemptionThatDiffersFromThePreviewIsATotalChange() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String code = newCouponCode();
        coupon(customerId, code, "22.00", false);
        // changed meanwhile
        PRICING.offers.put(code, new StubPricing.Offer(new BigDecimal("30.00"), false, null, null));

        place(customerId, newKey(), new BigDecimal("223.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOTAL_CHANGED"))
                .andExpect(jsonPath("$.currentTotal").value(215.00));
        assertThat(orderCount(customerId)).isZero();
    }

    @Test
    void aRetriedPlacementReplaysTheOrderWithoutRedeemingAgain() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        coupon(customerId, newCouponCode(), "22.00", false);
        String key = newKey();

        JsonNode first = body(place(customerId, key, new BigDecimal("223.00")).andExpect(status().isCreated()));
        JsonNode again = body(place(customerId, key, new BigDecimal("223.00"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(again).isEqualTo(first);
        assertThat(orderCount(customerId)).isOne();
        assertThat(PRICING.callsOf(customerId)).hasSize(1);
        assertThat(orderEventTypes(id(first))).containsExactly("OrderCreated");
    }

    @Test
    void aTakenOrderNumberIsRetriedWithTheRedeemedOrderId() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        coupon(customerId, newCouponCode(), "22.00", false);
        UUID first = UUID.randomUUID();
        String taken = order(first, newGroceryOrder(first)).get("orderNumber").asString();
        org.mockito.Mockito.doReturn(taken).doCallRealMethod().when(orderNumbers).next();

        JsonNode order = body(place(customerId, newKey(), new BigDecimal("223.00")).andExpect(status().isCreated()));

        assertThat(order.get("orderNumber").asString()).isNotEqualTo(taken);
        List<StubPricing.Call> calls = PRICING.callsOf(customerId);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().body().get("orderId").asString()).isEqualTo(id(order).toString());
    }
}
