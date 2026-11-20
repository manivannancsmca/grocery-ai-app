package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The restaurant assistant (Phase 12 §6): the order queue, menu, sales, returns and reviews as the owner; marking a
 * dish sold out, accepting and rejecting an order are proposals the owner decides, executed with a
 * restaurant-service approval token.
 */
class RestaurantAssistantIntegrationTest extends AbstractIntegrationTest {

    private static UUID approvalId(Result result) {
        return UUID.fromString(result.first("approval").text("id"));
    }

    @Test
    void readToolsCallTheRestaurantApisAsTheOwner() {
        UUID owner = UUID.randomUUID();
        String token = TOKENS.token(owner, "RESTAURANT_OWNER");
        Map<String, Object> accepted = Fixtures.storeOrder(UUID.randomUUID(), "GA-R0002", "ACCEPTED");
        GATEWAY.json("GET", "/api/restaurant/orders", 200, Fixtures.page(List.of(
                Fixtures.storeOrder(UUID.randomUUID(), "GA-R0001", "NEW"), accepted)));
        GATEWAY.json("GET", "/api/restaurant/reports/sales", 200, Fixtures.salesReport());
        GATEWAY.json("GET", "/api/restaurant/menu", 200, Fixtures.menu());
        GATEWAY.json("GET", "/api/store/returns", 200, Fixtures.page(List.of()));
        GATEWAY.json("GET", "/api/store/reviews", 200, Fixtures.page(List.of()));

        Result orders = chat(token, "restaurant", "orders");
        assertThat(toolEvents(orders)).containsExactly("getOrderQueue:STARTED", "getOrderQueue:SUCCEEDED");
        assertThat(orders.named("tool").getLast().text("summary")).isEqualTo("2 orders (1 new)");
        assertThat(orders.answer()).isEqualTo("2 orders: GA-R0001 (NEW, 2 items, ₹520.00, accept by 14:35); "
                + "GA-R0002 (ACCEPTED, 2 items, ₹520.00).");
        FakeGateway.Request queue = GATEWAY.requests("GET", "/api/restaurant/orders").getFirst();
        assertThat(queue.param("status")).isEqualTo("ACTIVE");
        assertThat(queue.param("size")).isEqualTo("20");
        assertThat(queue.header("Authorization")).isEqualTo("Bearer " + token);

        assertThat(chat(token, "restaurant", "sales").answer()).startsWith("From 2026-11-07 to 2026-11-13: 12 orders "
                + "received");
        assertThat(GATEWAY.requests("GET", "/api/restaurant/reports/sales")).hasSize(1);
        Result menu = chat(token, "restaurant", "use tool getMenu");
        assertThat(menu.named("tool").getLast().text("summary")).isEqualTo("Menu: 3 dishes, 1 sold out");
        assertThat(menu.answer()).isEqualTo("Your menu has 3 dishes, 1 sold out.");
        assertThat(chat(token, "restaurant", "use tool listReturns").named("tool").getLast().text("summary"))
                .isEqualTo("No return requests");
        assertThat(chat(token, "restaurant", "use tool listReviews").named("tool").getLast().text("summary"))
                .isEqualTo("No reviews yet");
        assertThat(GATEWAY.requests()).allSatisfy(request -> assertThat(request.header("Authorization"))
                .isEqualTo("Bearer " + token));
        assertThat(auditRows(owner)).hasSize(5).allSatisfy(row -> assertThat(row)
                .containsEntry("agent", "restaurant").containsEntry("status", "SUCCEEDED"));
    }

    @Test
    void markingADishSoldOutIsProposedAndRunsOnlyAfterTheOwnerApproves() throws Exception {
        UUID owner = UUID.randomUUID();
        String token = TOKENS.token(owner, "RESTAURANT_OWNER");
        GATEWAY.json("GET", "/api/restaurant/menu", 200, Fixtures.menu());

        Result result = chat(token, "restaurant", "Mark paneer tikka sold out");

        assertThat(toolEvents(result)).containsExactly("getMenu:STARTED", "getMenu:SUCCEEDED",
                "setMenuItemAvailability:STARTED", "setMenuItemAvailability:APPROVAL_REQUIRED");
        JsonNode view = result.first("approval").data();
        assertThat(view.get("action").asString()).isEqualTo("SET_MENU_ITEM_AVAILABILITY");
        assertThat(view.get("agent").asString()).isEqualTo("restaurant");
        assertThat(view.get("approverUserId").asString()).isEqualTo(owner.toString());
        assertThat(view.get("onBehalfOf").isNull()).isTrue();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Mark Paneer Tikka sold out");
        assertThat(summaryLines(view)).containsExactly("Dish=Paneer Tikka", "Category=Starters", "Price=₹249.00",
                "Now=Available", "Change to=Sold out");
        Map<String, Object> row = approvalRow(approvalId(result));
        assertThat(row).containsEntry("http_method", "PATCH")
                .containsEntry("http_path", "/api/restaurant/menu/items/" + Fixtures.PANEER_TIKKA + "/availability")
                .containsEntry("request_body", "{\"available\":false}");
        assertThat(result.answer()).startsWith("I've prepared “Mark Paneer Tikka sold out”. Nothing happens until "
                + "you approve it in the app");
        assertThat(GATEWAY.requests("PATCH", ".*")).isEmpty();
        // the same proposal again is the same pending request (the longest action name fits the duplicate key)
        assertThat(approvalId(chat(token, "restaurant", "mark paneer tikka sold out"))).isEqualTo(approvalId(result));

        // another restaurant owner cannot see or decide it
        mvc.perform(get("/api/approvals/{id}", approvalId(result)).with(as(UUID.randomUUID(), "RESTAURANT_OWNER")))
                .andExpect(status().isNotFound());

        GATEWAY.json("PATCH", "/api/restaurant/menu/items/" + Fixtures.PANEER_TIKKA + "/availability", 200,
                Fixtures.json(Fixtures.menuItem(Fixtures.PANEER_TIKKA, "Paneer Tikka", "249.00", false)));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(result)).with(as(owner, "RESTAURANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.name").value("Paneer Tikka"))
                .andExpect(jsonPath("$.result.available").value(false));
        FakeGateway.Request call = GATEWAY.requests("PATCH", "/api/restaurant/menu/items/.*").getFirst();
        assertThat(call.bodyText()).isEqualTo("{\"available\":false}");
        assertThat(call.header("Authorization")).isEqualTo("Bearer token-of-" + owner);
        JWTClaimsSet claims = approvalClaims(call);
        assertThat(claims.getAudience()).containsExactly("restaurant-service");
        assertThat(claims.getStringClaim("htm")).isEqualTo("PATCH");
        assertThat(claims.getStringClaim("act")).isEqualTo("SET_MENU_ITEM_AVAILABILITY");
        assertThat(claims.getSubject()).isEqualTo(owner.toString());
        assertThat(claims.getClaim("obo")).isNull();
        assertThat(event(approvalId(result), "ApprovalExecuted").get("resultReference").asString())
                .isEqualTo("Paneer Tikka: sold out");

        // an unknown dish, and a dish that is already sold out
        assertThat(chat(token, "restaurant", "mark unicorn stew sold out").answer())
                .isEqualTo("I couldn't find a dish matching “unicorn stew” on your menu.");
        Result already = chat(token, "restaurant", "use tool setMenuItemAvailability {\"menuItemId\":\""
                + Fixtures.SPRING_ROLL + "\",\"available\":\"false\"}");
        assertThat(already.answer()).isEqualTo("That didn't work: Veg Spring Roll is already sold out. "
                + "(MENU_ITEM_UNCHANGED)");
        Result back = chat(token, "restaurant", "use tool setMenuItemAvailability {\"menuItemId\":\""
                + Fixtures.SPRING_ROLL + "\",\"available\":true}");
        assertThat(back.first("approval").data().get("summary").get("title").asString())
                .isEqualTo("Mark Veg Spring Roll available again");
    }

    @Test
    void acceptingAndRejectingANewOrderAreProposedFromTheStoreOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(owner, "RESTAURANT_OWNER");
        GATEWAY.json("GET", "/api/restaurant/orders", 200, Fixtures.page(List.of(
                Fixtures.storeOrder(orderId, "GA-R0001", "NEW"),
                Fixtures.storeOrder(UUID.randomUUID(), "GA-R0002", "ACCEPTED"))));

        Result result = chat(token, "restaurant", "accept ga-r0001 in 20 min");

        assertThat(toolEvents(result)).containsExactly("acceptOrder:STARTED", "acceptOrder:APPROVAL_REQUIRED");
        JsonNode view = result.first("approval").data();
        assertThat(view.get("action").asString()).isEqualTo("ACCEPT_STORE_ORDER");
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Accept order GA-R0001 (ready in 20 min)");
        assertThat(view.get("summary").get("amount").decimalValue()).isEqualByComparingTo("520.00");
        assertThat(summaryLines(view)).containsExactly(
                "Order=GA-R0001",
                "Customer area=Indiranagar, Bengaluru",
                "Paneer Tikka × 2=₹498.00",
                "Total=₹520.00",
                "Customer's note=Less spicy",
                "Accept by=14:35 (India)",
                "Ready in=20 min");
        assertThat(approvalRow(approvalId(result))).containsEntry("http_method", "POST")
                .containsEntry("http_path", "/api/restaurant/orders/" + orderId + "/accept")
                .containsEntry("request_body", "{\"readyInMinutes\":20}");

        Map<String, Object> acceptedView = Fixtures.storeOrder(orderId, "GA-R0001", "ACCEPTED");
        acceptedView.put("readyBy", "2026-11-13T09:25:00Z");
        GATEWAY.json("POST", "/api/restaurant/orders/" + orderId + "/accept", 200, Fixtures.json(acceptedView));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(result)).with(as(owner, "RESTAURANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.orderNumber").value("GA-R0001"))
                .andExpect(jsonPath("$.result.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.result.readyBy").value("2026-11-13T09:25:00Z"));
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/restaurant/orders/.*/accept").getFirst();
        assertThat(call.bodyText()).isEqualTo("{\"readyInMinutes\":20}");
        assertThat(approvalClaims(call).getAudience()).containsExactly("restaurant-service");
        assertThat(approvalClaims(call).getStringClaim("act")).isEqualTo("ACCEPT_STORE_ORDER");

        // reject: the reason as an enum (OTHER needs a note)
        Result reject = chat(token, "restaurant", "use tool rejectOrder {\"orderNumber\":\"GA-R0001\","
                + "\"reason\":\"too_busy\",\"rationale\":\"Kitchen is full.\"}");
        JsonNode rejectView = reject.first("approval").data();
        assertThat(rejectView.get("action").asString()).isEqualTo("REJECT_STORE_ORDER");
        assertThat(rejectView.get("summary").get("title").asString()).isEqualTo("Reject order GA-R0001");
        assertThat(rejectView.get("rationale").asString()).isEqualTo("Kitchen is full.");
        assertThat(summaryLines(rejectView)).contains("Reason=Too busy",
                "Customer=Refunded in full when it is rejected");
        assertThat(approvalRow(approvalId(reject))).containsEntry("http_path", "/api/restaurant/orders/" + orderId
                + "/reject").containsEntry("request_body", "{\"reason\":\"TOO_BUSY\"}");
        Map<String, Object> rejectedView = Fixtures.storeOrder(orderId, "GA-R0001", "REJECTED");
        rejectedView.put("rejection", Fixtures.map("reason", "TOO_BUSY", "note", null, "auto", false));
        GATEWAY.json("POST", "/api/restaurant/orders/" + orderId + "/reject", 200, Fixtures.json(rejectedView));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(reject)).with(as(owner, "RESTAURANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.status").value("REJECTED"));
        FakeGateway.Request rejectCall = GATEWAY.requests("POST", "/api/restaurant/orders/.*/reject").getFirst();
        assertThat(rejectCall.bodyText()).isEqualTo("{\"reason\":\"TOO_BUSY\"}");
        JWTClaimsSet rejectClaims = approvalClaims(rejectCall);
        assertThat(rejectClaims.getAudience()).containsExactly("restaurant-service");
        assertThat(rejectClaims.getStringClaim("act")).isEqualTo("REJECT_STORE_ORDER");
        assertThat(rejectClaims.getStringClaim("htu")).isEqualTo("/api/restaurant/orders/" + orderId + "/reject");
        assertThat(rejectClaims.getSubject()).isEqualTo(owner.toString());
        assertThat(chat(token, "restaurant", "use tool rejectOrder {\"orderNumber\":\"GA-R0001\",\"reason\":"
                + "\"OTHER\"}").answer()).isEqualTo("I can't do that: Invalid arguments: note is required when "
                + "reason is OTHER.");

        // only a NEW order can be accepted; an unknown one is not found; the time is 5–180 minutes
        assertThat(chat(token, "restaurant", "accept GA-R0002 in 15 min").answer()).isEqualTo("That didn't work: "
                + "Order GA-R0002 is ACCEPTED; only a new order can be accepted. (STORE_ORDER_NOT_PENDING)");
        assertThat(chat(token, "restaurant", "accept GA-NOPE001 in 15 min").answer()).contains("STORE_ORDER_NOT_FOUND");
        assertThat(chat(token, "restaurant", "accept GA-R0001 in 300 min").answer()).isEqualTo("I can't do that: "
                + "Invalid arguments: readyInMinutes must be between 5 and 180.");
    }
}
