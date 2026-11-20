package com.grocery.cart;

import com.grocery.cart.store.CartStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two tabs (here: 16 requests at once) changing one cart never lose a change (compare-and-set on the version). */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void parallelAddsToOneCartLoseNoQuantity() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 100);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "150.00", 100);
        int requests = 16;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                UUID productId = i % 2 == 0 ? rice : dal;
                futures.add(pool.submit(() -> {
                    start.await();
                    return addProduct(customerId, productId, 1).andReturn().getResponse();
                }));
            }
            start.countDown();
        }

        int riceAdded = 0;
        int dalAdded = 0;
        for (int i = 0; i < requests; i++) {
            MockHttpServletResponse response = futures.get(i).get();
            if (response.getStatus() == 200) {
                if (i % 2 == 0) {
                    riceAdded++;
                } else {
                    dalAdded++;
                }
            } else {
                // the only acceptable failure: still overtaken after every retry (nothing written)
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(response.getContentAsString()).contains("CONCURRENT_MODIFICATION");
            }
        }
        // how many of 16 simultaneous writers get through 3 retries depends on Redis latency; none may be lost
        assertThat(riceAdded + dalAdded).isPositive();

        JsonNode cart = body(getCart(customerId).andExpect(status().isOk()));
        assertThat(cart.get("lines")).hasSize((riceAdded > 0 ? 1 : 0) + (dalAdded > 0 ? 1 : 0));
        for (JsonNode line : cart.get("lines")) {
            int expected = line.get("itemId").asString().equals(rice.toString()) ? riceAdded : dalAdded;
            assertThat(line.get("quantity").asInt()).isEqualTo(expected);
        }
        // exactly one version per successful write
        assertThat(redis.opsForHash().get(CartStore.key(customerId), "version"))
                .isEqualTo(Integer.toString(riceAdded + dalAdded));
    }

    @Test
    void twoTabsAddingAtTheSameTimeBothSucceed() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 100);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "150.00", 100);

        for (int round = 1; round <= 5; round++) {
            CountDownLatch start = new CountDownLatch(1);
            Future<MockHttpServletResponse> first;
            Future<MockHttpServletResponse> second;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                first = pool.submit(() -> {
                    start.await();
                    return addProduct(customerId, rice, 1).andReturn().getResponse();
                });
                second = pool.submit(() -> {
                    start.await();
                    return addProduct(customerId, dal, 2).andReturn().getResponse();
                });
                start.countDown();
            }
            // the loser of a collision re-reads and wins its retry: nobody else is writing
            assertThat(first.get().getStatus()).isEqualTo(200);
            assertThat(second.get().getStatus()).isEqualTo(200);
        }
        JsonNode cart = body(getCart(customerId));
        assertThat(cart.get("lines").get(0).get("quantity").asInt()
                + cart.get("lines").get(1).get("quantity").asInt()).isEqualTo(15);
        assertThat(cart.get("itemCount").asInt()).isEqualTo(15);
    }
}
