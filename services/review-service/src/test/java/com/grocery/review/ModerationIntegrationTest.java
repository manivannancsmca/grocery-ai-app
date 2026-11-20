package com.grocery.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract §1: automatic moderation on submission, and the admin decisions with their 409 rules. */
class ModerationIntegrationTest extends AbstractIntegrationTest {

    private JsonNode adminList(String... params) throws Exception {
        var request = get("/api/admin/reviews").with(admin(UUID.randomUUID(), "SUPPORT"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(r -> ids.add(r.get("id").asString()));
        return ids;
    }

    @Test
    void automaticModerationFlagsLinksContactsProfanityAndShouting() throws Exception {
        String[][] cases = {
                {"Write to me", "Mail asha.s@example.com for a better deal", "EMAIL"},
                {"Cheaper here", "Visit https://deals.example.com today", "LINK"},
                {"Cheaper here", "see www.cheap-veg.example", "LINK"},
                {"Cheaper here", "order from freshdeals.in instead", "LINK"},
                {"Call me", "My number is +91 98765 43210", "PHONE"},
                {"Call me", "9876543210", "PHONE"},
                {"Awful", "This was total bullshit", "PROFANITY"},
                {null, "Kya CHUTIYA service hai", "PROFANITY"},
                {"WORST TOMATOES EVER", "NEVER BUYING AGAIN", "UPPER_CASE"},
        };
        for (String[] c : cases) {
            Order order = deliveredGroceryOrder(customer, "Tomato");
            JsonNode review = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 1, c[0], c[1]);
            assertThat(review.get("status").asString()).as(c[1]).isEqualTo("FLAGGED");
            assertThat(review.get("flagReason").asString()).as(c[1]).isEqualTo(c[2]);
            JsonNode event = lastEvent(id(review), "ReviewSubmitted");
            assertThat(event.get("status").asString()).isEqualTo("FLAGGED");
            assertThat(event.get("flagReason").asString()).isEqualTo(c[2]);
            assertThat(event.get("edited").asBoolean()).isFalse();
            // a flagged review changes no summary
            assertThat(events(order.item(0).itemId(), "RatingSummaryUpdated")).isEmpty();
        }

        String[][] clean = {
                {"Fresh", "Delivered on 12/11/2026, 2 kg for Rs. 120. Will order again!"},
                {"OK", null},
                {"GOOD", "Nice"},                                 // too short to be shouting
                {null, "Order GA-1A2B3C arrived at 7.30 pm, e.g. before dinner"},
                {"Scunthorpe", "Classic taste"},                  // whole words only
        };
        for (String[] c : clean) {
            Order order = deliveredGroceryOrder(customer, "Tomato");
            JsonNode review = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, c[0], c[1]);
            assertThat(review.get("status").asString()).as(String.valueOf(c[1])).isEqualTo("PUBLISHED");
            assertThat(review.get("flagReason").isNull()).isTrue();
            assertThat(lastEvent(id(review), "ReviewSubmitted").get("status").asString()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void theAdminListDefaultsToFlaggedAndFilters() throws Exception {
        Order order = deliveredRestaurantOrder(customer, "Paneer Tikka");
        String marker = "zq" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode flaggedDish = review(customer, order.orderId(), "MENU_ITEM", order.item(0).itemId(), 1, marker,
                "call 9876543210");
        JsonNode flaggedStore = review(customer, order.orderId(), "RESTAURANT", order.storeId(), 1, marker,
                "see bad-food.com");
        JsonNode published = review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 5, marker,
                "Polite");

        assertThat(ids(adminList("q", marker))).containsExactly(flaggedStore.get("id").asString(),
                flaggedDish.get("id").asString());
        assertThat(ids(adminList("q", marker.toUpperCase(), "targetType", "MENU_ITEM")))
                .containsExactly(flaggedDish.get("id").asString());
        assertThat(ids(adminList("q", marker, "status", "published")))
                .containsExactly(published.get("id").asString());
        assertThat(ids(adminList("q", order.orderNumber(), "status", "PUBLISHED")))
                .containsExactly(published.get("id").asString());
        JsonNode first = adminList("q", marker).get("content").get(0);
        assertThat(first.get("authorName").asString()).isEqualTo("Asha S.");
        assertThat(first.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(first.get("flagReason").asString()).isEqualTo("LINK");

        mvc.perform(get("/api/admin/reviews").param("status", "DELETED").with(admin(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        // any admin may read; customers may not
        mvc.perform(get("/api/admin/reviews").with(admin(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/reviews").with(customer(customer))).andExpect(status().isForbidden());
    }

    @Test
    void aFlaggedReviewIsPublishedOrRejectedByAnAdmin() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato", "Onion");
        JsonNode first = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 4, "Good",
                "ask me on 98765 43210");
        JsonNode second = review(customer, order.orderId(), "PRODUCT", order.item(1).itemId(), 1, "Bad",
                "worst shit ever");
        UUID product = order.item(0).itemId();

        JsonNode published = body(moderate(id(first), "publish", null, "SUPPORT").andExpect(status().isOk()));
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("moderatedAt").isString()).isTrue();
        JsonNode decided = lastEvent(id(first), "ReviewModerated");
        assertThat(decided.get("decision").asString()).isEqualTo("PUBLISHED");
        assertThat(decided.get("reason").isNull()).isTrue();
        assertThat(decided.get("storeId").asString()).isEqualTo(order.storeId().toString());
        assertThat(decided.get("customerId").asString()).isEqualTo(customer.toString());
        JsonNode summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(1);

        moderate(id(first), "publish", null, "OPS_ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_MODERATABLE"))
                .andExpect(jsonPath("$.currentStatus").value("PUBLISHED"));
        moderate(id(first), "reject", "Spam", "OPS_ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PUBLISHED"));

        mvc.perform(post("/api/admin/reviews/{id}/reject", id(second)).with(admin(UUID.randomUUID(), "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        moderate(id(second), "reject", "x".repeat(301), "SUPER_ADMIN").andExpect(status().isBadRequest());
        JsonNode rejected = body(moderate(id(second), "reject", "Abusive language", "SUPER_ADMIN")
                .andExpect(status().isOk()));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("moderationReason").asString()).isEqualTo("Abusive language");
        JsonNode rejection = lastEvent(id(second), "ReviewModerated");
        assertThat(rejection.get("decision").asString()).isEqualTo("REJECTED");
        assertThat(rejection.get("reason").asString()).isEqualTo("Abusive language");
        assertThat(events(order.item(1).itemId(), "RatingSummaryUpdated")).isEmpty();
        moderate(id(second), "publish", null, "SUPER_ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
    }

    @Test
    void aPublishedReviewIsRemovedByAnAdmin() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = reviewFirstItem(order, 5);
        UUID product = order.item(0).itemId();
        assertThat(lastEvent(product, "RatingSummaryUpdated").get("ratingCount").asInt()).isEqualTo(1);

        moderate(id(review), "remove", " ", "SUPPORT").andExpect(status().isBadRequest());
        JsonNode removed = body(moderate(id(review), "remove", "Fake review", "SUPPORT").andExpect(status().isOk()));
        assertThat(removed.get("status").asString()).isEqualTo("REMOVED");
        assertThat(lastEvent(id(review), "ReviewModerated").get("decision").asString()).isEqualTo("REMOVED");
        JsonNode summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("ratingCount").asInt()).isZero();
        assertThat(summary.get("averageRating").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(2);

        moderate(id(review), "remove", "Again", "SUPPORT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REMOVED"));
        Order flaggedOrder = deliveredGroceryOrder(customer, "Onion");
        JsonNode flagged = review(customer, flaggedOrder.orderId(), "PRODUCT", flaggedOrder.item(0).itemId(), 2,
                null, "see onion-deals.com");
        moderate(id(flagged), "remove", "Spam", "SUPPORT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_MODERATABLE"))
                .andExpect(jsonPath("$.currentStatus").value("FLAGGED"));
    }

    @Test
    void onlyModeratorsDecideAndUnknownReviewsAreNotFound() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = reviewFirstItem(order, 5);
        moderate(id(review), "remove", "Spam", "FINANCE_ADMIN").andExpect(status().isForbidden());
        moderate(id(review), "remove", "Spam", "DELIVERY_MANAGER").andExpect(status().isForbidden());
        moderate(UUID.randomUUID(), "publish", null, "SUPPORT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_FOUND"));
        assertThat(statusOf(id(review))).isEqualTo("PUBLISHED");
    }
}
