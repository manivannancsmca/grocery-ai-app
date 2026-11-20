package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion "retrying {@code POST /api/orders} with the same Idempotency-Key creates exactly one order"
 * (contract §2 step 1), including truly parallel requests.
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private long keyRows(UUID customerId) {
        return jdbc().sql("SELECT COUNT(*) FROM idempotency_key WHERE scope = ?")
                .param("order:" + customerId).query(Long.class).single();
    }

    @Test
    void aRetryWithTheSameKeyAnswersWithTheOriginalOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String key = newKey();

        JsonNode first = body(place(customerId, key, GROCERY_TOTAL)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
        JsonNode retry = body(place(customerId, key, new BigDecimal("245.0"))   // the same amount
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/orders/" + id(first))));

        assertThat(retry).isEqualTo(first);
        assertThat(CART.callsOf(customerId)).hasSize(1);                     // a replay re-checks nothing
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(orderEventTypes(id(first))).containsExactly("OrderCreated");

        // still the same order after the cart changed (a new attempt would be TOTAL_CHANGED) …
        CART.carts.remove(customerId);
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id(first).toString()));
        // … and after it moved on: the replay shows the order as it is now
        inventoryListener.on(stockReserved(newEventId(), id(first)));
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(CART.callsOf(customerId)).hasSize(1);
    }

    @Test
    void parallelRequestsWithOneKeyCreateExactlyOneOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String key = newKey();
        int requests = 8;
        // every request passes the fast look-up before the first one commits: they all meet at the claim
        CART.delayMillis = 300;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return place(customerId, key, GROCERY_TOTAL).andReturn().getResponse();
                }));
            }
            start.countDown();
        }

        List<String> ids = new ArrayList<>();
        int replayed = 0;
        for (Future<MockHttpServletResponse> future : futures) {
            MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
            ids.add(json.readTree(response.getContentAsString()).get("id").asString());
            if ("true".equals(response.getHeader("Idempotent-Replayed"))) {
                replayed++;
            }
        }
        assertThat(ids).hasSize(requests).containsOnly(ids.getFirst());
        assertThat(replayed).isEqualTo(requests - 1);
        // (normally all of them) got past the fast look-up and met at the claim inside the creating transaction
        assertThat(CART.callsOf(customerId).size()).isGreaterThanOrEqualTo(2);
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(keyRows(customerId)).isEqualTo(1);
        assertThat(orderEventTypes(UUID.fromString(ids.getFirst()))).containsExactly("OrderCreated");
    }

    @Test
    void theSameKeyWithAnotherBodyIs422() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID home = defaultAddress(customerId);
        groceryCart(customerId);
        String key = newKey();
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isCreated());

        place(customerId, key, new BigDecimal("999.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        placeRequest(customerId, key, placeBody(null, GROCERY_TOTAL, "Leave at the door"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        placeRequest(customerId, key, placeBody(home, GROCERY_TOTAL, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(CART.callsOf(customerId)).hasSize(1);
    }

    @Test
    void theKeyIsRequiredAndCheckedFirst() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);

        // missing: checked before the (here invalid) body
        mvc.perform(post("/api/orders").with(customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"no total\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        place(customerId, "   ", GROCERY_TOTAL)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        for (String invalid : List.of("short12", "has space in it", "semi;colon-key", "x".repeat(101), "key/with/slash")) {
            place(customerId, invalid, GROCERY_TOTAL)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
        }
        // the allowed alphabet and lengths: 8–100 of A-Z a-z 0-9 . _ : -
        place(customerId, "Ab9._:-x", GROCERY_TOTAL).andExpect(status().isCreated());
        place(customerId, "k".repeat(100), GROCERY_TOTAL).andExpect(status().isCreated());

        assertThat(orderCount(customerId)).isEqualTo(2);
        assertThat(CART.callsOf(customerId)).hasSize(2);
    }

    @Test
    void keysAreScopedPerCustomer() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        for (UUID customerId : List.of(alice, bob)) {
            defaultAddress(customerId);
            groceryCart(customerId);
        }
        String key = newKey();

        JsonNode aliceOrder = body(place(alice, key, GROCERY_TOTAL).andExpect(status().isCreated()));
        JsonNode bobOrder = body(place(bob, key, GROCERY_TOTAL)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));

        assertThat(id(bobOrder)).isNotEqualTo(id(aliceOrder));
        assertThat(orderCount(alice)).isEqualTo(1);
        assertThat(orderCount(bob)).isEqualTo(1);
    }

    @Test
    void aFailedAttemptStoresNothingAndCanBeRetriedWithTheSameKey() throws Exception {
        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        String key = newKey();

        CART.down = true;
        place(customerId, key, GROCERY_TOTAL).andExpect(status().isServiceUnavailable());
        CART.down = false;
        place(customerId, key, new BigDecimal("1.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOTAL_CHANGED"));
        assertThat(keyRows(customerId)).isZero();
        assertThat(orderCount(customerId)).isZero();

        // neither failure used the key up: the retry creates the order
        place(customerId, key, GROCERY_TOTAL)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(orderCount(customerId)).isEqualTo(1);
    }

    @Test
    void aTakenOrderNumberIsReplacedByANewOne() throws Exception {
        UUID first = UUID.randomUUID();
        JsonNode existing = order(first, newGroceryOrder(first));
        String taken = existing.get("orderNumber").asString();

        UUID customerId = UUID.randomUUID();
        defaultAddress(customerId);
        groceryCart(customerId);
        doReturn(taken).doCallRealMethod().when(orderNumbers).next();

        JsonNode order = body(place(customerId, newKey(), GROCERY_TOTAL).andExpect(status().isCreated()));

        assertThat(order.get("orderNumber").asString()).isNotEqualTo(taken).matches("GA-[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM customer_order WHERE order_number = ?").param(taken)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(orderEventTypes(id(order))).containsExactly("OrderCreated");
        assertThat(keyRows(customerId)).isEqualTo(1);
    }
}
