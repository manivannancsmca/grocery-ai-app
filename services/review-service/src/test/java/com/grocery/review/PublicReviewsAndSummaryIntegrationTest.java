package com.grocery.review;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract §1: the public listing (published only, "Asha S."), the rating summary and RatingSummaryUpdated. */
class PublicReviewsAndSummaryIntegrationTest extends AbstractIntegrationTest {

    /** A delivered order of a new customer containing {@code product}; returns that customer's id. */
    private UUID buyerOf(Item product, Order like) {
        UUID buyer = UUID.randomUUID();
        UUID orderId = Ids.newId();
        Order order = new Order(orderId, orderNumber(orderId), "GROCERY", buyer, like.storeId(), like.storeName(),
                List.of(product), UUID.randomUUID());
        confirm(order);
        deliver(order);
        buyerOrders.put(buyer, order);
        return buyer;
    }

    /** Each buyer's one delivered order. */
    private final Map<UUID, Order> buyerOrders = new ConcurrentHashMap<>();

    private JsonNode rate(UUID buyer, Item product, int rating, String title, String comment) throws Exception {
        Order order = buyerOrders.get(buyer);
        return review(buyer, order.orderId(), "PRODUCT", product.itemId(), rating, title, comment);
    }

    private JsonNode publicList(UUID productId, String... params) throws Exception {
        var request = get("/api/reviews").param("targetType", "PRODUCT").param("targetId", productId.toString());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private JsonNode summary(String targetType, UUID targetId) throws Exception {
        return body(mvc.perform(get("/api/reviews/summary").param("targetType", targetType)
                .param("targetId", targetId.toString())).andExpect(status().isOk()));
    }

    private static List<Integer> ratings(JsonNode page) {
        List<Integer> ratings = new ArrayList<>();
        page.get("content").forEach(r -> ratings.add(r.get("rating").asInt()));
        return ratings;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asInt()));
        return values;
    }

    @Test
    void thePublicListingShowsPublishedReviewsWithFirstNameAndInitial() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        Item tomato = order.item(0);
        JsonNode asha = review(customer, order.orderId(), "PRODUCT", tomato.itemId(), 4, "Fresh", "Good quality");
        UUID ravi = buyerOf(tomato, order);
        rate(ravi, tomato, 2, null, "Mail ravi@example.com");          // flagged: hidden
        UUID meena = buyerOf(tomato, order);
        JsonNode removed = rate(meena, tomato, 1, "Bad", null);
        moderate(id(removed), "remove", "Fake", "SUPPORT").andExpect(status().isOk());
        UUID john = buyerOf(tomato, order);
        rate(john, tomato, 5, "Great", null);

        JsonNode page = publicList(tomato.itemId());              // no token needed
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(ratings(page)).containsExactly(5, 4);            // newest first
        JsonNode ashaPublic = page.get("content").get(1);
        assertThat(ashaPublic.get("id").asString()).isEqualTo(asha.get("id").asString());
        assertThat(ashaPublic.get("authorName").asString()).isEqualTo("Asha S.");
        assertThat(ashaPublic.get("title").asString()).isEqualTo("Fresh");
        assertThat(ashaPublic.get("comment").asString()).isEqualTo("Good quality");
        assertThat(ashaPublic.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(ashaPublic.get("edited").asBoolean()).isFalse();
        assertThat(ashaPublic.get("reply").isNull()).isTrue();
        // nothing private in the public view
        assertThat(ashaPublic.has("customerId")).isFalse();
        assertThat(ashaPublic.has("orderId")).isFalse();
        assertThat(ashaPublic.has("status")).isFalse();
        assertThat(page.get("content").get(0).get("authorName").asString()).isEqualTo("Ravi K.");

        assertThat(ratings(publicList(tomato.itemId(), "sort", "highest"))).containsExactly(5, 4);
        assertThat(ratings(publicList(tomato.itemId(), "sort", "LOWEST"))).containsExactly(4, 5);
        assertThat(ratings(publicList(tomato.itemId(), "size", "1", "page", "1"))).containsExactly(4);

        mvc.perform(get("/api/reviews").param("targetType", "PRODUCT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/reviews").param("targetType", "PRODUCT").param("targetId", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/reviews").param("targetType", "PRODUCT").param("targetId", tomato.itemId().toString())
                        .param("sort", "best"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews/summary").param("targetType", "SHOP")
                        .param("targetId", tomato.itemId().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSummaryHasTheAverageCountAndDistributionWithAnIncreasingVersion() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        Item tomato = order.item(0);
        UUID product = tomato.itemId();
        JsonNode empty = summary("PRODUCT", product);
        assertThat(empty.get("ratingCount").asInt()).isZero();
        assertThat(empty.get("averageRating").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(ints(empty.get("distribution"))).containsExactly(0, 0, 0, 0, 0);

        review(customer, order.orderId(), "PRODUCT", product, 5, null, null);
        for (int rating : new int[]{4, 4, 2}) {
            rate(buyerOf(tomato, order), tomato, rating, null, null);
        }
        JsonNode s = summary("PRODUCT", product);
        assertThat(s.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(s.get("targetId").asString()).isEqualTo(product.toString());
        assertThat(s.get("ratingCount").asInt()).isEqualTo(4);
        assertThat(s.get("averageRating").decimalValue()).isEqualByComparingTo("3.75");
        assertThat(ints(s.get("distribution"))).containsExactly(0, 1, 0, 2, 1);

        rate(buyerOf(tomato, order), tomato, 5, null, null);
        rate(buyerOf(tomato, order), tomato, 1, null, null);
        s = summary("PRODUCT", product);
        assertThat(s.get("averageRating").decimalValue()).isEqualByComparingTo("3.50");    // 21 / 6
        // always 2 decimals in the JSON (the tree reader would parse 3.50 as a double)
        assertThat(mvc.perform(get("/api/reviews/summary").param("targetType", "PRODUCT")
                        .param("targetId", product.toString())).andReturn().getResponse().getContentAsString())
                .contains("\"averageRating\":3.50");

        List<JsonNode> updates = events(product, "RatingSummaryUpdated");
        assertThat(updates).hasSize(6);
        List<Long> versions = updates.stream().map(u -> u.get("summaryVersion").asLong()).toList();
        assertThat(versions).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        JsonNode last = updates.getLast();
        assertThat(last.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(last.get("targetId").asString()).isEqualTo(product.toString());
        assertThat(last.get("ratingCount").asInt()).isEqualTo(6);
        assertThat(last.get("averageRating").decimalValue()).isEqualByComparingTo("3.50");
        assertThat(ints(last.get("ratingDistribution"))).containsExactly(1, 1, 0, 2, 2);
        assertThat(last.get("updatedAt").isString()).isTrue();

        // every target type has its own summary
        Order store = deliveredGroceryOrder(customer, "Milk");
        review(customer, store.orderId(), "VENDOR", store.storeId(), 5, null, null);
        JsonNode vendorSummary = summary("VENDOR", store.storeId());
        assertThat(vendorSummary.get("averageRating").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(events(store.storeId(), "RatingSummaryUpdated").getLast().get("targetType").asString())
                .isEqualTo("VENDOR");
    }

    @Test
    void averagesAreRoundedHalfUpToTwoDecimals() throws Exception {
        Order order = deliveredRestaurantOrder(customer, "Dosa");
        UUID restaurant = order.storeId();
        review(customer, order.orderId(), "RESTAURANT", restaurant, 5, null, null);
        for (int rating : new int[]{5, 4}) {
            UUID buyer = UUID.randomUUID();
            UUID orderId = Ids.newId();
            Order theirs = new Order(orderId, orderNumber(orderId), "RESTAURANT", buyer, restaurant, "Spice Route",
                    order.items(), UUID.randomUUID());
            confirm(theirs);
            deliver(theirs);
            review(buyer, orderId, "RESTAURANT", restaurant, rating, null, null);
        }
        // 14 / 3 = 4.666… → 4.67
        assertThat(summary("RESTAURANT", restaurant).get("averageRating").decimalValue()).isEqualByComparingTo("4.67");
        assertThat(lastEvent(restaurant, "RatingSummaryUpdated").get("averageRating").decimalValue())
                .isEqualByComparingTo("4.67");
    }

    @Test
    void concurrentReviewsOfOneTargetGetDistinctIncreasingVersions() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        Item tomato = order.item(0);
        int writers = 8;
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            buyers.add(buyerOf(tomato, order));
        }
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            UUID buyer = buyers.get(i);
            int rating = 1 + i % 5;
            tasks.add(() -> {
                Order theirs = buyerOrders.get(buyer);
                statuses.add(submit(buyer, theirs.orderId(), "PRODUCT", tomato.itemId(), rating, null, null)
                        .andReturn().getResponse().getStatus());
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (Future<Void> done : pool.invokeAll(tasks)) {
                done.get(1, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).hasSize(writers).containsOnly(201);
        List<JsonNode> updates = events(tomato.itemId(), "RatingSummaryUpdated");
        assertThat(updates.stream().map(u -> u.get("summaryVersion").asLong()).toList())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        JsonNode latest = updates.stream().max((a, b) -> Long.compare(a.get("summaryVersion").asLong(),
                b.get("summaryVersion").asLong())).orElseThrow();
        assertThat(latest.get("ratingCount").asInt()).isEqualTo(writers);
        assertThat(ints(latest.get("ratingDistribution"))).containsExactly(2, 2, 2, 1, 1);
        // each version's count is its position: every recomputation saw all the earlier ones
        updates.forEach(u -> assertThat(u.get("ratingCount").asLong()).isEqualTo(u.get("summaryVersion").asLong()));
    }

    @Test
    void anOperatorCanRepublishEverySummaryWithItsCurrentVersion() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        UUID product = order.item(0).itemId();
        JsonNode review = reviewFirstItem(order, 5);
        edit(customer, id(review), 3, null, null);
        assertThat(events(product, "RatingSummaryUpdated")).hasSize(2);

        JsonNode result = body(mvc.perform(post("/api/admin/reviews/summaries/republish")
                .with(admin(UUID.randomUUID(), "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(result.get("republished").asInt()).isPositive();
        List<JsonNode> updates = events(product, "RatingSummaryUpdated");
        assertThat(updates).hasSize(3);
        assertThat(updates.getLast().get("summaryVersion").asLong()).isEqualTo(2);
        assertThat(updates.getLast().get("averageRating").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(summary("PRODUCT", product).get("averageRating").decimalValue()).isEqualByComparingTo("3.00");

        mvc.perform(post("/api/admin/reviews/summaries/republish").with(admin(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isForbidden());
    }

    /**
     * Phase 13: review-service has its own security chain (ReviewSecurityConfig) — Prometheus scrapes
     * {@code /actuator/prometheus} without a token; the other actuator endpoints stay closed.
     */
    @Test
    void prometheusScrapesWithoutATokenAndTheOtherActuatorEndpointsStayClosed() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("service=\"review-service\"")));
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
