package com.grocery.restaurant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The restaurant-service exports: the admin restaurant list (contact details only for support and operations admins)
 * and an owner's own order board — the list's filters and order, and never another restaurant's orders.
 */
class RestaurantExportIntegrationTest extends StoreOrderTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-10T04:30:00Z");       // 10:00 India time
    private static final String OWNER = "RESTAURANT_OWNER";

    private final UUID admin = UUID.randomUUID();

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private List<String> csv(String path, RequestPostProcessor caller) throws Exception {
        MockHttpServletResponse response = export(path, caller);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=");
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private void badRequest(String path, RequestPostProcessor caller) throws Exception {
        mvc.perform(get(path).with(caller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    // ---- admin: restaurants -----------------------------------------------------------------------------------

    private String saveProfile(UUID owner, String name, String email) throws Exception {
        String body = """
                {"name":"%s","description":"North Indian kitchen","cuisines":["NORTH_INDIAN","BIRYANI"],
                 "fssaiLicense":"12345678901234","gstin":null,"contactEmail":"%s","contactPhone":"+919876543210",
                 "address":{"line1":"5 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                            "country":"IN","latitude":12.9752,"longitude":77.6050},
                 "openingHours":[{"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}""".formatted(name,
                email);
        return body(mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).get("id").asString();
    }

    private void submit(UUID owner) throws Exception {
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
    }

    @Test
    void theAdminRestaurantListFollowsItsFiltersAndOrderAndHidesContactsFromFinance() throws Exception {
        String token = "Exp" + UUID.randomUUID().toString().substring(0, 8);
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        clock.set(T0);
        String restaurantA = saveProfile(ownerA, token + " Kitchen A", "ops-a@example.in");
        clock.set(T0.plus(Duration.ofHours(1)));
        saveProfile(ownerB, token + " Kitchen B", "ops-b@example.in");
        clock.set(T0.plus(Duration.ofHours(2)));
        submit(ownerB);
        clock.set(T0.plus(Duration.ofHours(3)));
        submit(ownerA);

        // most recently changed first: A (submitted last), then B
        List<String> support = csv("/api/admin/restaurants/export?format=csv&query=" + token, as(admin, "SUPPORT"));
        assertThat(support.getFirst()).isEqualTo("Name,Restaurant ID,Status,Cuisines,Accepting orders,FSSAI licence,"
                + "GSTIN,Contact email,Contact phone,Street address,City,State,PIN code,Status reason,Submitted,"
                + "Approved,Registered,Last change");
        assertThat(support).hasSize(3);
        // a phone starting with + gets an apostrophe in CSV, so a spreadsheet never runs it as a formula
        assertThat(support.get(1)).isEqualTo(token + " Kitchen A," + restaurantA + ",Pending approval,"
                + "\"North Indian, Biryani\",Yes,12345678901234,,ops-a@example.in,'+919876543210,5 Church Street,"
                + "Bengaluru,Karnataka,560001,,2026-11-10 13:00,,2026-11-10 10:00,2026-11-10 13:00");
        assertThat(support.get(2)).startsWith(token + " Kitchen B,");

        // the review queue: oldest submission first (B, then A); finance admins get no contact details
        List<String> finance = csv("/api/admin/restaurants/export?format=csv&status=PENDING_APPROVAL&query="
                + token.toUpperCase(Locale.ROOT), as(admin, "FINANCE_ADMIN"));
        assertThat(finance.getFirst()).isEqualTo("Name,Restaurant ID,Status,Cuisines,Accepting orders,FSSAI licence,"
                + "GSTIN,City,State,PIN code,Status reason,Submitted,Approved,Registered,Last change");
        assertThat(finance).hasSize(3);
        assertThat(finance.get(1)).startsWith(token + " Kitchen B,");
        assertThat(finance.get(2)).startsWith(token + " Kitchen A,");
        assertThat(String.join("\n", finance)).doesNotContain("919876543210").doesNotContain("5 Church Street")
                .doesNotContain("@example.in");

        assertThat(csv("/api/admin/restaurants/export?format=csv&status=APPROVED&query=" + token,
                as(admin, "OPS_ADMIN"))).hasSize(1);

        MockHttpServletResponse pdf = export("/api/admin/restaurants/export?format=pdf&query=" + token,
                as(admin, "SUPER_ADMIN"));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        badRequest("/api/admin/restaurants/export?format=docx", as(admin, "SUPPORT"));
        badRequest("/api/admin/restaurants/export", as(admin, "SUPPORT"));
        badRequest("/api/admin/restaurants/export?format=csv&status=NOPE", as(admin, "SUPPORT"));
        mvc.perform(get("/api/admin/restaurants/export?format=csv").with(as(ownerA, OWNER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/restaurants/export?format=csv")).andExpect(status().isUnauthorized());
    }

    // ---- owner: the order board -------------------------------------------------------------------------------

    private void act(Store store, UUID orderId, String action, String body) throws Exception {
        var request = post("/api/restaurant/orders/{id}/" + action, orderId).with(owner(store));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void theOrderBoardExportKeepsTheBoardsOrderAndOnlyTheRestaurantsOwnOrders() throws Exception {
        Store store = newStore();
        Store other = newStore();
        clock.set(T0);
        UUID preparing = confirm(store);
        clock.set(T0.plusSeconds(30));
        act(store, preparing, "accept", "{\"readyInMinutes\":20}");
        clock.set(T0.plusSeconds(60));
        act(store, preparing, "preparing", null);
        clock.set(T0.plus(Duration.ofHours(1)));
        UUID older = confirm(store);
        clock.set(T0.plus(Duration.ofHours(2)));
        UUID newer = confirm(store);
        clock.set(T0.plus(Duration.ofHours(3)));
        UUID pickedUp = confirm(store);
        clock.set(T0.plus(Duration.ofHours(3)).plusSeconds(30));
        act(store, pickedUp, "accept", null);
        clock.set(T0.plus(Duration.ofHours(3)).plusSeconds(60));
        act(store, pickedUp, "ready", null);
        clock.set(Instant.parse("2026-11-10T07:40:00Z"));
        pickUp(newEventId(), store, pickedUp, "2026-11-10T07:40:00Z");
        clock.set(T0.plus(Duration.ofHours(4)));
        UUID cancelled = confirm(store);
        clock.set(Instant.parse("2026-11-10T08:35:00Z"));
        cancel(newEventId(), store, cancelled, "2026-11-10T08:35:00Z");
        UUID othersOrder = confirm(other);

        // NEW first by deadline (the older one is more urgent), then the rest newest first
        List<String> lines = csv("/api/restaurant/orders/export?format=csv", owner(store));
        assertThat(lines.getFirst()).isEqualTo("Order number,Status,Received,Customer,Customer area,Items,Dishes,"
                + "Subtotal (INR),Total (INR),Note,Accept by,Accepted,Ready by,Preparing started,Ready,Picked up,"
                + "Rejection reason,Auto-rejected,Rejection note,Rejected,Cancellation reason,Cancelled by,Cancelled");
        assertThat(lines).hasSize(6);
        assertThat(lines.get(1)).startsWith(orderNumber(older) + ",New,2026-11-10 11:00,Ravi Kumar,"
                + "\"Indiranagar, Bengaluru\",3,2 × Veg Biryani; 1 × Dal Makhani,220.00,245.00,Ring the bell twice,"
                + "2026-11-10 11:05,");
        assertThat(lines.get(2)).startsWith(orderNumber(newer) + ",New,");
        assertThat(lines.get(3)).startsWith(orderNumber(cancelled) + ",Cancelled,2026-11-10 14:00,")
                .endsWith(",Customer cancelled,Customer,2026-11-10 14:05");
        assertThat(lines.get(4)).startsWith(orderNumber(pickedUp) + ",Picked up,2026-11-10 13:00,")
                .contains(",2026-11-10 13:01,2026-11-10 13:10,");
        assertThat(lines.get(5)).startsWith(orderNumber(preparing) + ",Preparing,2026-11-10 10:00,")
                .contains(",2026-11-10 10:05,2026-11-10 10:00,2026-11-10 10:20,2026-11-10 10:01,");
        // the restaurant never gets the customer's phone or street
        assertThat(String.join("\n", lines)).doesNotContain("919876543210").doesNotContain("42 HAL 2nd Stage")
                .doesNotContain(orderNumber(othersOrder));

        List<String> active = csv("/api/restaurant/orders/export?format=csv&status=ACTIVE", owner(store));
        assertThat(active).hasSize(4);
        assertThat(active.get(1)).startsWith(orderNumber(older) + ",");
        assertThat(active.get(2)).startsWith(orderNumber(newer) + ",");
        assertThat(active.get(3)).startsWith(orderNumber(preparing) + ",");
        assertThat(csv("/api/restaurant/orders/export?format=csv&status=PICKED_UP", owner(store))).hasSize(2)
                .last().asString().startsWith(orderNumber(pickedUp) + ",");
        List<String> others = csv("/api/restaurant/orders/export?format=csv", owner(other));
        assertThat(others).hasSize(2);
        assertThat(others.get(1)).startsWith(orderNumber(othersOrder) + ",");

        MockHttpServletResponse xlsx = export("/api/restaurant/orders/export?format=xlsx", owner(store));
        assertThat(xlsx.getContentType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

        badRequest("/api/restaurant/orders/export?format=docx", owner(store));
        badRequest("/api/restaurant/orders/export?format=csv&status=NOPE", owner(store));
        // an owner whose restaurant is not approved (no restaurant_id claim) gets nothing
        mvc.perform(get("/api/restaurant/orders/export?format=csv").with(as(store.ownerUserId(), OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_ACTIVE"));
        mvc.perform(get("/api/restaurant/orders/export?format=csv").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/restaurant/orders/export?format=csv")).andExpect(status().isUnauthorized());
    }
}
