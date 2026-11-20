package com.grocery.pricing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /internal/coupons/redeem} (Phase 12 §2): reservation, idempotency per order, 409 reasons, and the usage
 * limits under real concurrency (requests released together from several threads).
 */
class CouponRedeemIntegrationTest extends AbstractIntegrationTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final UUID storeId = UUID.randomUUID();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private String coupon(String fields) throws Exception {
        String code = code("rd");
        adminCoupon("\"code\":\"%s\",\"name\":\"Redeem\",%s,%s".formatted(code, fields, validWindow()));
        return code;
    }

    private long redemptions(String code, String status) {
        return jdbc().sql("SELECT COUNT(*) FROM coupon_redemption WHERE coupon_code = ? AND status = ?")
                .param(code).param(status).query(Long.class).single();
    }

    @Test
    void redeemReservesAndIsIdempotentPerOrder() throws Exception {
        String code = coupon("\"discountType\":\"PERCENT\",\"value\":10,\"maxDiscount\":40");
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String body = redemption(code.toLowerCase(), orderId, customerId, storeId, "GROCERY", "300.00", "25.00");

        JsonNode first = body(redeem(customerId, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptionId").isNotEmpty())
                .andExpect(jsonPath("$.discount").value(30.00))
                .andExpect(jsonPath("$.deliveryFeeDiscount").value(0.00)));
        assertThat(redemptionStatus(orderId)).isEqualTo("RESERVED");

        // the same order again (a retry, even with another subtotal): the same redemption, nothing new
        redeem(customerId, redemption(code, orderId, customerId, storeId, "GROCERY", "900.00", "25.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptionId").value(first.get("redemptionId").asString()))
                .andExpect(jsonPath("$.discount").value(30.00))
                .andExpect(jsonPath("$.couponName").value("Redeem"))
                .andExpect(jsonPath("$.fundedBy").value("PLATFORM"));
        assertThat(redemptions(code, "RESERVED")).isEqualTo(1);

        Map<String, Object> row = jdbc().sql("""
                        SELECT BIN_TO_UUID(customer_id) AS customer, BIN_TO_UUID(store_id) AS store, order_type,
                               subtotal, delivery_fee, discount, delivery_fee_discount
                        FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)""")
                .param(orderId.toString()).query().singleRow();
        assertThat(row.get("customer")).isEqualTo(customerId.toString());
        assertThat(row.get("store")).isEqualTo(storeId.toString());
        assertThat(row.get("order_type")).isEqualTo("GROCERY");
        assertThat(row.get("subtotal").toString()).isEqualTo("300.00");
        assertThat(row.get("delivery_fee").toString()).isEqualTo("25.00");

        // the order cannot redeem another coupon, and another customer cannot reuse the order id
        String other = coupon("\"discountType\":\"FREE_DELIVERY\"");
        redeem(customerId, redemption(other, orderId, customerId, storeId, "GROCERY", "300.00", "25.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_REDEMPTION_CONFLICT"));
        UUID stranger = UUID.randomUUID();
        redeem(stranger, redemption(code, orderId, stranger, storeId, "GROCERY", "300.00", "25.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_REDEMPTION_CONFLICT"));

        // the coupon view counts the reserved use
        mvc.perform(get("/api/admin/coupons").param("q", code).with(opsAdmin()))
                .andExpect(jsonPath("$.content[0].usedCount").value(1))
                .andExpect(jsonPath("$.content[0].discountGiven").value(0.00));   // not confirmed yet
    }

    @Test
    void theRedemptionNamesTheCouponAndWhoFundsItsDiscount() throws Exception {
        String platform = coupon("\"discountType\":\"FLAT\",\"value\":20");
        String store = code("rd");
        adminCoupon(("\"code\":\"%s\",\"name\":\"Store treat\",\"scope\":\"STORE\",\"storeId\":\"%s\","
                + "\"storeType\":\"GROCERY\",\"discountType\":\"FLAT\",\"value\":15,%s")
                .formatted(store, storeId, validWindow()));
        UUID customerId = UUID.randomUUID();

        redeem(customerId, redemption(platform, UUID.randomUUID(), customerId, storeId, "GROCERY", "300.00", "25.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponName").value("Redeem"))
                .andExpect(jsonPath("$.fundedBy").value("PLATFORM"));
        redeem(customerId, redemption(store, UUID.randomUUID(), customerId, storeId, "GROCERY", "300.00", "25.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount").value(15.00))
                .andExpect(jsonPath("$.couponName").value("Store treat"))
                .andExpect(jsonPath("$.fundedBy").value("STORE"));
    }

    @Test
    void aCouponThatCannotBeUsedIs409WithTheReason() throws Exception {
        UUID customerId = UUID.randomUUID();
        redeem(customerId, redemption("NOPE-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_NOT_FOUND"));
        String minOrder = coupon("\"discountType\":\"FLAT\",\"value\":50,\"minOrderSubtotal\":499");
        UUID orderId = UUID.randomUUID();
        redeem(customerId, redemption(minOrder, orderId, customerId, storeId, "GROCERY", "498", "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.reason").value("COUPON_MIN_ORDER"))
                .andExpect(jsonPath("$.minOrderSubtotal").value(499.00))
                .andExpect(jsonPath("$.detail").value("Coupon " + minOrder + " needs a minimum order of ₹499.00"));
        assertThat(redemptionStatus(orderId)).isNull();
        String wrongStore = coupon("\"discountType\":\"FREE_DELIVERY\",\"storeType\":\"RESTAURANT\"");
        redeem(customerId, redemption(wrongStore, UUID.randomUUID(), customerId, storeId, "GROCERY", "100", "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("COUPON_WRONG_STORE"));
        redeem(customerId, redemption(wrongStore, UUID.randomUUID(), UUID.randomUUID(), storeId, "RESTAURANT", "100",
                "25"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_MISMATCH"));
        mvc.perform(post("/internal/coupons/redeem").with(customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + wrongStore + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theTotalLimitHoldsUnderConcurrentRedeems() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":25,\"totalUsageLimit\":1,\"perCustomerLimit\":5");
        List<MockHttpServletResponse> responses = concurrently(5, i -> {
            UUID customerId = UUID.randomUUID();
            return new Call(customerId, redemption(code, UUID.randomUUID(), customerId, storeId, "GROCERY", "200",
                    "25"));
        });
        assertThat(responses).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatus() == 409).hasSize(4)
                .allSatisfy(r -> assertThat(contentOf(r)).contains("\"reason\":\"COUPON_USAGE_LIMIT\""));
        assertThat(redemptions(code, "RESERVED")).isEqualTo(1);
    }

    @Test
    void aLimitOfTwoLetsExactlyTwoOfSixThrough() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":25,\"totalUsageLimit\":2,\"perCustomerLimit\":5");
        List<MockHttpServletResponse> responses = concurrently(6, i -> {
            UUID customerId = UUID.randomUUID();
            return new Call(customerId, redemption(code, UUID.randomUUID(), customerId, storeId, "GROCERY", "200",
                    "25"));
        });
        assertThat(responses).filteredOn(r -> r.getStatus() == 200).hasSize(2);
        assertThat(responses).filteredOn(r -> r.getStatus() == 409).hasSize(4);
        assertThat(redemptions(code, "RESERVED")).isEqualTo(2);
    }

    @Test
    void thePerCustomerLimitHoldsUnderConcurrentRedeems() throws Exception {
        String code = coupon("\"discountType\":\"FREE_DELIVERY\",\"perCustomerLimit\":1");
        UUID customerId = UUID.randomUUID();
        List<MockHttpServletResponse> responses = concurrently(5, i -> new Call(customerId,
                redemption(code, UUID.randomUUID(), customerId, storeId, "GROCERY", "200", "25")));
        assertThat(responses).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatus() == 409).hasSize(4)
                .allSatisfy(r -> assertThat(contentOf(r)).contains("\"reason\":\"COUPON_CUSTOMER_LIMIT\""));
        assertThat(redemptions(code, "RESERVED")).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneOrderGetOneRedemption() throws Exception {
        String code = coupon("\"discountType\":\"FLAT\",\"value\":25,\"perCustomerLimit\":1");
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        List<MockHttpServletResponse> responses = concurrently(5, i -> new Call(customerId,
                redemption(code, orderId, customerId, storeId, "GROCERY", "200", "25")));
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
        assertThat(responses.stream().map(r -> json.readTree(contentOf(r)).get("redemptionId").asString()).distinct())
                .hasSize(1);
        assertThat(redemptions(code, "RESERVED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private record Call(UUID customerId, String body) {
    }

    /** Runs {@code count} redeems released at the same moment from separate threads. */
    private List<MockHttpServletResponse> concurrently(int count, IntFunction<Call> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Call call = calls.apply(i);
            futures.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post("/internal/coupons/redeem").with(customer(call.customerId()))
                                .contentType(MediaType.APPLICATION_JSON).content(call.body()))
                        .andReturn().getResponse();
            }));
        }
        start.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }

    private static String contentOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
