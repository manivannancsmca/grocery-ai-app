package com.grocery.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract §1: a store sees its published reviews and replies to them — its own store's only. */
class StoreReviewsIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorUser = UUID.randomUUID();

    private JsonNode storeReviews(JwtRequestPostProcessor caller, String... params) throws Exception {
        var request = get("/api/store/reviews").with(caller);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private ResultActions reply(JwtRequestPostProcessor caller, UUID reviewId, String text) throws Exception {
        return mvc.perform(put("/api/store/reviews/{id}/reply", reviewId).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", text))));
    }

    private static List<String> targetTypes(JsonNode page) {
        List<String> types = new ArrayList<>();
        page.get("content").forEach(r -> types.add(r.get("targetType").asString() + ":" + r.get("rating").asInt()));
        return types;
    }

    @Test
    void aVendorSeesThePublishedReviewsOfItsStoreAndProducts() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato", "Onion");
        JwtRequestPostProcessor owner = vendor(vendorUser, order.storeId());
        review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, "Fresh", null);
        review(customer, order.orderId(), "PRODUCT", order.item(1).itemId(), 1, null, "visit onion-mart.com");
        review(customer, order.orderId(), "VENDOR", order.storeId(), 4, null, null);
        review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 2, null, null);
        deliveredGroceryOrder(otherCustomer, "Milk");     // another store's order

        JsonNode page = storeReviews(owner);
        assertThat(targetTypes(page)).containsExactly("VENDOR:4", "PRODUCT:5");   // newest first, published only
        JsonNode productReview = page.get("content").get(1);
        assertThat(productReview.get("authorName").asString()).isEqualTo("Asha S.");
        assertThat(productReview.get("targetName").asString()).isEqualTo("Tomato");
        assertThat(productReview.get("title").asString()).isEqualTo("Fresh");
        assertThat(productReview.get("storeId").asString()).isEqualTo(order.storeId().toString());
        assertThat(productReview.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(productReview.has("flagReason")).isTrue();
        assertThat(productReview.get("reply").isNull()).isTrue();
        assertThat(targetTypes(storeReviews(owner, "targetType", "PRODUCT"))).containsExactly("PRODUCT:5");
        assertThat(targetTypes(storeReviews(owner, "rating", "4"))).containsExactly("VENDOR:4");
        assertThat(storeReviews(owner, "targetType", "DELIVERY_PARTNER").get("content")).isEmpty();
        assertThat(storeReviews(owner, "targetType", "RESTAURANT").get("content")).isEmpty();
        assertThat(storeReviews(vendor(UUID.randomUUID(), UUID.randomUUID())).get("content")).isEmpty();

        mvc.perform(get("/api/store/reviews").param("rating", "6").with(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/store/reviews").param("targetType", "SHOP").with(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/store/reviews").with(customer(customer))).andExpect(status().isForbidden());
        // a vendor whose account is not approved has no vendor_id claim
        mvc.perform(get("/api/store/reviews").with(vendor(vendorUser, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
    }

    @Test
    void theStoreRepliesOnceAndMayEditItsReply() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JwtRequestPostProcessor owner = vendor(vendorUser, order.storeId());
        JsonNode review = reviewFirstItem(order, 3);

        JsonNode replied = body(reply(owner, id(review), "  Sorry, we will do better.  ").andExpect(status().isOk()));
        JsonNode reply = replied.get("reply");
        assertThat(reply.get("text").asString()).isEqualTo("Sorry, we will do better.");
        assertThat(reply.get("repliedAt").asString()).isEqualTo(reply.get("updatedAt").asString());
        JsonNode event = lastEvent(id(review), "ReviewReplied");
        assertThat(event.get("repliedBy").asString()).isEqualTo(vendorUser.toString());
        assertThat(event.get("storeId").asString()).isEqualTo(order.storeId().toString());
        assertThat(event.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(event.get("targetType").asString()).isEqualTo("PRODUCT");

        clock.advance(Duration.ofHours(1));
        JsonNode edited = body(reply(owner, id(review), "Refund sent.").andExpect(status().isOk()));
        assertThat(edited.get("reply").get("text").asString()).isEqualTo("Refund sent.");
        assertThat(edited.get("reply").get("repliedAt").asString()).isEqualTo(reply.get("repliedAt").asString());
        assertThat(edited.get("reply").get("updatedAt").asString()).isNotEqualTo(reply.get("updatedAt").asString());
        assertThat(events(id(review), "ReviewReplied")).hasSize(2);
        // the reply is public
        JsonNode published = body(mvc.perform(get("/api/reviews").param("targetType", "PRODUCT")
                .param("targetId", order.item(0).itemId().toString())).andExpect(status().isOk()));
        assertThat(published.get("content").get(0).get("reply").get("text").asString()).isEqualTo("Refund sent.");

        reply(owner, id(review), " ").andExpect(status().isBadRequest());
        reply(owner, id(review), "x".repeat(501)).andExpect(status().isBadRequest());
    }

    @Test
    void aStoreRepliesOnlyToItsOwnPublishedReviews() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode product = reviewFirstItem(order, 4);
        JsonNode partner = review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 5, null, null);
        Order other = deliveredGroceryOrder(customer, "Onion");
        JsonNode flagged = review(customer, other.orderId(), "PRODUCT", other.item(0).itemId(), 1, null,
                "my email is asha@example.com");

        reply(vendor(UUID.randomUUID(), other.storeId()), id(product), "Not mine")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_FOUND"));
        reply(vendor(vendorUser, order.storeId()), id(partner), "About the rider")
                .andExpect(status().isNotFound());
        reply(vendor(UUID.randomUUID(), other.storeId()), id(flagged), "Hidden")
                .andExpect(status().isNotFound());
        reply(restaurantOwner(UUID.randomUUID(), order.storeId()), id(product), "Wrong kind of store")
                .andExpect(status().isNotFound());
        reply(vendor(vendorUser, order.storeId()), UUID.randomUUID(), "Unknown").andExpect(status().isNotFound());
        reply(customer(customer), id(product), "Me").andExpect(status().isForbidden());
        assertThat(events(id(product), "ReviewReplied")).isEmpty();
    }

    @Test
    void aRestaurantRepliesToReviewsOfItsDishes() throws Exception {
        Order order = deliveredRestaurantOrder(customer, "Masala Dosa");
        UUID ownerUser = UUID.randomUUID();
        JwtRequestPostProcessor owner = restaurantOwner(ownerUser, order.storeId());
        JsonNode dish = review(customer, order.orderId(), "MENU_ITEM", order.item(0).itemId(), 5, "Crisp", null);
        review(customer, order.orderId(), "RESTAURANT", order.storeId(), 4, null, null);

        assertThat(targetTypes(storeReviews(owner))).containsExactly("RESTAURANT:4", "MENU_ITEM:5");
        assertThat(targetTypes(storeReviews(owner, "targetType", "MENU_ITEM"))).containsExactly("MENU_ITEM:5");
        assertThat(storeReviews(owner, "targetType", "PRODUCT").get("content")).isEmpty();
        reply(owner, id(dish), "Thank you!").andExpect(status().isOk())
                .andExpect(jsonPath("$.reply.text").value("Thank you!"));
        assertThat(lastEvent(id(dish), "ReviewReplied").get("repliedBy").asString()).isEqualTo(ownerUser.toString());
    }
}
