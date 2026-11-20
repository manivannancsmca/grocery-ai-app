package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery assistant (Phase 12 §6): one agent for partners and managers, split by ToolGuard's role check; a
 * manager's reassignment is a proposal decided by DELIVERY_MANAGER, OPS_ADMIN or SUPER_ADMIN (no four-eyes) and
 * executed with a delivery-service approval token.
 */
class DeliveryAssistantIntegrationTest extends AbstractIntegrationTest {

    private static final UUID RAVI = UUID.fromString("0192f000-0000-7000-8000-0000000000f1");
    private static final UUID MEENA = UUID.fromString("0192f000-0000-7000-8000-0000000000f2");

    private static UUID approvalId(Result result) {
        return UUID.fromString(result.first("approval").text("id"));
    }

    @Test
    void aPartnerReadsTheirOwnDeliveriesAndMayNotUseTheManagerTools() {
        UUID partner = UUID.randomUUID();
        String token = TOKENS.token(partner, "DELIVERY_PARTNER");
        Map<String, Object> active = Fixtures.adminDelivery(UUID.randomUUID(), "GA-D0001", "ASSIGNED", partner,
                "Asha", false);
        GATEWAY.json("GET", "/api/delivery/deliveries/active", 200, Fixtures.json(active));
        GATEWAY.json("GET", "/api/delivery/offers", 200, Fixtures.json(List.of(Fixtures.map("offerId",
                UUID.randomUUID().toString(), "orderNumber", "GA-D0002", "orderType", "RESTAURANT",
                "store", Fixtures.map("name", "Spice Route", "city", "Bengaluru"), "dropArea", "Indiranagar",
                "distanceKm", 1.2, "tripKm", 3.4, "itemCount", 2, "expiresAt", "2026-11-13T09:00:30Z"))));
        GATEWAY.json("GET", "/api/delivery/history", 200, Fixtures.page(List.of(Fixtures.map("orderNumber",
                "GA-D0000", "status", "DELIVERED", "store", "Green Basket", "dropArea", "Koramangala"))));
        GATEWAY.json("GET", "/api/delivery/history/summary", 200, Fixtures.json(Fixtures.map("deliveredToday", 3,
                "deliveredThisWeek", 17, "failedThisWeek", 1)));

        Result result = chat(token, "delivery", "active delivery");
        assertThat(toolEvents(result)).containsExactly("getActiveDelivery:STARTED", "getActiveDelivery:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("Your active delivery: order GA-D0001 (ASSIGNED), pick up at Green "
                + "Basket, drop at 12 MG Road, Bengaluru.");
        assertThat(GATEWAY.requests("GET", "/api/delivery/deliveries/active").getFirst().header("Authorization"))
                .isEqualTo("Bearer " + token);
        // what the model got: the drop address without the recipient's phone
        JsonNode remembered = json.readTree(((org.springframework.ai.chat.messages.ToolResponseMessage)
                model.prompts().getLast().getInstructions().getLast()).getResponses().getFirst().responseData());
        assertThat(remembered.get("delivery").get("drop").has("phone")).isFalse();
        assertThat(remembered.get("delivery").get("drop").get("landmark").asString()).isEqualTo("Near the metro");
        // the next stop is the pickup; no reported position yet, so no distance (and never any coordinates)
        assertThat(remembered.get("delivery").get("nextStop").asString()).isEqualTo("PICKUP");
        assertThat(remembered.get("delivery").has("distanceToNextStopKm")).isFalse();
        assertThat(remembered.toString()).doesNotContain("latitude", "longitude");

        // with the partner's own last position (≈1.1 km south of the pickup), the distance to the pickup...
        GATEWAY.json("GET", "/api/delivery/partners/me", 200, "{\"status\":\"APPROVED\"}");
        GATEWAY.json("GET", "/api/delivery/partners/me/status", 200, Fixtures.json(Fixtures.map("partnerId",
                partner.toString(), "online", true, "location", Fixtures.map("latitude", 12.96, "longitude", 77.59,
                        "at", "2026-11-13T09:01:00Z"), "activeDeliveryId", active.get("deliveryId"), "pendingOffers", 0)));
        chat(token, "delivery", "active delivery");
        JsonNode located = json.readTree(((org.springframework.ai.chat.messages.ToolResponseMessage)
                model.prompts().getLast().getInstructions().getLast()).getResponses().getFirst().responseData());
        assertThat(located.get("delivery").get("distanceToNextStopKm").asDouble()).isEqualTo(1.1);
        // the chat's live context says the same, so an answer given without a tool call has the true distance too
        String context = TestModels.systemText(model.prompts().getLast());
        assertThat(context).contains("active delivery: order GA-D0001, status ASSIGNED, pickup at Green Basket, "
                + "customer's delivery address Asha Rao, 12 MG Road, Near the metro, Bengaluru 560001, next stop the "
                + "pickup at Green Basket, 1.1 km away in a straight line from your last reported position");
        // the partner may see their own drop address, never the customer's phone or any coordinates
        assertThat(context).contains("except the delivery address of this partner's own active delivery")
                .doesNotContain("+919876543210", "12.98", "77.6");
        // ...and after the pickup, to the customer (12.98, 77.60: ≈2.5 km from that position)
        Map<String, Object> pickedUp = new java.util.HashMap<>(active);
        pickedUp.put("status", "PICKED_UP");
        GATEWAY.json("GET", "/api/delivery/deliveries/active", 200, Fixtures.json(pickedUp));
        chat(token, "delivery", "active delivery");
        JsonNode onTheWay = json.readTree(((org.springframework.ai.chat.messages.ToolResponseMessage)
                model.prompts().getLast().getInstructions().getLast()).getResponses().getFirst().responseData());
        assertThat(onTheWay.get("delivery").get("nextStop").asString()).isEqualTo("CUSTOMER");
        assertThat(onTheWay.get("delivery").get("distanceToNextStopKm").asDouble()).isEqualTo(2.5);
        assertThat(GATEWAY.requests("GET", "/api/delivery/partners/me/status").getLast().header("Authorization"))
                .isEqualTo("Bearer " + token);

        assertThat(chat(token, "delivery", "use tool getMyOffers").named("tool").getLast().text("summary"))
                .isEqualTo("1 pending offer");
        assertThat(chat(token, "delivery", "use tool getMyHistory").named("tool").getLast().text("summary"))
                .isEqualTo("1 recent deliveries");
        assertThat(GATEWAY.requests("GET", "/api/delivery/history").getFirst().param("size")).isEqualTo("10");
        assertThat(chat(token, "delivery", "use tool getMySummary").named("tool").getLast().text("summary"))
                .isEqualTo("3 delivered today, 17 this week");

        // no active delivery: 204
        GATEWAY.on("GET", "/api/delivery/deliveries/active", request -> FakeGateway.Response.json(204, null));
        assertThat(chat(token, "delivery", "active delivery").answer()).isEqualTo("You have no active delivery "
                + "right now.");

        // the manager tools are refused for a partner, before any call
        Result deliveries = chat(token, "delivery", "deliveries");
        assertThat(toolEvents(deliveries)).containsExactly("listDeliveries:STARTED", "listDeliveries:BLOCKED");
        assertThat(deliveries.answer()).isEqualTo("I can't do that: Your role may not use 'listDeliveries'.");
        Result reassign = chat(token, "delivery", "reassign GA-D0001");
        assertThat(reassign.named("approval")).isEmpty();
        assertThat(reassign.answer()).isEqualTo("I can't do that: Your role may not use 'reassignDelivery'.");
        assertThat(GATEWAY.requests("GET", "/api/admin/.*")).isEmpty();
        assertThat(auditRows(partner)).extracting(r -> r.get("tool") + ":" + r.get("status") + ":" + r.get("reason"))
                .containsExactly("getActiveDelivery:SUCCEEDED:null", "getActiveDelivery:SUCCEEDED:null",
                        "getActiveDelivery:SUCCEEDED:null", "getMyOffers:SUCCEEDED:null",
                        "getMyHistory:SUCCEEDED:null", "getMySummary:SUCCEEDED:null",
                        "getActiveDelivery:SUCCEEDED:null", "listDeliveries:BLOCKED:ROLE_NOT_ALLOWED",
                        "reassignDelivery:BLOCKED:ROLE_NOT_ALLOWED");
    }

    @Test
    void aManagerMayNotUseThePartnerTools() {
        UUID manager = UUID.randomUUID();
        Result result = chat(TOKENS.token(manager, "DELIVERY_MANAGER"), "delivery", "active delivery");
        assertThat(result.answer()).isEqualTo("I can't do that: Your role may not use 'getActiveDelivery'.");
        assertThat(auditRows(manager)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("reason", "ROLE_NOT_ALLOWED").containsEntry("agent", "delivery"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    void aManagerListsDeliveriesAndLooksOneUp() {
        UUID manager = UUID.randomUUID();
        String token = TOKENS.token(manager, "DELIVERY_MANAGER");
        UUID deliveryId = UUID.randomUUID();
        Map<String, Object> detail = Fixtures.adminDelivery(deliveryId, "GA-D0001", "SEARCHING", null, null, true);
        detail.put("offers", List.of(Fixtures.map("offerId", UUID.randomUUID().toString(), "partnerId",
                RAVI.toString(), "partnerName", "Ravi", "distanceKm", 2.1, "status", "EXPIRED")));
        GATEWAY.json("GET", "/api/admin/deliveries", 200, Fixtures.page(List.of(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "SEARCHING", null, null, true),
                Fixtures.adminDelivery(UUID.randomUUID(), "GA-D0002", "ASSIGNED", RAVI, "Ravi", false))));
        GATEWAY.json("GET", "/api/admin/deliveries/" + deliveryId, 200, Fixtures.json(detail));

        Result list = chat(token, "delivery", "deliveries");
        assertThat(list.answer()).isEqualTo("2 deliveries: GA-D0001 (SEARCHING, no partner found); GA-D0002 "
                + "(ASSIGNED, Ravi).");
        assertThat(list.named("tool").getLast().text("summary")).isEqualTo("2 deliveries (1 without a partner)");
        FakeGateway.Request request = GATEWAY.requests("GET", "/api/admin/deliveries").getFirst();
        assertThat(request.param("size")).isEqualTo("20");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + token);

        chat(token, "delivery", "use tool listDeliveries {\"status\":\"searching\",\"assignmentFailed\":true}");
        FakeGateway.Request filtered = GATEWAY.requests("GET", "/api/admin/deliveries").getLast();
        assertThat(filtered.param("status")).isEqualTo("SEARCHING");
        assertThat(filtered.param("assignmentFailed")).isEqualTo("true");

        Result one = chat(token, "delivery", "use tool getDelivery {\"orderNumber\":\"ga-d0001\"}");
        assertThat(one.named("tool").getLast().text("summary")).isEqualTo("Delivery of GA-D0001 is SEARCHING");
        assertThat(GATEWAY.requests("GET", "/api/admin/deliveries").getLast().param("q")).isEqualTo("GA-D0001");
        assertThat(GATEWAY.requests("GET", "/api/admin/deliveries/" + deliveryId)).hasSize(1);
    }

    @Test
    void aReassignmentIsProposedByAManagerAndApprovedByAnOpsAdmin() throws Exception {
        UUID manager = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String token = TOKENS.token(manager, "DELIVERY_MANAGER");
        GATEWAY.json("GET", "/api/admin/deliveries", 200, Fixtures.page(List.of(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "ASSIGNED", RAVI, "Ravi", false))));
        GATEWAY.json("GET", "/api/admin/deliveries/" + deliveryId, 200, Fixtures.json(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "ASSIGNED", RAVI, "Ravi", false)));

        Result result = chat(token, "delivery", "reassign GA-D0001");

        assertThat(toolEvents(result)).containsExactly("reassignDelivery:STARTED",
                "reassignDelivery:APPROVAL_REQUIRED");
        JsonNode view = result.first("approval").data();
        UUID approvalId = approvalId(result);
        assertThat(view.get("action").asString()).isEqualTo("REASSIGN_DELIVERY");
        assertThat(view.get("agent").asString()).isEqualTo("delivery");
        assertThat(view.get("approverUserId").isNull()).isTrue();
        assertThat(view.get("onBehalfOf").isNull()).isTrue();
        assertThat(view.get("approverRoles").valueStream().map(JsonNode::asString).toList())
                .containsExactly("DELIVERY_MANAGER", "OPS_ADMIN", "SUPER_ADMIN");
        assertThat(view.get("fourEyes").asBoolean()).isFalse();
        assertThat(view.get("canDecide").asBoolean()).as("four-eyes is off: the manager may decide it too").isTrue();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Reassign order GA-D0001 (search again)");
        assertThat(summaryLines(view)).containsExactly(
                "Order=GA-D0001",
                "Store=Green Basket",
                "Delivery status=ASSIGNED",
                "Current partner=Ravi",
                "New partner=Search again for the nearest available partner (not Ravi)",
                "Reason=Requested through the assistant");
        Map<String, Object> row = approvalRow(approvalId);
        assertThat(row).containsEntry("http_method", "POST")
                .containsEntry("http_path", "/api/admin/deliveries/" + deliveryId + "/reassign")
                .containsEntry("request_body", "{\"reason\":\"Requested through the assistant\"}");
        assertThat(lifetime(row)).isEqualTo(Duration.ofHours(24));
        assertThat(result.answer()).startsWith("I've prepared “Reassign order GA-D0001 (search again)”. Nothing "
                + "happens until a delivery manager or operations admin approves it");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();

        // who may see and decide it
        mvc.perform(get("/api/approvals/{id}", approvalId).with(as(UUID.randomUUID(), "DELIVERY_PARTNER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_FORBIDDEN"));
        UUID ops = UUID.randomUUID();
        assertThat(body(mvc.perform(get("/api/approvals").param("scope", "to-decide")
                .with(as(ops, "OPS_ADMIN")))).get("content").valueStream()
                .map(v -> v.get("id").asString()).toList()).contains(approvalId.toString());

        Map<String, Object> reassigned = Fixtures.adminDelivery(deliveryId, "GA-D0001", "SEARCHING", null, null,
                false);
        GATEWAY.json("POST", "/api/admin/deliveries/" + deliveryId + "/reassign", 200, Fixtures.json(reassigned));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(ops, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Ravi's scooter broke down\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decidedBy").value(ops.toString()))
                .andExpect(jsonPath("$.result.orderNumber").value("GA-D0001"))
                .andExpect(jsonPath("$.result.status").value("SEARCHING"));
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/admin/deliveries/.*/reassign").getFirst();
        assertThat(call.bodyText()).isEqualTo("{\"reason\":\"Requested through the assistant\"}");
        assertThat(call.header("Authorization")).isEqualTo("Bearer token-of-" + ops);
        assertThat(call.header("Idempotency-Key")).isNull();
        JWTClaimsSet claims = approvalClaims(call);
        assertThat(claims.getAudience()).containsExactly("delivery-service");
        assertThat(claims.getSubject()).isEqualTo(ops.toString());
        assertThat(claims.getClaim("obo")).isNull();
        assertThat(claims.getStringClaim("act")).isEqualTo("REASSIGN_DELIVERY");
        assertThat(claims.getStringClaim("htu")).isEqualTo("/api/admin/deliveries/" + deliveryId + "/reassign");
        assertThat(claims.getStringClaim("bsh")).isEqualTo(bodyHash(call.body()));
        assertThat(event(approvalId, "ApprovalExecuted").get("resultReference").asString()).isEqualTo("GA-D0001");
    }

    @Test
    void aReassignmentToAGivenPartnerChecksThatTheyAreOnlineAndFree() throws Exception {
        UUID manager = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String token = TOKENS.token(manager, "DELIVERY_MANAGER");
        GATEWAY.json("GET", "/api/admin/deliveries", 200, Fixtures.page(List.of(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "ASSIGNED", RAVI, "Ravi", false))));
        GATEWAY.json("GET", "/api/admin/deliveries/" + deliveryId, 200, Fixtures.json(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "ASSIGNED", RAVI, "Ravi", false)));
        GATEWAY.json("GET", "/api/admin/delivery-partners/online", 200, Fixtures.json(List.of(
                Fixtures.map("partnerId", MEENA.toString(), "name", "Meena", "vehicleType", "SCOOTER", "busy", false),
                Fixtures.map("partnerId", RAVI.toString(), "name", "Ravi", "vehicleType", "BIKE", "busy", true))));

        Result result = chat(token, "delivery", "use tool reassignDelivery {\"orderNumber\":\"GA-D0001\","
                + "\"partnerId\":\"" + MEENA + "\",\"reason\":\"Closer to the store\"}");
        JsonNode view = result.first("approval").data();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Reassign order GA-D0001 to Meena");
        assertThat(summaryLines(view)).contains("Current partner=Ravi", "New partner=Meena",
                "Reason=Closer to the store");
        assertThat(approvalRow(approvalId(result)).get("request_body")).isEqualTo("{\"partnerId\":\"" + MEENA
                + "\",\"reason\":\"Closer to the store\"}");

        // the requesting manager may approve it (four-eyes is off)
        GATEWAY.json("POST", "/api/admin/deliveries/" + deliveryId + "/reassign", 200, Fixtures.json(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "ASSIGNED", MEENA, "Meena", false)));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(result)).with(as(manager, "DELIVERY_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.partnerName").value("Meena"));
        assertThat(approvalClaims(GATEWAY.requests("POST", "/api/admin/deliveries/.*/reassign").getFirst())
                .getSubject()).isEqualTo(manager.toString());

        // a placeholder partner id means "search again"; the current partner, a busy or an offline one is refused
        Result placeholder = chat(token, "delivery", "use tool reassignDelivery {\"orderNumber\":\"GA-D0001\","
                + "\"partnerId\":\"none\"}");
        assertThat(approvalRow(approvalId(placeholder)).get("request_body")).isEqualTo("{}");
        assertThat(chat(token, "delivery", "use tool reassignDelivery {\"orderNumber\":\"GA-D0001\",\"partnerId\":\""
                + RAVI + "\"}").answer()).isEqualTo("I can't do that: Order GA-D0001 is already assigned to Ravi.");
        GATEWAY.json("GET", "/api/admin/deliveries/" + deliveryId, 200, Fixtures.json(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "SEARCHING", null, null, true)));
        assertThat(chat(token, "delivery", "use tool reassignDelivery {\"orderNumber\":\"GA-D0001\",\"partnerId\":\""
                + RAVI + "\"}").answer()).isEqualTo("That didn't work: Ravi is busy with another delivery. "
                + "(PARTNER_UNAVAILABLE)");
        UUID offline = UUID.randomUUID();
        assertThat(chat(token, "delivery", "use tool reassignDelivery {\"orderNumber\":\"GA-D0001\",\"partnerId\":\""
                + offline + "\"}").answer()).isEqualTo("That didn't work: The partner " + offline + " is not online "
                + "right now. (PARTNER_UNAVAILABLE)");

        // only a searching or assigned delivery can be reassigned
        GATEWAY.json("GET", "/api/admin/deliveries/" + deliveryId, 200, Fixtures.json(
                Fixtures.adminDelivery(deliveryId, "GA-D0001", "PICKED_UP", RAVI, "Ravi", false)));
        assertThat(chat(token, "delivery", "reassign GA-D0001").answer()).isEqualTo("That didn't work: The delivery "
                + "of order GA-D0001 is PICKED_UP; only a searching or assigned delivery can be reassigned. "
                + "(INVALID_DELIVERY_TRANSITION)");
        assertThat(chat(token, "delivery", "reassign GA-NOPE001").answer()).contains("DELIVERY_NOT_FOUND");
    }
}
