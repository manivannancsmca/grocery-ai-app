package com.grocery.review;

import com.grocery.common.kafka.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract §1, what can be reviewed: the projection built from OrderConfirmed and OrderDelivered, the eligible list,
 * and the 422 REVIEW_NOT_ELIGIBLE / 409 ALREADY_REVIEWED rules of {@code POST /api/reviews}.
 */
class EligibilityIntegrationTest extends AbstractIntegrationTest {

    private JsonNode eligible(UUID customerId, UUID orderId) throws Exception {
        var request = get("/api/reviews/eligible").with(customer(customerId));
        if (orderId != null) {
            request = request.param("orderId", orderId.toString());
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> targets(JsonNode eligible) {
        List<String> targets = new ArrayList<>();
        eligible.forEach(t -> targets.add(t.get("targetType").asString() + ":" + t.get("targetName").asString()));
        return targets;
    }

    @Test
    void aDeliveredOrderListsItsItemsTheStoreAndThePartner() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato", "Basmati Rice");

        JsonNode list = eligible(customer, order.orderId());
        assertThat(targets(list)).containsExactly("PRODUCT:Tomato", "PRODUCT:Basmati Rice", "VENDOR:Green Basket",
                "DELIVERY_PARTNER:Delivery partner");
        JsonNode first = list.get(0);
        assertThat(first.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(first.get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(first.get("deliveredAt").isString()).isTrue();
        assertThat(first.get("targetId").asString()).isEqualTo(order.item(0).itemId().toString());
        assertThat(first.get("review").isNull()).isTrue();
        assertThat(list.get(2).get("targetId").asString()).isEqualTo(order.storeId().toString());
        assertThat(list.get(3).get("targetId").asString()).isEqualTo(order.partnerId().toString());

        // a restaurant order: dishes and the restaurant
        Order dinner = deliveredRestaurantOrder(customer, "Paneer Tikka");
        assertThat(targets(eligible(customer, dinner.orderId()))).containsExactly("MENU_ITEM:Paneer Tikka",
                "RESTAURANT:Spice Route", "DELIVERY_PARTNER:Delivery partner");
    }

    @Test
    void withoutOrderIdEveryDeliveredOrderInTheWindowIsListedNewestFirst() throws Exception {
        UUID shopper = UUID.randomUUID();
        Order old = groceryOrder(shopper, "Onion");
        confirm(old);
        deliver(old, clock.instant().minus(Duration.ofDays(31)));        // outside the window
        Order older = groceryOrder(shopper, "Milk");
        confirm(older);
        deliver(older, clock.instant().minus(Duration.ofDays(3)));
        Order newer = groceryOrder(shopper, "Bread");
        confirm(newer);
        deliver(newer, clock.instant().minus(Duration.ofHours(2)));
        Order notDelivered = groceryOrder(shopper, "Eggs");
        confirm(notDelivered);
        deliveredGroceryOrder(otherCustomer, "Butter");                 // someone else's

        JsonNode list = eligible(shopper, null);
        List<String> orders = new ArrayList<>();
        list.forEach(t -> {
            String orderId = t.get("orderId").asString();
            if (!orders.contains(orderId)) {
                orders.add(orderId);
            }
        });
        assertThat(orders).containsExactly(newer.orderId().toString(), older.orderId().toString());
        assertThat(targets(list)).containsExactly("PRODUCT:Bread", "VENDOR:Green Basket",
                "DELIVERY_PARTNER:Delivery partner", "PRODUCT:Milk", "VENDOR:Green Basket",
                "DELIVERY_PARTNER:Delivery partner");

        // one order that is not eligible: someone else's, not delivered, too old → empty
        assertThat(eligible(otherCustomer, newer.orderId())).isEmpty();
        assertThat(eligible(shopper, notDelivered.orderId())).isEmpty();
        assertThat(eligible(shopper, old.orderId())).isEmpty();
        assertThat(eligible(shopper, UUID.randomUUID())).isEmpty();
    }

    @Test
    void theEligibleListShowsTheCustomersReview() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode review = reviewFirstItem(order, 4);

        JsonNode list = eligible(customer, order.orderId());
        assertThat(list.get(0).get("review").get("id").asString()).isEqualTo(review.get("id").asString());
        assertThat(list.get(0).get("review").get("rating").asInt()).isEqualTo(4);
        assertThat(list.get(1).get("review").isNull()).isTrue();
    }

    @Test
    void anOrderThatIsNotDeliveredCannotBeReviewed() throws Exception {
        Order order = groceryOrder(customer, "Tomato");
        confirm(order);

        submit(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("NOT_DELIVERED"));
        // unknown order, and someone else's delivered order
        submit(customer, UUID.randomUUID(), "PRODUCT", order.item(0).itemId(), 5, null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("NOT_DELIVERED"));
        Order theirs = deliveredGroceryOrder(otherCustomer, "Milk");
        submit(customer, theirs.orderId(), "PRODUCT", theirs.item(0).itemId(), 5, null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ELIGIBLE"));

        deliver(order);
        submit(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, null, null)
                .andExpect(status().isCreated());
    }

    @Test
    void onlyWhatTheOrderContainsCanBeReviewed() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        Order other = deliveredGroceryOrder(customer, "Milk");

        for (Object[] wrong : new Object[][]{
                {"PRODUCT", other.item(0).itemId()},           // an item of another order
                {"PRODUCT", UUID.randomUUID()},
                {"MENU_ITEM", order.item(0).itemId()},         // right id, wrong type
                {"RESTAURANT", order.storeId()},               // a grocery order's store is a VENDOR
                {"VENDOR", other.storeId()},
                {"DELIVERY_PARTNER", other.partnerId()}}) {
            submit(customer, order.orderId(), (String) wrong[0], (UUID) wrong[1], 4, null, null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ELIGIBLE"))
                    .andExpect(jsonPath("$.reason").value("NOT_IN_ORDER"));
        }
        JsonNode store = review(customer, order.orderId(), "VENDOR", order.storeId(), 5, null, null);
        assertThat(store.get("targetName").asString()).isEqualTo("Green Basket");
        JsonNode partner = review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 3, null, "Late");
        assertThat(partner.get("storeId").asString()).isEqualTo(order.storeId().toString());
    }

    @Test
    void theReviewWindowEndsThirtyDaysAfterDelivery() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato", "Onion");
        clock.advance(Duration.ofDays(29));
        reviewFirstItem(order, 5);

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        submit(customer, order.orderId(), "PRODUCT", order.item(1).itemId(), 4, null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("WINDOW_OVER"));
        assertThat(eligible(customer, order.orderId())).isEmpty();
    }

    @Test
    void oneReviewPerCustomerOrderAndTarget() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        JsonNode first = reviewFirstItem(order, 5);

        submit(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 2, "Again", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_REVIEWED"))
                .andExpect(jsonPath("$.reviewId").value(first.get("id").asString()));
        // the same product from another order is another review
        Order again = groceryOrder(customer, "Tomato");
        Order sameProduct = new Order(again.orderId(), again.orderNumber(), again.orderType(), customer,
                again.storeId(), again.storeName(), List.of(order.item(0)), again.partnerId());
        confirm(sameProduct);
        deliver(sameProduct);
        review(customer, sameProduct.orderId(), "PRODUCT", order.item(0).itemId(), 3, null, null);
    }

    @Test
    void concurrentDuplicateSubmissionsCreateOneReview() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    statuses.add(submit(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, null, null)
                            .andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM review WHERE order_id = UUID_TO_BIN(?)")
                .param(order.orderId().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deliveredBeforeConfirmedAndDuplicateEventsGiveTheSameProjection() throws Exception {
        Order order = groceryOrder(customer, "Tomato");
        String deliveredId = newEventId();
        orderListener.on(orderEvent("OrderDelivered", deliveredId, order.orderId(),
                orderDeliveredPayload(order, clock.instant())));
        // without OrderConfirmed only the store and the partner are known
        assertThat(targets(eligible(customer, order.orderId()))).containsExactly("VENDOR:Store",
                "DELIVERY_PARTNER:Delivery partner");

        String confirmedId = newEventId();
        orderListener.on(orderEvent("OrderConfirmed", confirmedId, order.orderId(), orderConfirmedPayload(order)));
        orderListener.on(orderEvent("OrderConfirmed", confirmedId, order.orderId(), orderConfirmedPayload(order)));
        // a redelivered OrderDelivered with another time is a duplicate (same eventId) and changes nothing
        orderListener.on(orderEvent("OrderDelivered", deliveredId, order.orderId(),
                orderDeliveredPayload(order, clock.instant().minus(Duration.ofDays(60)))));
        assertThat(targets(eligible(customer, order.orderId()))).containsExactly("PRODUCT:Tomato",
                "VENDOR:Green Basket", "DELIVERY_PARTNER:Delivery partner");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE consumer = 'review-service.order-events' "
                        + "AND event_id IN (?, ?)").param(deliveredId).param(confirmedId)
                .query(Long.class).single()).isEqualTo(2);

        // other order event types are ignored
        orderListener.on(orderEvent("OrderCancelled", newEventId(), order.orderId(), Map.of("orderId",
                order.orderId().toString())));
    }

    @Test
    void brokenOrderEventsAreInvalid() {
        Order order = groceryOrder(customer, "Tomato");
        Map<String, Object> noCustomer = orderConfirmedPayload(order);
        noCustomer.remove("customerId");
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderConfirmed", newEventId(), order.orderId(),
                noCustomer))).isInstanceOf(InvalidEventException.class);
        Map<String, Object> noPartner = orderDeliveredPayload(order, clock.instant());
        noPartner.put("partnerId", null);
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderDelivered", newEventId(), order.orderId(),
                noPartner))).isInstanceOf(InvalidEventException.class);
        Map<String, Object> badId = orderDeliveredPayload(order, clock.instant());
        badId.put("orderId", "not-a-uuid");
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderDelivered", newEventId(), order.orderId(),
                badId))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void submissionsAreValidatedAndNeedACustomer() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato");
        UUID item = order.item(0).itemId();
        submit(customer, order.orderId(), "PRODUCT", item, 0, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        submit(customer, order.orderId(), "PRODUCT", item, 6, null, null).andExpect(status().isBadRequest());
        submit(customer, order.orderId(), "PRODUCT", item, 4, "x".repeat(81), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        submit(customer, order.orderId(), "PRODUCT", item, 4, null, "x".repeat(1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("comment"));
        submit(customer, order.orderId(), "SHOP", item, 4, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetType"));

        String body = json.writeValueAsString(Map.of("orderId", order.orderId().toString(), "targetType", "PRODUCT",
                "targetId", item.toString(), "rating", 5));
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reviews").with(vendor(UUID.randomUUID(), UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reviews/eligible")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews/eligible").param("orderId", "nope").with(customer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }
}
