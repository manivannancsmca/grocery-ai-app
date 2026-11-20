package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
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
 * The shopping assistant's {@code requestReturn} (Phase 12 §6, §3): the customer's delivered order, items matched by
 * name, the refund estimated for the summary; the customer confirms it and order-service gets
 * {@code POST /api/orders/{id}/returns} with an order-service approval token on behalf of the customer.
 */
class ReturnRequestIntegrationTest extends AbstractIntegrationTest {

    private void stubDeliveredOrder(UUID orderId, String number, boolean returnable) {
        stubCart(Fixtures.cart(List.of()));
        GATEWAY.json("GET", "/api/orders", 200, Fixtures.page(List.of(
                Fixtures.map("id", UUID.randomUUID().toString(), "orderNumber", "GA-OTHER01"),
                Fixtures.map("id", orderId.toString(), "orderNumber", number))));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.deliveredOrder(orderId, number,
                returnable)));
    }

    @Test
    void aReturnIsProposedConfirmedByTheCustomerAndSentToOrderService() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubDeliveredOrder(orderId, "GA-RET0001", true);

        Result result = chat(token, "shopping", "return 1 smart rice from GA-RET0001 because the bag was torn");

        assertThat(toolEvents(result)).containsExactly("requestReturn:STARTED", "requestReturn:APPROVAL_REQUIRED");
        JsonNode view = result.first("approval").data();
        UUID approvalId = UUID.fromString(view.get("id").asString());
        assertThat(view.get("action").asString()).isEqualTo("REQUEST_RETURN");
        assertThat(view.get("agent").asString()).isEqualTo("shopping");
        assertThat(view.get("onBehalfOf").asString()).isEqualTo(customer.toString());
        assertThat(view.get("approverUserId").asString()).isEqualTo(customer.toString());
        assertThat(view.get("fourEyes").asBoolean()).isFalse();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Return 1 item from order GA-RET0001");
        // 200.00 × 1/2 less the 10 % coupon share (26.00 of 260.00)
        assertThat(view.get("summary").get("amount").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(summaryLines(view)).containsExactly(
                "Order=GA-RET0001",
                "Store=Fresh Mart",
                "Smart Rice × 1 of 2=₹90.00",
                "Reason=Damaged",
                "Note=the bag was torn",
                "Estimated refund=₹90.00 (the store decides; the exact refund is computed by the order service)");
        Map<String, Object> row = approvalRow(approvalId);
        assertThat(row).containsEntry("http_method", "POST")
                .containsEntry("http_path", "/api/orders/" + orderId + "/returns")
                .containsEntry("request_body", "{\"items\":[{\"itemId\":\"" + Fixtures.RICE + "\",\"quantity\":1}],"
                        + "\"note\":\"the bag was torn\",\"reason\":\"DAMAGED\"}");
        assertThat(lifetime(row)).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.answer()).startsWith("I've prepared “Return 1 item from order GA-RET0001”. Nothing happens "
                + "until you confirm it in the app");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();

        // only the customer sees and decides it
        mvc.perform(get("/api/approvals/{id}", approvalId).with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isNotFound());

        UUID returnId = UUID.randomUUID();
        GATEWAY.json("POST", "/api/orders/" + orderId + "/returns", 201, Fixtures.json(Fixtures.map(
                "id", returnId.toString(), "returnNumber", "RT-7K3M9QX2", "orderId", orderId.toString(),
                "orderNumber", "GA-RET0001", "status", "REQUESTED", "reason", "DAMAGED", "refundAmount", 90.00,
                "items", List.of(Fixtures.map("itemId", Fixtures.RICE.toString(), "name", "Smart Rice",
                        "quantity", 1, "refundAmount", 90.00)))));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.returnId").value(returnId.toString()))
                .andExpect(jsonPath("$.result.returnNumber").value("RT-7K3M9QX2"))
                .andExpect(jsonPath("$.result.refundAmount").value(90.00))
                .andExpect(jsonPath("$.result.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.result.orderNumber").value("GA-RET0001"))
                .andExpect(jsonPath("$.result.status").value("REQUESTED"));
        // the same result is kept on the request (GET /api/approvals/{id})
        mvc.perform(get("/api/approvals/{id}", approvalId).with(as(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.returnId").value(returnId.toString()))
                .andExpect(jsonPath("$.result.orderNumber").value("GA-RET0001"));
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/orders/.*/returns").getFirst();
        assertThat(call.bodyText()).isEqualTo(row.get("request_body"));
        assertThat(call.header("Authorization")).isEqualTo("Bearer token-of-" + customer);
        assertThat(call.header("Idempotency-Key")).isNull();
        JWTClaimsSet claims = approvalClaims(call);
        assertThat(claims.getAudience()).containsExactly("order-service");
        assertThat(claims.getSubject()).isEqualTo(customer.toString());
        assertThat(claims.getStringClaim("obo")).isEqualTo(customer.toString());
        assertThat(claims.getStringClaim("act")).isEqualTo("REQUEST_RETURN");
        assertThat(claims.getStringClaim("htu")).isEqualTo("/api/orders/" + orderId + "/returns");
        assertThat(claims.getStringClaim("bsh")).isEqualTo(bodyHash(call.body()));
        assertThat(event(approvalId, "ApprovalExecuted").get("resultReference").asString()).isEqualTo("RT-7K3M9QX2");
    }

    @Test
    void returningTheWholeOrderAddsThePaidDeliveryFeeToTheEstimate() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stubDeliveredOrder(orderId, "GA-RET0002", true);

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool requestReturn "
                + "{\"orderNumber\":\"GA-RET0002\",\"items\":[{\"name\":\"rice\",\"quantity\":2},"
                + "{\"itemId\":\"" + Fixtures.DAL + "\",\"quantity\":1}],\"reason\":\"quality\"}");

        JsonNode view = result.first("approval").data();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Return 3 items from order GA-RET0002");
        assertThat(view.get("summary").get("amount").decimalValue()).isEqualByComparingTo("259.00");
        assertThat(summaryLines(view)).containsExactly(
                "Order=GA-RET0002",
                "Store=Fresh Mart",
                "Smart Rice × 2 of 2=₹180.00",
                "Toor Dal × 1 of 1=₹54.00",
                "Delivery fee (whole order returned)=₹25.00",
                "Reason=Quality problem",
                "Estimated refund=₹259.00 (the store decides; the exact refund is computed by the order service)");
        assertThat(approvalRow(UUID.fromString(view.get("id").asString())).get("request_body")).isEqualTo(
                "{\"items\":[{\"itemId\":\"" + Fixtures.RICE + "\",\"quantity\":2},{\"itemId\":\"" + Fixtures.DAL
                        + "\",\"quantity\":1}],\"reason\":\"QUALITY\"}");
    }

    @Test
    void impossibleReturnsAreRefusedBeforeAnyApproval() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubDeliveredOrder(orderId, "GA-RET0003", true);

        assertThat(chat(token, "shopping", "return 1 milk from GA-RET0003 because it was missing").answer())
                .isEqualTo("I can't do that: Order GA-RET0003 has no item “milk” (it has: Smart Rice, Toor Dal).");
        assertThat(chat(token, "shopping", "return 3 smart rice from GA-RET0003 because damaged").answer())
                .isEqualTo("I can't do that: Only 2 × Smart Rice were ordered in GA-RET0003.");
        assertThat(chat(token, "shopping", "use tool requestReturn {\"orderNumber\":\"GA-RET0003\",\"items\":"
                + "[{\"quantity\":1}],\"reason\":\"DAMAGED\"}").answer()).isEqualTo("I can't do that: Invalid "
                + "arguments: items[0]: give the item's name or itemId.");
        assertThat(chat(token, "shopping", "use tool requestReturn {\"orderNumber\":\"GA-RET0003\",\"items\":"
                + "[{\"name\":\"rice\",\"quantity\":1}],\"reason\":\"BROKEN\"}").answer()).isEqualTo("I can't do "
                + "that: Invalid arguments: reason must be one of [DAMAGED, MISSING, OTHER, QUALITY, WRONG_ITEM].");

        // past the return window (or a return already open), and an order that was not delivered
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.deliveredOrder(orderId,
                "GA-RET0003", false)));
        assertThat(chat(token, "shopping", "return 1 rice from GA-RET0003 because stale").answer())
                .startsWith("That didn't work: Order GA-RET0003 can no longer be returned").endsWith(
                        "(RETURN_NOT_ALLOWED)");
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-RET0003",
                "OUT_FOR_DELIVERY", false, "259.00")));
        assertThat(chat(token, "shopping", "return 1 rice from GA-RET0003 because stale").answer())
                .isEqualTo("That didn't work: Order GA-RET0003 is OUT_FOR_DELIVERY; only a delivered order can be "
                        + "returned. (RETURN_NOT_ALLOWED)");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM approval_request WHERE requested_by = UUID_TO_BIN(?)")
                .param(customer.toString()).query(Long.class).single()).isZero();
    }
}
