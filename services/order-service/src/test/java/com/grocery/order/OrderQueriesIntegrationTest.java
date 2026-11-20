package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read side: the customer's list and detail (contract §2), the admin monitor and the stats (§4), and who may read
 * what. Times are moved with SQL where a test needs orders on a particular day (India time).
 */
class OrderQueriesIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final UUID admin = UUID.randomUUID();

    private JsonNode customerList(UUID customerId, String query) throws Exception {
        return body(mvc.perform(get("/api/orders" + query).with(customer(customerId))).andExpect(status().isOk()));
    }

    private ResultActions adminList(String query) throws Exception {
        return mvc.perform(get("/api/admin/orders" + query).with(as(admin, "SUPPORT")));
    }

    private List<String> ids(JsonNode page) {
        return page.get("content").valueStream().map(o -> o.get("id").asString()).toList();
    }

    /** Moves an order's creation (and confirmation, if any) to {@code createdAt} (UTC). */
    private void createdAt(UUID orderId, Instant createdAt) {
        jdbc().sql("UPDATE customer_order SET created_at = ?, confirmed_at = IF(confirmed_at IS NULL, NULL, ?) "
                        + "WHERE id = UUID_TO_BIN(?)")
                .param(createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(createdAt.plusSeconds(60).atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(orderId.toString()).update();
    }

    private void confirmedAt(UUID orderId, Instant confirmedAt) {
        jdbc().sql("UPDATE customer_order SET confirmed_at = ? WHERE id = UUID_TO_BIN(?)")
                .param(confirmedAt.atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(orderId.toString()).update();
    }

    /** A day no other test uses (the stats count every order of a day). */
    private static LocalDate unusedDay() {
        return LocalDate.of(2031, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 20_000));
    }

    @Test
    void theCustomerListsTheirOwnOrdersNewestFirst() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID created = newGroceryOrder(customerId);
        UUID awaiting = newRestaurantOrder(customerId);
        UUID cancelled = newGroceryOrder(customerId);
        mvc.perform(post("/api/orders/{id}/cancel", cancelled).with(customer(customerId))).andExpect(status().isOk());
        UUID someoneElses = newGroceryOrder(UUID.randomUUID());

        JsonNode all = customerList(customerId, "");
        assertThat(ids(all)).containsExactly(cancelled.toString(), awaiting.toString(), created.toString());
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(ids(all)).doesNotContain(someoneElses.toString());

        JsonNode summary = all.get("content").get(1);
        assertThat(fields(summary)).containsExactly("id", "orderNumber", "type", "status", "store", "itemCount",
                "couponCode", "discount", "deliveryFeeDiscount",                             // + Phase 12
                "total", "currency", "firstItemName", "payment", "createdAt");
        assertThat(summary.get("couponCode").isNull()).isTrue();
        assertThat(summary.get("discount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(summary.get("type").asString()).isEqualTo("RESTAURANT");
        assertThat(summary.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(summary.get("store").get("name").asString()).isEqualTo("Spice Route");
        assertThat(summary.get("itemCount").asInt()).isEqualTo(3);
        assertThat(summary.get("total").decimalValue()).isEqualByComparingTo(RESTAURANT_TOTAL);
        assertThat(summary.get("currency").asString()).isEqualTo("INR");
        assertThat(summary.get("firstItemName").asString()).isEqualTo("Chicken Biryani");
        assertThat(fields(summary.get("payment"))).containsExactly("status", "dueAt");
        assertThat(summary.get("payment").get("status").asString()).isEqualTo("PENDING");
        // The exact end of the payment window (the order view's payment.dueAt), only while the order awaits payment.
        assertThat(summary.get("payment").get("dueAt").asString())
                .isEqualTo(order(customerId, awaiting).get("payment").get("dueAt").asString());
        assertThat(all.get("content").get(0).get("payment").get("dueAt").isNull()).isTrue();          // CANCELLED
        assertThat(all.get("content").get(2).get("payment").get("dueAt").isNull()).isTrue();          // CREATED

        assertThat(ids(customerList(customerId, "?status=OPEN")))
                .containsExactly(awaiting.toString(), created.toString());
        assertThat(ids(customerList(customerId, "?status=CANCELLED"))).containsExactly(cancelled.toString());
        assertThat(ids(customerList(customerId, "?status=CONFIRMED"))).isEmpty();

        JsonNode page = customerList(customerId, "?page=1&size=2");
        assertThat(ids(page)).containsExactly(created.toString());
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("size").asInt()).isEqualTo(2);

        mvc.perform(get("/api/orders?status=SHIPPED").with(customer(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void anotherCustomersOrderIsNotFound() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        mvc.perform(get("/api/orders/{id}", orderId).with(customer(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/orders/{id}", UUID.randomUUID()).with(customer(customerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/orders/{id}", "not-a-uuid").with(customer(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void customerAndAdminEndpointsAreSeparated() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        for (String path : List.of("/api/admin/orders", "/api/admin/orders/" + orderId, "/api/admin/stats")) {
            mvc.perform(get(path).with(customer(customerId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
                mvc.perform(get(path).with(as(admin, role))).andExpect(status().isOk());
            }
            mvc.perform(get(path).with(as(admin, "VENDOR"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/orders").with(as(admin, "OPS_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/{id}", orderId).with(as(admin, "SUPER_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminDetailIsTheOrderViewWithTheCustomer() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newRestaurantOrder(customerId);
        JsonNode customerView = order(customerId, orderId);

        JsonNode adminView = body(mvc.perform(get("/api/admin/orders/{id}", orderId).with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk()));

        assertThat(fields(adminView).subList(0, 2)).containsExactly("customerId", "adminCancellable");
        assertThat(adminView.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(adminView.get("adminCancellable").asBoolean()).isTrue();
        assertThat(fields(adminView).subList(2, fields(adminView).size())).isEqualTo(fields(customerView));
        for (String field : fields(customerView)) {
            assertThat(adminView.get(field)).as(field).isEqualTo(customerView.get(field));
        }
        mvc.perform(get("/api/admin/orders/{id}", UUID.randomUUID()).with(as(admin, "SUPPORT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
    }

    @Test
    void theAdminListFiltersByEveryParameter() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID grocery = newGroceryOrder(customerId);
        UUID restaurant = newRestaurantOrder(customerId);
        UUID confirmed = confirmedOrder(customerId);
        UUID otherCustomer = UUID.randomUUID();
        UUID other = newGroceryOrder(otherCustomer);
        String c = "?customerId=" + customerId;

        JsonNode all = body(adminList(c).andExpect(status().isOk()));
        assertThat(ids(all)).containsExactly(confirmed.toString(), restaurant.toString(), grocery.toString());
        JsonNode row = all.get("content").get(2);
        assertThat(fields(row)).containsExactly("customerId", "adminCancellable", "id", "orderNumber", "type",
                "status", "store", "itemCount", "couponCode", "discount", "deliveryFeeDiscount", "total", "currency",
                "firstItemName", "payment", "createdAt");
        assertThat(row.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(row.get("adminCancellable").asBoolean()).isTrue();
        assertThat(row.get("firstItemName").asString()).isEqualTo("Basmati Rice");
        // The admin rows carry the payment due time too: set while AWAITING_PAYMENT, null once paid.
        assertThat(all.get("content").get(1).get("payment").get("dueAt").asString())
                .isEqualTo(order(customerId, restaurant).get("payment").get("dueAt").asString());
        assertThat(all.get("content").get(0).get("payment").get("dueAt").isNull()).isTrue();          // CONFIRMED

        assertThat(ids(body(adminList(c + "&type=GROCERY")))).containsExactly(grocery.toString());
        assertThat(ids(body(adminList(c + "&status=CONFIRMED")))).containsExactly(confirmed.toString());
        assertThat(ids(body(adminList(c + "&status=OPEN")))).containsExactly(restaurant.toString(), grocery.toString());
        assertThat(ids(body(adminList(c + "&status=AWAITING_PAYMENT&type=RESTAURANT"))))
                .containsExactly(restaurant.toString());

        String storeId = order(otherCustomer, other).get("store").get("id").asString();
        assertThat(ids(body(adminList("?storeId=" + storeId)))).containsExactly(other.toString());

        String number = order(customerId, restaurant).get("orderNumber").asString();
        assertThat(ids(body(adminList("?q=" + number.toLowerCase())))).containsExactly(restaurant.toString());
        assertThat(ids(body(mvc.perform(get("/api/admin/orders").param("q", " " + number + " ")
                .with(as(admin, "OPS_ADMIN")))))).containsExactly(restaurant.toString());
        assertThat(ids(body(adminList("?q=" + number.substring(0, 6))))).isEmpty();      // exact, not a prefix

        adminList("?type=BICYCLE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        adminList("?status=open").andExpect(status().isBadRequest());
        adminList("?customerId=nope").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        adminList("?from=11/11/2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        adminList("?from=2026-11-12&to=2026-11-11").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void theAdminDateFilterUsesIndiaTimeDaysInclusive() throws Exception {
        UUID customerId = UUID.randomUUID();
        LocalDate day = unusedDay();
        Instant midnight = day.atStartOfDay(INDIA).toInstant();              // = previous day 18:30 UTC
        UUID beforeDay = newGroceryOrder(customerId);
        UUID firstMoment = newGroceryOrder(customerId);
        UUID lastMoment = newGroceryOrder(customerId);
        UUID nextDay = newGroceryOrder(customerId);
        UUID dayAfter = newGroceryOrder(customerId);
        createdAt(beforeDay, midnight.minusMillis(1));
        createdAt(firstMoment, midnight);
        createdAt(lastMoment, midnight.plusSeconds(86_400).minusNanos(1_000));
        createdAt(nextDay, midnight.plusSeconds(86_400));
        createdAt(dayAfter, midnight.plusSeconds(2 * 86_400));
        String c = "?customerId=" + customerId;

        assertThat(ids(body(adminList(c + "&from=" + day + "&to=" + day))))
                .containsExactly(lastMoment.toString(), firstMoment.toString());
        assertThat(ids(body(adminList(c + "&from=" + day + "&to=" + day.plusDays(1)))))
                .containsExactly(nextDay.toString(), lastMoment.toString(), firstMoment.toString());
        assertThat(ids(body(adminList(c + "&from=" + day.plusDays(1)))))
                .containsExactly(dayAfter.toString(), nextDay.toString());
        assertThat(ids(body(adminList(c + "&to=" + day.minusDays(1))))).containsExactly(beforeDay.toString());
    }

    @Test
    void statsCountOneIndiaTimeDay() throws Exception {
        LocalDate day = unusedDay();
        Instant midnight = day.atStartOfDay(INDIA).toInstant();
        UUID customerId = UUID.randomUUID();

        UUID created = newGroceryOrder(customerId);                                  // CREATED, grocery
        UUID awaiting = newRestaurantOrder(customerId);                              // AWAITING_PAYMENT, restaurant
        UUID paidA = confirmedOrder(customerId);                                     // CONFIRMED, 359.00
        UUID paidB = newGroceryOrder(customerId);                                    // CONFIRMED, 245.00
        inventoryListener.on(stockReserved(newEventId(), paidB));
        paymentListener.on(paymentCaptured(newEventId(), paidB, false));
        UUID rejected = newGroceryOrder(customerId);
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, Map.of(UUID.randomUUID(), new int[]{1, 0})));
        UUID cancelled = newRestaurantOrder(customerId);
        paymentListener.on(paymentExpired(newEventId(), cancelled));
        UUID previousDay = confirmedOrder(customerId);                               // created the day before …
        UUID paidLater = newRestaurantOrder(customerId);                             // … created that day, paid next day
        paymentListener.on(paymentCaptured(newEventId(), paidLater, false));

        for (UUID orderId : List.of(created, awaiting, paidA, paidB, rejected, cancelled, paidLater)) {
            createdAt(orderId, midnight.plusSeconds(3_600));
        }
        createdAt(previousDay, midnight.minusSeconds(3_600));
        confirmedAt(previousDay, midnight.plusSeconds(7_200));                       // … but paid that day
        confirmedAt(paidLater, midnight.plusSeconds(86_400 + 60));

        String raw = mvc.perform(get("/api/admin/stats").param("date", day.toString())
                .with(as(admin, "FINANCE_ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode stats = json.readTree(raw);

        assertThat(fields(stats)).containsExactly("date", "timezone", "orders", "byStatus", "byType",
                "confirmedRevenue", "averageOrderValue", "currency");
        assertThat(stats.get("date").asString()).isEqualTo(day.toString());
        assertThat(stats.get("timezone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(stats.get("orders").asLong()).isEqualTo(7);
        assertThat(fields(stats.get("byStatus"))).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED",
                "ACCEPTED", "PREPARING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED",
                "REJECTED", "CANCELLED");
        assertThat(stats.get("byStatus").get("CREATED").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("AWAITING_PAYMENT").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("CONFIRMED").asLong()).isEqualTo(3);
        assertThat(stats.get("byStatus").get("REJECTED").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("CANCELLED").asLong()).isEqualTo(1);
        assertThat(stats.get("byType").get("GROCERY").asLong()).isEqualTo(3);
        assertThat(stats.get("byType").get("RESTAURANT").asLong()).isEqualTo(4);
        // confirmed that day: paidA (359.00), paidB (245.00), previousDay (359.00); not paidLater
        assertThat(stats.get("confirmedRevenue").decimalValue()).isEqualByComparingTo("963.00");
        assertThat(stats.get("averageOrderValue").decimalValue()).isEqualByComparingTo("321.00");
        assertThat(raw).contains("\"confirmedRevenue\":963.00", "\"averageOrderValue\":321.00");
        assertThat(stats.get("currency").asString()).isEqualTo("INR");

        // an empty day: zeros everywhere, average 0
        JsonNode empty = body(mvc.perform(get("/api/admin/stats").param("date", unusedDay().plusDays(30_000).toString())
                .with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(empty.get("orders").asLong()).isZero();
        assertThat(empty.get("byStatus").get("CONFIRMED").asLong()).isZero();
        assertThat(empty.get("byType").get("RESTAURANT").asLong()).isZero();
        assertThat(empty.get("confirmedRevenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(empty.get("averageOrderValue").decimalValue()).isEqualByComparingTo("0.00");
    }

    /** Phase 8: the store statuses in the stats, the list filters and the views. */
    @Test
    void theStoreStatusesAreCountedListedAndShown() throws Exception {
        LocalDate day = unusedDay();
        Instant midnight = day.atStartOfDay(INDIA).toInstant();
        UUID customerId = UUID.randomUUID();
        UUID accepted = acceptedOrder(customerId);
        UUID preparing = preparingOrder(customerId);
        UUID readyA = readyOrder(customerId);
        UUID readyB = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(readyB);
        Instant acceptedAt = Instant.parse("2026-11-11T09:00:00Z");
        vendorListener.on(storeAccepted(VENDOR, newEventId(), readyB, vendorId, 15, acceptedAt));
        vendorListener.on(storeReady(VENDOR, newEventId(), readyB, vendorId, acceptedAt.plusSeconds(600)));
        UUID storeRejected = confirmedOrder(customerId);
        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), storeRejected, storeIdOf(storeRejected),
                "NO_RESPONSE", null, true, null));
        for (UUID orderId : List.of(accepted, preparing, readyA, readyB, storeRejected)) {
            createdAt(orderId, midnight.plusSeconds(3_600));
        }

        JsonNode stats = body(mvc.perform(get("/api/admin/stats").param("date", day.toString())
                .with(as(admin, "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(stats.get("orders").asLong()).isEqualTo(5);
        assertThat(stats.get("byStatus").get("CONFIRMED").asLong()).isZero();
        assertThat(stats.get("byStatus").get("ACCEPTED").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("PREPARING").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("READY_FOR_PICKUP").asLong()).isEqualTo(2);
        assertThat(stats.get("byStatus").get("CANCELLED").asLong()).isEqualTo(1);

        String c = "?customerId=" + customerId;
        assertThat(ids(body(adminList(c + "&status=READY_FOR_PICKUP"))))
                .containsExactly(readyB.toString(), readyA.toString());
        assertThat(ids(body(adminList(c + "&status=PREPARING")))).containsExactly(preparing.toString());
        assertThat(ids(customerList(customerId, "?status=ACCEPTED"))).containsExactly(accepted.toString());
        JsonNode rows = body(adminList(c + "&status=READY_FOR_PICKUP"));
        assertThat(rows.get("content").get(0).get("adminCancellable").asBoolean()).isTrue();
        assertThat(body(adminList(c + "&status=CANCELLED")).get("content").get(0).get("adminCancellable").asBoolean())
                .isFalse();

        JsonNode grocery = order(customerId, readyB);
        assertThat(grocery.get("status").asString()).isEqualTo("READY_FOR_PICKUP");
        assertThat(Instant.parse(grocery.get("acceptedAt").asString())).isEqualTo(acceptedAt);
        assertThat(grocery.get("readyInMinutes").asInt()).isEqualTo(15);
        assertThat(Instant.parse(grocery.get("readyBy").asString())).isEqualTo(acceptedAt.plusSeconds(900));
        assertThat(grocery.get("cancellable").asBoolean()).isFalse();
        assertThat(grocery.get("timeline").valueStream().map(e -> e.get("status").asString()))
                .containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED", "READY_FOR_PICKUP");
        List<String> viewFields = fields(grocery);
        assertThat(viewFields.subList(viewFields.indexOf("payment"), viewFields.indexOf("cancellation")))
                .containsExactly("payment", "acceptedAt", "readyInMinutes", "readyBy", "cancellable");

        JsonNode noResponse = order(customerId, storeRejected);
        assertThat(noResponse.get("cancellation").get("reason").asString()).isEqualTo("STORE_NO_RESPONSE");
        assertThat(noResponse.get("cancellation").get("by").asString()).isEqualTo("SYSTEM");
        assertThat(noResponse.get("acceptedAt").isNull()).isTrue();
        assertThat(noResponse.get("readyInMinutes").isNull()).isTrue();
        assertThat(noResponse.get("readyBy").isNull()).isTrue();
    }

    @Test
    void statsDefaultToTodayInIndia() throws Exception {
        UUID customerId = UUID.randomUUID();
        newRestaurantOrder(customerId);

        JsonNode stats = body(mvc.perform(get("/api/admin/stats").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk()));

        assertThat(stats.get("date").asString()).isIn(LocalDate.now(INDIA).toString(),
                LocalDate.now(INDIA).minusDays(1).toString());               // a test running over midnight
        assertThat(stats.get("orders").asLong()).isPositive();
        mvc.perform(get("/api/admin/stats").param("date", "tomorrow").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }
}
