package com.grocery.cart;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cart's coupon (Phase 12 §2): apply / remove, the preview's discount lines and total, a coupon that cannot be
 * used (422 at apply; later a blocking issue), pricing-service down (COUPON_UNAVAILABLE, never a 500), and a store
 * change dropping a STORE coupon of the old store. pricing-service's evaluate is played by the stub.
 * Grocery cart: 2 × ₹90 = ₹180; the address is 2 km north (2.6 km road: fee ₹25).
 */
class CouponCartIntegrationTest extends AbstractIntegrationTest {

    private UUID vendorId;

    /** A grocery cart of ₹180 (2 × ₹90) and a default address with a ₹25 delivery fee. */
    private UUID groceryCart(UUID customerId) throws Exception {
        vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 50);
        addProduct(customerId, rice, 2).andExpect(status().isOk());
        address(customerId, 2.0, true);
        return rice;
    }

    private List<JsonNode> evaluateCalls() {
        return STUB.calls("/internal/coupons/evaluate").stream().map(StubServices.Call::body).toList();
    }

    @Test
    void aValidCouponIsAppliedAndDiscountsThePreview() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);
        String code = coupon("save10", "PLATFORM", null, "ANY", "PERCENT", "10", "15", "100");

        applyCoupon(customerId, "  " + code.toLowerCase() + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(180.00))
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.valid").value(true))
                .andExpect(jsonPath("$.coupon.discount").value(15.00))           // 10 % = 18, capped at 15
                .andExpect(jsonPath("$.coupon.deliveryFeeDiscount").value(0.00))
                .andExpect(jsonPath("$.coupon.reason").doesNotExist())
                .andExpect(jsonPath("$.coupon.message").doesNotExist())
                .andExpect(jsonPath("$.issues.length()").value(0));

        // evaluated once, with the customer's token, for the cart's store and subtotal (no delivery fee yet)
        List<StubServices.Call> calls = STUB.calls("/internal/coupons/evaluate");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().authorization()).isEqualTo("Bearer token-" + customerId);
        JsonNode request = calls.getFirst().body();
        assertThat(request.get("code").asString()).isEqualTo(code);
        assertThat(request.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(request.get("storeId").asString()).isEqualTo(vendorId.toString());
        assertThat(request.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(request.get("subtotal").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(request.get("deliveryFee").decimalValue()).isEqualByComparingTo("0.00");

        STUB.calls.clear();
        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(180.00))
                .andExpect(jsonPath("$.delivery.fee").value(25.00))
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.valid").value(true))
                .andExpect(jsonPath("$.coupon.discount").value(15.00))
                .andExpect(jsonPath("$.discount").value(15.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00))
                .andExpect(jsonPath("$.total").value(190.00))                    // 180 + 25 − 15
                .andExpect(jsonPath("$.canCheckout").value(true));
        assertThat(evaluateCalls()).hasSize(1);
        assertThat(evaluateCalls().getFirst().get("deliveryFee").decimalValue()).isEqualByComparingTo("25.00");

        getCart(customerId)
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.valid").value(true));
    }

    @Test
    void freeDeliveryRemovesTheDeliveryFeeInThePreview() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);
        String code = coupon("freedel", "PLATFORM", null, "GROCERY", "FREE_DELIVERY", null, null, "0");
        applyCoupon(customerId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.valid").value(true))
                .andExpect(jsonPath("$.coupon.deliveryFeeDiscount").value(0.00));   // no fee in the cart view

        preview(customerId, null)
                .andExpect(jsonPath("$.delivery.fee").value(25.00))
                .andExpect(jsonPath("$.coupon.deliveryFeeDiscount").value(25.00))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(25.00))
                .andExpect(jsonPath("$.total").value(180.00))
                .andExpect(jsonPath("$.canCheckout").value(true));
    }

    @Test
    void atLeastOneRupeeStaysPayable() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID store = vendor("Green Basket");
        UUID ghee = product(store, "Ghee", "550.00", "499.00", 5);
        addProduct(customerId, ghee, 1).andExpect(status().isOk());
        address(customerId, 2.0, true);                                          // free delivery from ₹499
        // the stub answers the full ₹499 off (it does not apply pricing-service's floor): the cart keeps ₹1.00
        String code = coupon("all499", "PLATFORM", null, "ANY", "FLAT", "499", null, "0");
        applyCoupon(customerId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.discount").value(498.00));
        preview(customerId, null)
                .andExpect(jsonPath("$.delivery.fee").value(0.00))
                .andExpect(jsonPath("$.discount").value(498.00))
                .andExpect(jsonPath("$.total").value(1.00))
                .andExpect(jsonPath("$.canCheckout").value(true));
    }

    @Test
    void aCouponThatCannotBeUsedIsNotApplied() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);

        applyCoupon(customerId, "NOPE-1234")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_NOT_FOUND"))
                .andExpect(jsonPath("$.code").value("NOPE-1234"))
                .andExpect(jsonPath("$.detail").value("Coupon NOPE-1234 does not exist"));

        String big = coupon("big", "PLATFORM", null, "ANY", "FLAT", "50", null, "199");
        applyCoupon(customerId, big)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_MIN_ORDER"))
                .andExpect(jsonPath("$.minOrderSubtotal").value(199.00))
                .andExpect(jsonPath("$.detail").value("Add ₹19.00 more to use " + big + " (minimum order ₹199.00)"));

        String restaurantsOnly = coupon("rest", "PLATFORM", null, "RESTAURANT", "FLAT", "50", null, "0");
        applyCoupon(customerId, restaurantsOnly)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("COUPON_WRONG_STORE"))
                .andExpect(jsonPath("$.detail").value("Coupon " + restaurantsOnly + " cannot be used at Green Basket"));

        getCart(customerId).andExpect(jsonPath("$.coupon").isEmpty());          // nothing stored

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cart/coupon")
                        .with(customer(customerId)).contentType("application/json").content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void anEmptyCartTakesNoCoupon() throws Exception {
        String code = coupon("empty", "PLATFORM", null, "ANY", "FLAT", "50", null, "0");
        applyCoupon(UUID.randomUUID(), code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("EMPTY_CART"));
    }

    @Test
    void aCouponThatNoLongerAppliesStaysVisibleAndBlocksCheckout() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID rice = groceryCart(customerId);
        String code = coupon("min150", "PLATFORM", null, "ANY", "FLAT", "40", null, "150");
        applyCoupon(customerId, code).andExpect(status().isOk());

        // fewer items: below the coupon's minimum order
        setQuantity(customerId, rice, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(90.00))
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.coupon.reason").value("COUPON_MIN_ORDER"))
                .andExpect(jsonPath("$.coupon.minOrderSubtotal").value(150.00))
                .andExpect(jsonPath("$.coupon.discount").value(0.00))
                .andExpect(jsonPath("$.coupon.message").value("Add ₹60.00 more to use " + code
                        + " (minimum order ₹150.00)"))
                .andExpect(jsonPath("$.issues[0].code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.issues[0].blocking").value(true))
                .andExpect(jsonPath("$.issues[0].itemId").isEmpty());

        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00))
                .andExpect(jsonPath("$.total").value(115.00))                    // 90 + 25, nothing off
                .andExpect(jsonPath("$.issues[*].code").value(hasItem("COUPON_NOT_APPLICABLE")))
                .andExpect(jsonPath("$.canCheckout").value(false));

        // back above the minimum, then the coupon expires
        setQuantity(customerId, rice, 2).andExpect(jsonPath("$.coupon.valid").value(true));
        setCouponReason(code, "COUPON_EXPIRED");
        preview(customerId, null)
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.coupon.reason").value("COUPON_EXPIRED"))
                .andExpect(jsonPath("$.coupon.message").value("Coupon " + code + " has expired"))
                .andExpect(jsonPath("$.coupon.minOrderSubtotal").doesNotExist())
                .andExpect(jsonPath("$.total").value(205.00))
                .andExpect(jsonPath("$.canCheckout").value(false));
        setCouponReason(code, "COUPON_CUSTOMER_LIMIT");
        getCart(customerId)
                .andExpect(jsonPath("$.coupon.message").value("You have already used coupon " + code));

        // removing it unblocks the checkout
        removeCoupon(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon").isEmpty())
                .andExpect(jsonPath("$.issues[*].code").value(not(hasItem("COUPON_NOT_APPLICABLE"))));
        removeCoupon(customerId).andExpect(status().isOk());                    // idempotent
        preview(customerId, null)
                .andExpect(jsonPath("$.coupon").isEmpty())
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.total").value(205.00))
                .andExpect(jsonPath("$.canCheckout").value(true));
    }

    @Test
    void pricingDownMakesTheCouponUnavailableNeverA500() throws Exception {
        // restaurant cart: prices come from restaurant-service, only the coupon needs pricing-service
        UUID customerId = UUID.randomUUID();
        UUID restaurantId = restaurant("Spice Route");
        UUID biryani = menuItem(restaurantId, "Chicken Biryani", "249.00");
        addMenuItem(customerId, restaurantId, biryani, 1).andExpect(status().isOk());
        address(customerId, 2.0, true);                                           // ₹30 fee
        String code = coupon("dinner", "PLATFORM", null, "RESTAURANT", "FLAT", "50", null, "0");
        applyCoupon(customerId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.discount").value(50.00));
        preview(customerId, null).andExpect(jsonPath("$.total").value(229.00));   // 249 + 30 − 50

        STUB.down.add("pricing");
        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(249.00))
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.coupon.reason").value("COUPON_UNAVAILABLE"))
                .andExpect(jsonPath("$.coupon.message").value("Coupon " + code
                        + " could not be checked. Please try again shortly."))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.total").value(279.00))
                .andExpect(jsonPath("$.issues[*].code").value(hasItem("COUPON_NOT_APPLICABLE")))
                .andExpect(jsonPath("$.canCheckout").value(false));
        getCart(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.reason").value("COUPON_UNAVAILABLE"));
        applyCoupon(customerId, code)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("pricing"));
        removeCoupon(customerId).andExpect(status().isOk()).andExpect(jsonPath("$.coupon").isEmpty());
    }

    @Test
    void aGroceryCartWithoutPricesDoesNotAskForTheCoupon() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);
        String code = coupon("noprice", "PLATFORM", null, "ANY", "FLAT", "20", null, "0");
        applyCoupon(customerId, code).andExpect(status().isOk());

        STUB.down.add("pricing");
        STUB.calls.clear();
        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].issue").value("PRICING_UNAVAILABLE"))
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.coupon.reason").value("COUPON_UNAVAILABLE"))
                .andExpect(jsonPath("$.canCheckout").value(false));
        assertThat(evaluateCalls()).isEmpty();
        applyCoupon(customerId, code)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.dependency").value("pricing"));
    }

    @Test
    void changingToAnotherStoreDropsAStoreCouponOfTheOldStore() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);
        UUID greenBasket = vendorId;
        String storeCoupon = coupon("gb", "STORE", greenBasket, "GROCERY", "FLAT", "20", null, "0");
        applyCoupon(customerId, storeCoupon)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.valid").value(true));

        UUID freshMart = vendor("Fresh Mart");
        UUID atta = product(freshMart, "Atta", "300.00", "280.00", 10);
        addProduct(customerId, atta, 1).andExpect(status().isConflict());      // the store conflict as before
        addProductReplacing(customerId, atta, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.id").value(freshMart.toString()))
                .andExpect(jsonPath("$.coupon").isEmpty())
                .andExpect(jsonPath("$.issues.length()").value(0));

        // a PLATFORM coupon moves with the cart
        String platform = coupon("all", "PLATFORM", null, "ANY", "FLAT", "20", null, "0");
        applyCoupon(customerId, platform).andExpect(status().isOk());
        UUID ghee = product(greenBasket, "Ghee", "550.00", "499.00", 5);
        addProductReplacing(customerId, ghee, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.id").value(greenBasket.toString()))
                .andExpect(jsonPath("$.coupon.code").value(platform))
                .andExpect(jsonPath("$.coupon.valid").value(true))
                .andExpect(jsonPath("$.coupon.discount").value(20.00));

        // emptied by hand, then filled from another store: the old store's coupon goes too
        applyCoupon(customerId, coupon("gb2", "STORE", greenBasket, "GROCERY", "FLAT", "10", null, "0"))
                .andExpect(status().isOk());
        removeItem(customerId, ghee)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.valid").value(false))
                .andExpect(jsonPath("$.coupon.reason").value("EMPTY_CART"));
        addProduct(customerId, atta, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon").isEmpty());
    }

    @Test
    void emptyingTheCartRemovesTheCoupon() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId);
        String code = coupon("clear", "PLATFORM", null, "ANY", "FLAT", "20", null, "0");
        applyCoupon(customerId, code).andExpect(status().isOk());
        clearCart(customerId).andExpect(status().isNoContent());
        getCart(customerId)
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.coupon").isEmpty());
    }
}
