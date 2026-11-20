package com.grocery.pricing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /internal/coupons/evaluate} (Phase 12 §2): the amounts of every discount type and every reason. */
class CouponEvaluateIntegrationTest extends AbstractIntegrationTest {

    private final UUID customerId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    private String coupon(String fields) throws Exception {
        String code = code("ev");
        adminCoupon("\"code\":\"%s\",\"name\":\"Evaluate\",%s".formatted(code, fields));
        return code;
    }

    @Test
    void percentWithMaxDiscount() throws Exception {
        String code = coupon("\"discountType\":\"PERCENT\",\"value\":20,\"maxDiscount\":75," + validWindow());
        evaluate(customerId, evaluation(code.toLowerCase(), customerId, storeId, "GROCERY", "300.00", "25.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(60.00))                  // 20 % of 300
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.minOrderSubtotal").doesNotExist())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.scope").value("PLATFORM"))
                .andExpect(jsonPath("$.storeId").doesNotExist())
                .andExpect(jsonPath("$.discountType").value("PERCENT"));
        evaluate(customerId, evaluation(code, customerId, storeId, "GROCERY", "1000.00", "25.00"))
                .andExpect(jsonPath("$.discount").value(75.00));                 // 200 capped at 75
    }

    @Test
    void flatIsCappedAtTheSubtotal() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":200," + validWindow());
        evaluate(customerId, evaluation(code, customerId, storeId, "RESTAURANT", "150.00", "30.00"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(150.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00));
        evaluate(customerId, evaluation(code, customerId, storeId, "RESTAURANT", "450.00", "30.00"))
                .andExpect(jsonPath("$.discount").value(200.00));
    }

    @Test
    void atLeastOneRupeeStaysPayable() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":150," + validWindow());
        evaluate(customerId, evaluation(code, customerId, storeId, "GROCERY", "150.00", "0.00"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(149.00))                 // ₹1.00 left to pay
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00));
        UUID orderId = UUID.randomUUID();
        redeem(customerId, redemption(code, orderId, customerId, storeId, "GROCERY", "150.00", "0.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount").value(149.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00));
    }

    @Test
    void freeDeliveryRemovesTheDeliveryFee() throws Exception {
        String code = coupon("\"discountType\":\"FREE_DELIVERY\"," + validWindow());
        evaluate(customerId, evaluation(code, customerId, storeId, "GROCERY", "180.00", "37.00"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(37.00));
        // no deliveryFee in the request = 0
        evaluate(customerId, """
                {"code":"%s","storeId":"%s","orderType":"GROCERY","subtotal":180}""".formatted(code, storeId))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00));
    }

    @Test
    void minimumOrder() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":50,\"minOrderSubtotal\":199," + validWindow());
        evaluate(customerId, evaluation(code, customerId, storeId, "GROCERY", "198.99", "25.00"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_MIN_ORDER"))
                .andExpect(jsonPath("$.minOrderSubtotal").value(199.00))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00));
        evaluate(customerId, evaluation(code, customerId, storeId, "GROCERY", "199.00", "25.00"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(50.00));
    }

    @Test
    void validityWindow() throws Exception {
        Instant now = Instant.now();
        String notStarted = coupon("\"discountType\":\"FREE_DELIVERY\",\"validFrom\":\"%s\",\"validUntil\":\"%s\""
                .formatted(now.plusSeconds(3600), now.plusSeconds(7200)));
        String expired = coupon("\"discountType\":\"FREE_DELIVERY\",\"validFrom\":\"%s\",\"validUntil\":\"%s\""
                .formatted(now.minusSeconds(7200), now.minusSeconds(1)));
        evaluate(customerId, evaluation(notStarted, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_NOT_STARTED"));
        evaluate(customerId, evaluation(expired, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_EXPIRED"));
    }

    @Test
    void unknownAndPaused() throws Exception {
        evaluate(customerId, evaluation("NOPE-" + UUID.randomUUID().toString().substring(0, 8), customerId, storeId,
                "GROCERY", "100", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_NOT_FOUND"))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.scope").doesNotExist());
        String paused = coupon("\"discountType\":\"FREE_DELIVERY\",\"active\":false," + validWindow());
        evaluate(customerId, evaluation(paused, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_PAUSED"));
    }

    @Test
    void wrongStore() throws Exception {
        String restaurantsOnly = coupon("\"discountType\":\"FREE_DELIVERY\",\"storeType\":\"RESTAURANT\"," + validWindow());
        evaluate(customerId, evaluation(restaurantsOnly, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.reason").value("COUPON_WRONG_STORE"));
        evaluate(customerId, evaluation(restaurantsOnly, customerId, storeId, "RESTAURANT", "100", "25"))
                .andExpect(jsonPath("$.valid").value(true));

        UUID vendorId = UUID.randomUUID();
        String storeCode = body(createCoupon("/api/vendor/coupons", vendor(vendorId),
                "{\"code\":\"%s\",\"name\":\"Store\",\"discountType\":\"FLAT\",\"value\":20,%s}"
                        .formatted(code("st"), validWindow()))
                .andExpect(status().isCreated())).get("code").asString();
        evaluate(customerId, evaluation(storeCode, customerId, UUID.randomUUID(), "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_WRONG_STORE"));
        evaluate(customerId, evaluation(storeCode, customerId, vendorId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(20.00))
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeId").value(vendorId.toString()));
    }

    @Test
    void usageAndCustomerLimits() throws Exception {
        String once = coupon("\"discountType\":\"FLAT\",\"value\":10,\"totalUsageLimit\":1,\"perCustomerLimit\":5,"
                + validWindow());
        UUID other = UUID.randomUUID();
        redeem(other, redemption(once, UUID.randomUUID(), other, storeId, "GROCERY", "100", "25"))
                .andExpect(status().isOk());
        evaluate(customerId, evaluation(once, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_USAGE_LIMIT"));

        String perCustomer = coupon("\"discountType\":\"FLAT\",\"value\":10,\"perCustomerLimit\":1," + validWindow());
        redeem(customerId, redemption(perCustomer, UUID.randomUUID(), customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(status().isOk());
        evaluate(customerId, evaluation(perCustomer, customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("COUPON_CUSTOMER_LIMIT"));
        evaluate(other, evaluation(perCustomer, other, storeId, "GROCERY", "100", "25"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void theCustomerIsTheCaller() throws Exception {
        String code = coupon("\"discountType\":\"FREE_DELIVERY\"," + validWindow());
        evaluate(customerId, evaluation(code, UUID.randomUUID(), storeId, "GROCERY", "100", "25"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_MISMATCH"));
        evaluate(customerId, "{\"code\":\"%s\",\"orderType\":\"GROCERY\",\"subtotal\":10}".formatted(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/internal/coupons/evaluate")
                        .contentType("application/json")
                        .content(evaluation(code, customerId, storeId, "GROCERY", "100", "25")))
                .andExpect(status().isUnauthorized());
    }
}
