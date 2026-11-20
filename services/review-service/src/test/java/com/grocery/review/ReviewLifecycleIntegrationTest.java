package com.grocery.review;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract §1: the author edits (7 days, re-moderated) and deletes (any time); "my reviews". */
class ReviewLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aSubmittedReviewIsTheAuthorsView() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 4, "  Fresh  ",
                "   ");
        assertThat(review.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(review.get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(review.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(review.get("targetName").asString()).isEqualTo("Tomato");
        assertThat(review.get("storeId").asString()).isEqualTo(order.storeId().toString());
        assertThat(review.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(review.get("authorName").asString()).isEqualTo("Asha S.");
        assertThat(review.get("rating").asInt()).isEqualTo(4);
        assertThat(review.get("title").asString()).isEqualTo("Fresh");
        assertThat(review.get("comment").isNull()).isTrue();
        assertThat(review.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(review.get("edited").asBoolean()).isFalse();
        assertThat(review.get("reply").isNull()).isTrue();
        Instant created = Instant.parse(review.get("createdAt").asString());
        assertThat(Instant.parse(review.get("editableUntil").asString())).isEqualTo(created.plus(Duration.ofDays(7)));

        JsonNode event = lastEvent(id(review), "ReviewSubmitted");
        assertThat(event.get("reviewId").asString()).isEqualTo(review.get("id").asString());
        assertThat(event.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(event.get("targetId").asString()).isEqualTo(order.item(0).itemId().toString());
        assertThat(event.get("storeId").asString()).isEqualTo(order.storeId().toString());
        assertThat(event.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(event.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(event.get("rating").asInt()).isEqualTo(4);
        assertThat(event.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(event.get("flagReason").isNull()).isTrue();
        assertThat(event.get("edited").asBoolean()).isFalse();
    }

    @Test
    void anEditIsModeratedAgainAndChangesTheSummary() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        UUID product = order.item(0).itemId();
        JsonNode review = reviewFirstItem(order, 5);
        assertThat(lastEvent(product, "RatingSummaryUpdated").get("averageRating").decimalValue())
                .isEqualByComparingTo("5.00");

        clock.advance(Duration.ofDays(3));
        JsonNode edited = body(edit(customer, id(review), 2, "Gone soft", "Soft by the next day")
                .andExpect(status().isOk()));
        assertThat(edited.get("rating").asInt()).isEqualTo(2);
        assertThat(edited.get("edited").asBoolean()).isTrue();
        assertThat(edited.get("status").asString()).isEqualTo("PUBLISHED");
        JsonNode resubmitted = lastEvent(id(review), "ReviewSubmitted");
        assertThat(resubmitted.get("edited").asBoolean()).isTrue();
        assertThat(resubmitted.get("rating").asInt()).isEqualTo(2);
        JsonNode summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("averageRating").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(2);

        // an edit with a link is flagged: hidden, and out of the summary
        JsonNode flagged = body(edit(customer, id(review), 1, "Rotten", "See photos at www.rotten-veg.example")
                .andExpect(status().isOk()));
        assertThat(flagged.get("status").asString()).isEqualTo("FLAGGED");
        assertThat(flagged.get("flagReason").asString()).isEqualTo("LINK");
        assertThat(lastEvent(id(review), "ReviewSubmitted").get("status").asString()).isEqualTo("FLAGGED");
        summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("ratingCount").asInt()).isZero();
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(3);

        // fixed again: published again
        body(edit(customer, id(review), 1, "Rotten", "Soft and rotten").andExpect(status().isOk()));
        summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(4);
        assertThat(events(id(review), "ReviewSubmitted")).hasSize(4);

        // an edit that changes nothing in the published ratings publishes no summary
        edit(customer, id(review), 1, "Rotten tomatoes", "Soft and rotten").andExpect(status().isOk());
        assertThat(lastEvent(product, "RatingSummaryUpdated").get("summaryVersion").asLong()).isEqualTo(4);
    }

    @Test
    void reviewsCanBeEditedForSevenDays() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = reviewFirstItem(order, 5);
        clock.advance(Duration.ofDays(7).minusMinutes(1));
        edit(customer, id(review), 4, null, null).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(2));
        edit(customer, id(review), 3, null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_EDIT_WINDOW_OVER"));
        assertThat(jdbc().sql("SELECT rating FROM review WHERE id = UUID_TO_BIN(?)").param(id(review).toString())
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void onlyTheAuthorEditsOrDeletes() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = reviewFirstItem(order, 5);
        edit(otherCustomer, id(review), 1, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_FOUND"));
        mvc.perform(delete("/api/reviews/{id}", id(review)).with(customer(otherCustomer)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/reviews/{id}", id(review))).andExpect(status().isUnauthorized());
        edit(customer, id(review), 0, null, null).andExpect(status().isBadRequest());
        edit(customer, UUID.randomUUID(), 3, null, null).andExpect(status().isNotFound());
        assertThat(statusOf(id(review))).isEqualTo("PUBLISHED");
    }

    @Test
    void deletingAReviewUpdatesTheSummaryAndFreesTheTarget() throws Exception {
        Order mine = deliveredGroceryOrder(customer, "Tomato");
        UUID product = mine.item(0).itemId();
        JsonNode review = reviewFirstItem(mine, 5);
        Order theirs = groceryOrder(otherCustomer, "Tomato");
        Order theirsSameProduct = new Order(theirs.orderId(), theirs.orderNumber(), "GROCERY", otherCustomer,
                theirs.storeId(), theirs.storeName(), List.of(mine.item(0)), theirs.partnerId());
        confirm(theirsSameProduct);
        deliver(theirsSameProduct);
        reviewFirstItem(theirsSameProduct, 2);
        assertThat(lastEvent(product, "RatingSummaryUpdated").get("averageRating").decimalValue())
                .isEqualByComparingTo("3.50");

        clock.advance(Duration.ofDays(20));          // long after the edit window: deleting is always allowed
        mvc.perform(delete("/api/reviews/{id}", id(review)).with(customer(customer)))
                .andExpect(status().isNoContent());
        JsonNode summary = lastEvent(product, "RatingSummaryUpdated");
        assertThat(summary.get("averageRating").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(summary.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(summary.get("summaryVersion").asLong()).isEqualTo(3);
        mvc.perform(delete("/api/reviews/{id}", id(review)).with(customer(customer)))
                .andExpect(status().isNotFound());

        // a flagged review leaves no trace in the summary when deleted
        Order other = deliveredGroceryOrder(customer, "Onion");
        JsonNode flagged = review(customer, other.orderId(), "PRODUCT", other.item(0).itemId(), 1, null,
                "call 9876543210");
        mvc.perform(delete("/api/reviews/{id}", id(flagged)).with(customer(customer)))
                .andExpect(status().isNoContent());
        assertThat(events(other.item(0).itemId(), "RatingSummaryUpdated")).isEmpty();

        // still in the window: the deleted target can be reviewed again
        review(customer, mine.orderId(), "PRODUCT", product, 4, null, null);
    }

    @Test
    void myReviewsShowEveryStatusNewestFirst() throws Exception {
        UUID me = UUID.randomUUID();
        Order order = deliveredRestaurantOrder(me, "Dosa", "Idli");
        JsonNode first = review(me, order.orderId(), "MENU_ITEM", order.item(0).itemId(), 5, "Crisp", null);
        JsonNode flagged = review(me, order.orderId(), "MENU_ITEM", order.item(1).itemId(), 1, null,
                "WhatsApp 98765-43210");
        JsonNode store = review(me, order.orderId(), "RESTAURANT", order.storeId(), 4, null, null);
        moderate(id(flagged), "reject", "Contact details", "SUPPORT").andExpect(status().isOk());

        JsonNode page = body(mvc.perform(get("/api/reviews/mine").with(customer(me)).param("size", "2"))
                .andExpect(status().isOk()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        List<String> statuses = new ArrayList<>();
        page.get("content").forEach(r -> statuses.add(r.get("status").asString()));
        assertThat(page.get("content").get(0).get("id").asString()).isEqualTo(store.get("id").asString());
        assertThat(statuses).containsExactly("PUBLISHED", "REJECTED");
        JsonNode second = body(mvc.perform(get("/api/reviews/mine").with(customer(me)).param("size", "2")
                .param("page", "1")).andExpect(status().isOk()));
        assertThat(second.get("content").get(0).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(second.get("content").get(0).get("authorName").asString()).isEqualTo("Ravi K.");
    }
}
