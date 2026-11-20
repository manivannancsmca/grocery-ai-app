package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 (docs/api/phase-11-agentic-ai-and-approvals.md §5, Phase 6 contract §2 step 3): the optional
 * {@code expectedCartUpdatedAt} binds an order to the cart the customer saw. Present and different from the live
 * preview's {@code updatedAt} (compared in milliseconds, the cart's precision) → 409 {@code CART_CHANGED} with
 * {@code expectedCartUpdatedAt} / {@code currentCartUpdatedAt}; checked after the blocking issues and before
 * {@code TOTAL_CHANGED}; part of the Idempotency-Key's body; absent → exactly as before.
 */
class CartChangedIntegrationTest extends AbstractIntegrationTest {

    /** The updatedAt of {@link #groceryCart}. */
    private static final String CART_UPDATED_AT = "2026-11-11T10:00:00.123Z";

    private static Map<String, Object> cartBody(BigDecimal expectedTotal, String expectedCartUpdatedAt) {
        Map<String, Object> body = placeBody(null, expectedTotal, null);
        body.put("expectedCartUpdatedAt", expectedCartUpdatedAt);
        return body;
    }

    private void assertNothingStored(UUID customerId) {
        assertThat(orderCount(customerId)).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM idempotency_key WHERE scope = ?")
                .param("order:" + customerId).query(Long.class).single()).isZero();
    }

    @Test
    void theCartTheCustomerSawIsPlaced() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        placeRequest(customerId, newKey(), cartBody(GROCERY_TOTAL, CART_UPDATED_AT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(245.00));

        // the cart's own precision is milliseconds: finer digits of the same instant are the same cart
        UUID other = UUID.randomUUID();
        defaultAddress(other);
        groceryCart(other);
        placeRequest(other, newKey(), cartBody(GROCERY_TOTAL, "2026-11-11T10:00:00.123456Z"))
                .andExpect(status().isCreated());
        // and an order without the field is placed as before
        UUID plain = UUID.randomUUID();
        defaultAddress(plain);
        groceryCart(plain);
        place(plain, newKey(), GROCERY_TOTAL).andExpect(status().isCreated());
    }

    @Test
    void aCartThatChangedSinceThePreviewIsCartChanged() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        String raw = placeRequest(customerId, newKey(), cartBody(GROCERY_TOTAL, "2026-11-11T09:59:30Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_CHANGED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = json.readTree(raw);
        assertThat(problem.get("expectedCartUpdatedAt").asString()).isEqualTo("2026-11-11T09:59:30Z");
        assertThat(problem.get("currentCartUpdatedAt").asString()).isEqualTo(CART_UPDATED_AT);
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertNothingStored(customerId);
    }

    @Test
    void blockingIssuesComeFirstAndACartChangeBeforeATotalChange() throws Exception {
        // a changed cart whose total changed too: CART_CHANGED (the items must be reviewed, not just the price)
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        placeRequest(customerId, newKey(), cartBody(new BigDecimal("200.00"), "2026-11-11T09:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_CHANGED"));
        // the same cart with only the total wrong: TOTAL_CHANGED
        placeRequest(customerId, newKey(), cartBody(new BigDecimal("200.00"), CART_UPDATED_AT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOTAL_CHANGED"));

        // a cart that cannot be checked out at all: CHECKOUT_BLOCKED, whatever the client expects
        UUID blocked = UUID.randomUUID();
        defaultAddress(blocked);
        StubCart.Cart cart = groceryCart(blocked);
        CART.carts.put(blocked, new StubCart.Cart(cart.storeId(), cart.storeType(), cart.storeName(), cart.lines(),
                List.of(StubCart.issue("OUT_OF_STOCK", cart.lines().getFirst().itemId(), true, "Out of stock")),
                Instant.parse("2026-11-11T10:05:00Z")));
        placeRequest(blocked, newKey(), cartBody(new BigDecimal("200.00"), CART_UPDATED_AT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED"));
        // an emptied cart (e.g. the order was placed by hand meanwhile) is blocked as before
        UUID emptied = UUID.randomUUID();
        defaultAddress(emptied);
        placeRequest(emptied, newKey(), cartBody(GROCERY_TOTAL, CART_UPDATED_AT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_BLOCKED"))
                .andExpect(jsonPath("$.issues[0].code").value("EMPTY_CART"));
        assertNothingStored(customerId);
        assertNothingStored(blocked);
        assertNothingStored(emptied);
    }

    @Test
    void theExpectedCartIsPartOfTheIdempotentBody() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String key = UUID.randomUUID().toString();                 // the orchestrator sends the approval id

        JsonNode first = body(placeRequest(customerId, key, cartBody(GROCERY_TOTAL, CART_UPDATED_AT))
                .andExpect(status().isCreated()));
        // the same body again: the original order, nothing re-checked — even after the cart changed
        CART.carts.remove(customerId);
        placeRequest(customerId, key, cartBody(GROCERY_TOTAL, CART_UPDATED_AT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id(first).toString()));
        // another expectation (or none) with the same key is another body
        placeRequest(customerId, key, cartBody(GROCERY_TOTAL, "2026-11-11T10:00:01Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        place(customerId, key, GROCERY_TOTAL)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(CART.callsOf(customerId)).hasSize(1);
    }

    @Test
    void aMalformedExpectationIsABadRequest() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        mvc.perform(post("/api/orders").with(customer(customerId)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTotal\":245.00,\"expectedCartUpdatedAt\":\"yesterday\"}"))
                .andExpect(status().isBadRequest());
        assertThat(CART.callsOf(customerId)).isEmpty();
        assertNothingStored(customerId);
    }
}
