package com.grocery.ai;

import com.grocery.ai.SseClient.Event;
import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer support assistant (docs/api/payment-and-support-agents.md): tracking, returns, complaints and the
 * order tools it shares with the shopping assistant, over real HTTP chat with the scripted model, ToolGuard and the
 * fake gateway. Everything is read and written with the customer's own token.
 */
class SupportAssistantIntegrationTest extends AbstractIntegrationTest {

    private static Map<String, Object> summary(UUID id, String number, String status) {
        return Fixtures.map("id", id.toString(), "orderNumber", number, "type", "GROCERY", "status", status,
                "store", Fixtures.map("name", "Fresh Mart"), "itemCount", 2, "total", new BigDecimal("260.00"),
                "currency", "INR", "payment", Fixtures.map("status", "PAID", "dueAt", null),
                "createdAt", "2026-11-19T08:00:00Z");
    }

    private void stubOrders(List<Map<String, Object>> summaries) {
        GATEWAY.json("GET", "/api/orders", 200, Fixtures.page(summaries));
    }

    /** A ComplaintView (order-service, contract §2.4). */
    private static Map<String, Object> complaint(String number, String status, UUID orderId, String orderNumber,
                                                 String resolution) {
        return Fixtures.map("id", UUID.randomUUID().toString(), "complaintNumber", number, "category", "DELIVERY",
                "status", status, "orderId", orderId == null ? null : orderId.toString(), "orderNumber", orderNumber,
                "storeName", orderNumber == null ? null : "Fresh Mart",
                "description", "The order came two hours late and the milk was warm", "resolution", resolution,
                "createdAt", "2026-11-19T09:00:00Z", "updatedAt", "2026-11-19T09:00:00Z",
                "resolvedAt", resolution == null ? null : "2026-11-19T11:00:00Z");
    }

    private JsonNode lastToolResult() {
        ToolResponseMessage response = (ToolResponseMessage) model.prompts().getLast().getInstructions().getLast();
        return Fixtures.JSON.readTree(response.getResponses().getFirst().responseData());
    }

    /** The result the model got from {@code tool} in the last turn (one of several tool calls). */
    private JsonNode toolResult(String tool) {
        return model.prompts().getLast().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> response.name().equals(tool))
                .map(response -> Fixtures.JSON.readTree(response.responseData()))
                .findFirst().orElseThrow();
    }

    @Test
    void anOrderOnTheWayIsTrackedWithoutThePartnersPrivateDetails() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-SUP0001", "OUT_FOR_DELIVERY")));
        Map<String, Object> view = Fixtures.order(orderId, "GA-SUP0001", "OUT_FOR_DELIVERY", false, "260.00");
        view.put("trackable", true);
        view.put("delivery", Fixtures.map("partnerName", "Ravi", "assignedAt", "2026-11-19T08:20:00Z",
                "pickedUpAt", "2026-11-19T08:35:00Z", "deliveredAt", null));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(view));
        GATEWAY.json("GET", "/api/delivery/orders/" + orderId + "/tracking", 200, Fixtures.json(Fixtures.map(
                "orderId", orderId.toString(), "status", "PICKED_UP",
                "partner", Fixtures.map("name", "Ravi", "vehicleType", "BIKE", "phone", "+919876543210",
                        "vehicleNumber", "TN09AB1234"),
                "pickup", Fixtures.map("name", "Fresh Mart", "latitude", 13.0, "longitude", 80.2),
                "drop", Fixtures.map("latitude", 12.98, "longitude", 80.25),
                "partnerLocation", Fixtures.map("latitude", 12.99, "longitude", 80.24, "heading", 90.0,
                        "at", "2026-11-19T08:40:00Z"),
                "etaMinutes", 12, "timeline", List.of())));

        Result result = chat(token, "support", "track GA-SUP0001");

        assertThat(toolEvents(result)).containsExactly("trackOrder:STARTED", "trackOrder:SUCCEEDED");
        Event tool = result.named("tool").getLast();
        assertThat(tool.text("summary")).isEqualTo("Order GA-SUP0001 is out for delivery, arriving in about 12 "
                + "minutes");
        assertThat(tool.data().get("links").get(0).get("path").asString()).isEqualTo("/orders/" + orderId);
        assertThat(result.answer()).isEqualTo("Order GA-SUP0001 is out for delivery, with Ravi, arriving in about 12 "
                + "min.");
        JsonNode remembered = lastToolResult();
        assertThat(remembered.get("delivery").get("statusText").asString()).isEqualTo("picked up, on the way to you");
        assertThat(remembered.get("delivery").get("lastLocationAt").asString()).isEqualTo("19 Nov, 2:10 pm IST");
        // no phone number, number plate or coordinates for the model
        assertThat(remembered.toString()).doesNotContain("+91987", "TN09", "latitude");
        assertThat(GATEWAY.requests("GET", "/api/delivery/orders/.*/tracking").getFirst().header("Authorization"))
                .isEqualTo("Bearer " + token);

        // "where is my order" without a number: the newest order
        Result latest = chat(token, "support", "Where is my order?");
        assertThat(toolEvents(latest)).containsExactly("listMyOrders:STARTED", "listMyOrders:SUCCEEDED",
                "trackOrder:STARTED", "trackOrder:SUCCEEDED");
        assertThat(latest.answer()).isEqualTo("Order GA-SUP0001 is out for delivery, with Ravi, arriving in about 12 "
                + "min.");
        // the order list's times are India times, written out (the API's are UTC)
        JsonNode listed = toolResult("listMyOrders").get("orders").get(0);
        assertThat(listed.get("placedAt").asString()).isEqualTo("19 Nov, 1:30 pm IST");
        assertThat(listed.has("createdAt")).isFalse();

        // before a delivery is under way there is nothing to track live: the order's status says it all
        view.put("status", "PREPARING");
        view.put("trackable", false);
        view.put("delivery", null);
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(view));
        assertThat(chat(token, "support", "track GA-SUP0001").answer()).isEqualTo("Order GA-SUP0001 is being "
                + "prepared.");
        assertThat(GATEWAY.requests("GET", "/api/delivery/orders/.*/tracking")).hasSize(2);

        // a cancelled order says why and when, in words
        view.put("status", "CANCELLED");
        view.put("cancellation", Fixtures.map("reason", "PAYMENT_TIMEOUT", "note", null, "by", "SYSTEM",
                "at", "2026-11-19T08:45:00Z"));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(view));
        Result cancelled = chat(token, "support", "track GA-SUP0001");
        assertThat(cancelled.named("tool").getLast().text("summary")).isEqualTo("Order GA-SUP0001 is cancelled (the "
                + "time to pay ran out)");
        assertThat(cancelled.answer()).isEqualTo("Order GA-SUP0001 is cancelled (because the time to pay ran out).");
        assertThat(lastToolResult().get("cancelledAt").asString()).isEqualTo("19 Nov, 2:15 pm IST");
        // the order details tool (shared with the shopping and admin assistants) gives India times too: given
        // "2026-11-18T15:07:08Z" the real model once told a customer "cancelled at 15:07" for 8:37 pm
        chat(token, "support", "status of GA-SUP0001");
        JsonNode details = lastToolResult().get("order");
        assertThat(details.get("placedAt").asString()).isEqualTo("13 Nov, 1:30 pm IST");
        assertThat(details.get("cancellation").get("at").asString()).isEqualTo("19 Nov, 2:15 pm IST");
        assertThat(details.get("cancellation").get("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        // and the reason in words too: given only the code, the model read "STORE_REJECTED" out to people
        assertThat(details.get("cancellation").get("reasonText").asString()).isEqualTo("the time to pay ran out");
        assertThat(details.has("createdAt")).isFalse();

        // the latest order cancelled: the context line carries the real reason (a small model answering "where is my
        // order?" from the context alone made one up)
        stubOrders(List.of(summary(orderId, "GA-SUP0001", "CANCELLED")));
        chat(token, "support", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("latest order GA-SUP0001: CANCELLED")
                .contains("placed 19 Nov, 1:30 pm IST")
                .contains("(cancelled because the time to pay ran out, on 19 Nov, 2:15 pm IST)");
    }

    @Test
    void aComplaintIsRaisedForTheCustomersOrderAndItsAnswerFollowedUp() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-SUP0002", "DELIVERED")));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-SUP0002",
                "DELIVERED", false, "260.00")));
        GATEWAY.json("POST", "/api/complaints", 201, Fixtures.json(complaint("CMP-7K3F9Q2M", "OPEN", orderId,
                "GA-SUP0002", null)));

        Result result = chat(token, "support", "complaint delivery about GA-SUP0002: The order came two hours late "
                + "and the milk was warm");

        assertThat(toolEvents(result)).containsExactly("fileComplaint:STARTED", "fileComplaint:SUCCEEDED");
        Event tool = result.named("tool").getLast();
        assertThat(tool.text("label")).isEqualTo("Raised a complaint");
        assertThat(tool.text("summary")).isEqualTo("Raised complaint CMP-7K3F9Q2M");
        assertThat(tool.data().get("links").get(0).get("path").asString()).isEqualTo("/account/complaints");
        assertThat(result.answer()).isEqualTo("I've raised complaint CMP-7K3F9Q2M (delivery (late, not delivered, "
                + "the delivery partner)). Our support team answers it in the app under Help & support.");
        FakeGateway.Request post = GATEWAY.requests("POST", "/api/complaints").getFirst();
        assertThat(post.bodyText()).isEqualTo("{\"category\":\"DELIVERY\",\"orderId\":\"" + orderId + "\","
                + "\"description\":\"The order came two hours late and the milk was warm\"}");
        assertThat(post.header("Authorization")).isEqualTo("Bearer " + token);
        // a complaint is a LOW_RISK_WRITE: audited like a cart change
        assertThat(auditRows(customer)).anySatisfy(row -> assertThat(row).containsEntry("tool", "fileComplaint")
                .containsEntry("status", "SUCCEEDED"));

        // the open complaint is in the next turn's context, and its answer is read back once support resolved it
        GATEWAY.json("GET", "/api/complaints", 200, Fixtures.page(List.of(complaint("CMP-7K3F9Q2M", "OPEN", orderId,
                "GA-SUP0002", null))));
        chat(token, "support", "my complaints");
        String context = TestModels.systemText(model.prompts().getLast());
        assertThat(context).contains("open complaints: 1 (CMP-7K3F9Q2M about GA-SUP0002, waiting for the support "
                + "team)");
        GATEWAY.json("GET", "/api/complaints", 200, Fixtures.page(List.of(complaint("CMP-7K3F9Q2M", "RESOLVED",
                orderId, "GA-SUP0002", "Sorry about that! We refunded the delivery fee."))));
        Result answered = chat(token, "support", "Did support answer my complaint?");
        assertThat(answered.named("tool").getLast().text("summary")).isEqualTo("1 complaint, all resolved");
        assertThat(answered.answer()).isEqualTo("CMP-7K3F9Q2M about GA-SUP0002: resolved — answer: Sorry about that! "
                + "We refunded the delivery fee.");
        // "no open complaints" alone read as "no complaints" to a small model, which then made up replies: the
        // resolved ones come with their (shortened) answers, and the line says what it does not hold
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("no open complaints; resolved complaints: 1 (newest: CMP-7K3F9Q2M about GA-SUP0002, "
                        + "answered \"Sorry about that! We refunded the delivery fee.\")")
                .contains("anything else (where an order is now, why another order was cancelled, an order's refund");
    }

    @Test
    void invalidOrRefusedComplaintsAreExplainedAndNothingIsInvented() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-SUP0003", "DELIVERED")));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-SUP0003",
                "DELIVERED", false, "260.00")));

        // checked before anything is sent: an unknown category, a description that is too short
        assertThat(chat(token, "support", "use tool fileComplaint {\"category\":\"ANGRY\",\"description\":"
                + "\"The delivery was very late\"}").answer()).isEqualTo("I can't do that: Invalid arguments: "
                + "category must be one of [APP, DELIVERY, ITEMS, OTHER, PAYMENT, STORE].");
        assertThat(chat(token, "support", "use tool fileComplaint {\"category\":\"APP\",\"description\":\"bad\"}")
                .answer()).startsWith("I can't do that: Invalid arguments: description");
        // an order that is not the customer's
        assertThat(chat(token, "support", "complaint delivery about GA-NOPE0001: The order never arrived at all")
                .answer()).isEqualTo("That didn't work: There is no order GA-NOPE0001. (ORDER_NOT_FOUND)");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();

        // order-service's own rules come back as they are
        GATEWAY.on("POST", "/api/complaints", request -> FakeGateway.Response.problem(409,
                "COMPLAINT_ALREADY_OPEN", "Complaint CMP-4H8K2M9P about order GA-SUP0003 is still open."));
        assertThat(chat(token, "support", "complaint items about GA-SUP0003: The rice bag was torn open")
                .answer()).isEqualTo("That didn't work: Complaint CMP-4H8K2M9P about order GA-SUP0003 is still open. "
                + "(COMPLAINT_ALREADY_OPEN)");
        // with complaints unreachable the context says so instead of guessing
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("complaints unavailable right now");
    }

    @Test
    void returnsAndTheOrderToolsSharedWithTheShoppingAssistant() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-SUP0004", "CONFIRMED")));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-SUP0004",
                "CONFIRMED", true, "260.00")));
        GATEWAY.json("GET", "/api/returns", 200, Fixtures.page(List.of(
                Fixtures.map("id", UUID.randomUUID().toString(), "returnNumber", "RT-7K3M9QX2", "orderNumber",
                        "GA-SUP0003", "storeName", "Fresh Mart", "status", "REQUESTED", "reason", "DAMAGED",
                        "items", List.of(Fixtures.map("name", "Smart Rice", "quantity", 1, "refundAmount", 90.00)),
                        "refundAmount", 90.00, "decideBy", "2026-11-20T08:00:00Z",
                        "requestedAt", "2026-11-19T08:00:00Z"),
                Fixtures.map("id", UUID.randomUUID().toString(), "returnNumber", "RT-2B6N4P8R", "orderNumber",
                        "GA-SUP0001", "storeName", "Fresh Mart", "status", "REFUNDED", "reason", "MISSING",
                        "items", List.of(Fixtures.map("name", "Toor Dal", "quantity", 1, "refundAmount", 54.00)),
                        "refundAmount", 54.00, "requestedAt", "2026-11-17T08:00:00Z",
                        "decidedAt", "2026-11-17T10:00:00Z", "comment", "Sorry, it was missed while packing.",
                        "refundedAt", "2026-11-17T10:05:00Z"))));

        Result returns = chat(token, "support", "my returns");
        assertThat(returns.named("tool").getLast().text("summary")).isEqualTo("2 return requests, 1 waiting for a "
                + "decision");
        assertThat(returns.answer()).isEqualTo("RT-7K3M9QX2 for GA-SUP0003: waiting for the store's decision, refund "
                + "₹90.00; RT-2B6N4P8R for GA-SUP0001: refunded, refund ₹54.00.");
        JsonNode remembered = lastToolResult();
        assertThat(remembered.get("returns").get(0).get("decisionDueBy").asString()).isEqualTo("20 Nov, 1:30 pm IST");
        assertThat(remembered.get("returns").get(1).get("decisionComment").asString())
                .isEqualTo("Sorry, it was missed while packing.");
        assertThat(chat(token, "support", "returns of ga-sup0001").answer()).isEqualTo("RT-2B6N4P8R for GA-SUP0001: "
                + "refunded, refund ₹54.00.");
        // the context line counts the returns and names the newest, so "what happened to my returns?" answered
        // without a tool is still real data
        assertThat(TestModels.systemText(model.prompts().getLast())).contains("return requests: 2 "
                + "(latest RT-7K3M9QX2 for GA-SUP0003: waiting for the store's decision)");

        // the shared order tools: a cancellation is only proposed, by the support assistant, for the customer
        Result cancel = chat(token, "support", "cancel order GA-SUP0004");
        assertThat(toolEvents(cancel)).containsExactly("cancelOrder:STARTED", "cancelOrder:APPROVAL_REQUIRED");
        assertThat(cancel.first("approval").data().get("agent").asString()).isEqualTo("support");
        assertThat(chat(token, "support", "status of GA-SUP0004").answer()).contains("GA-SUP0004");
        // the payment tool it shares with the payment assistant (no payment-service record: order-service's view)
        Result payment = chat(token, "support", "use tool getPaymentStatus {\"orderNumber\":\"GA-SUP0004\"}");
        assertThat(toolEvents(payment)).containsExactly("getPaymentStatus:STARTED", "getPaymentStatus:SUCCEEDED");
        assertThat(payment.answer()).startsWith("You paid ₹260.00");
        // the shopping assistant's cart tools are not part of support
        assertThat(chat(token, "support", "use tool addToCart {\"productId\":\"" + Fixtures.RICE + "\"}").answer())
                .isEqualTo("I can't do that: The tool 'addToCart' is not available to the Customer support "
                        + "assistant.");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
    }
}
