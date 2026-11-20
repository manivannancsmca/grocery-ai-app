package com.grocery.order;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the live checkout preview can refuse (contract §2 steps 2, 3, 5): blocking issues, a changed total, an address
 * that is not the caller's, and cart-service (or one of its dependencies) being unreachable. Nothing is ever stored.
 */
class CheckoutFailuresIntegrationTest extends AbstractIntegrationTest {

    private void assertNothingStored(UUID customerId) {
        assertThat(orderCount(customerId)).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM idempotency_key WHERE scope = ?")
                .param("order:" + customerId).query(Long.class).single()).isZero();
    }

    @Test
    void blockingIssuesAreCheckoutBlockedWithThePreviewsIssues() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        StubCart.Cart cart = groceryCart(customerId);
        UUID dal = cart.lines().get(1).itemId();
        CART.carts.put(customerId, new StubCart.Cart(cart.storeId(), cart.storeType(), cart.storeName(), cart.lines(),
                List.of(StubCart.issue("PRICE_CHANGED", cart.lines().getFirst().itemId(), false,
                                "Basmati Rice now costs ₹50.00 (was ₹45.00)"),
                        StubCart.issue("OUT_OF_STOCK", dal, true, "Toor Dal is out of stock")),
                cart.updatedAt()));

        JsonNode problem = body(place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED")));

        JsonNode issues = problem.get("issues");
        assertThat(issues).hasSize(2);
        assertThat(fields(issues.get(1))).containsExactly("code", "itemId", "blocking", "message");
        assertThat(issues.get(0).get("code").asString()).isEqualTo("PRICE_CHANGED");
        assertThat(issues.get(0).get("blocking").asBoolean()).isFalse();
        assertThat(issues.get(1).get("code").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(issues.get(1).get("itemId").asString()).isEqualTo(dal.toString());
        assertThat(issues.get(1).get("blocking").asBoolean()).isTrue();
        assertThat(issues.get(1).get("message").asString()).isEqualTo("Toor Dal is out of stock");
        assertNothingStored(customerId);
    }

    @Test
    void anEmptyCartOrAMissingAddressIsCheckoutBlocked() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        place(customerId, newKey(), BigDecimal.ONE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED"))
                .andExpect(jsonPath("$.issues[0].code").value("EMPTY_CART"))
                .andExpect(jsonPath("$.issues[0].itemId").isEmpty());

        UUID homeless = UUID.randomUUID();
        groceryCart(homeless);
        place(homeless, newKey(), GROCERY_TOTAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED"))
                .andExpect(jsonPath("$.issues[0].code").value("ADDRESS_REQUIRED"));
        assertNothingStored(customerId);
        assertNothingStored(homeless);
    }

    @Test
    void aDifferentLiveTotalIsTotalChanged() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        String raw = place(customerId, newKey(), new BigDecimal("200"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOTAL_CHANGED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = json.readTree(raw);

        assertThat(problem.get("expectedTotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(problem.get("currentTotal").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(raw).contains("\"currentTotal\":245.00", "\"expectedTotal\":200.00");
        assertNothingStored(customerId);
    }

    @Test
    void cartServiceDownIs503WithTheDependency() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        CART.down = true;
        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("cart"));

        // cart-service is up but could not reach customer-service: the 503 names the service that was unreachable
        CART.down = false;
        CART.customerDown = true;
        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("customer"));

        // a 404 that is not ADDRESS_NOT_FOUND (e.g. the endpoint is not deployed) is not an answer either
        CART.customerDown = false;
        CART.unknownPath = true;
        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.dependency").value("cart"));
        assertNothingStored(customerId);
    }

    @Test
    void aSlowCartTimesOut() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        CART.delayMillis = StubCart.SLOW_MILLIS;

        long started = System.nanoTime();
        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.dependency").value("cart"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(StubCart.SLOW_MILLIS);
        assertNothingStored(customerId);
    }

    @Test
    void anOpenCircuitBreakerFailsFastWithoutCallingCart() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        CART.down = true;
        for (int i = 0; i < 4; i++) {
            place(customerId, newKey(), GROCERY_TOTAL).andExpect(status().isServiceUnavailable());
        }
        assertThat(CART.callsOf(customerId)).hasSize(4);

        CART.down = false;                                    // back, but the breaker stays open for 60 s
        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.dependency").value("cart"));
        assertThat(CART.callsOf(customerId)).hasSize(4);
        assertNothingStored(customerId);
    }

    @Test
    void clientErrorsNeverOpenTheBreaker() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        for (int i = 0; i < 6; i++) {
            placeRequest(customerId, newKey(), placeBody(UUID.randomUUID(), GROCERY_TOTAL, null))
                    .andExpect(status().isNotFound());
        }
        place(customerId, newKey(), GROCERY_TOTAL).andExpect(status().isCreated());
    }

    @Test
    void anAddressThatIsNotTheCallersIsAddressNotFound() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        UUID someoneElse = UUID.randomUUID();
        UUID theirAddress = defaultAddress(someoneElse);

        placeRequest(customerId, newKey(), placeBody(theirAddress, GROCERY_TOTAL, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
        assertThat(CART.callsOf(customerId).getFirst().query()).isEqualTo("addressId=" + theirAddress);
        assertNothingStored(customerId);
    }

    @Test
    void aPreviewThatAllowsCheckoutButLacksDataIsTreatedAsCartFailure() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        StubCart.Cart cart = groceryCart(customerId);
        // a store type order-service does not know
        CART.carts.put(customerId, new StubCart.Cart(cart.storeId(), "PHARMACY", cart.storeName(), cart.lines(),
                List.of(), Instant.now()));

        place(customerId, newKey(), GROCERY_TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("cart"));
        assertNothingStored(customerId);
    }
}
