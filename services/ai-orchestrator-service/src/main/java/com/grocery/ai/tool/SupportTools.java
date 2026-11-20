package com.grocery.ai.tool;

import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Json;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code support} assistant's own tools, called as the customer through the gateway (their own data only):
 * <ul>
 *   <li>{@code trackOrder} (READ): where an order is now — its status, the delivery partner's first name and the
 *       estimated arrival (delivery-service tracking);</li>
 *   <li>{@code listMyReturns} (READ): return requests and their status, decision and refund (order-service);</li>
 *   <li>{@code fileComplaint} (LOW_RISK_WRITE): raises a complaint the support team answers (order-service
 *       complaints, docs/api/payment-and-support-agents.md §2). It only records the customer's words — nothing about
 *       the order, money or stock changes — so it runs directly like a cart change, after the customer agreed;</li>
 *   <li>{@code listMyComplaints} (READ): the customer's complaints with their status and the team's answer.</li>
 * </ul>
 * The support assistant also uses the shopping assistant's {@code listMyOrders}, {@code getOrderStatus},
 * {@code cancelOrder} and {@code requestReturn}, and the payment assistant's {@code getPaymentStatus} (see
 * {@code Agents}): one implementation per tool, shared by the agents.
 */
@Component
public class SupportTools {

    /** Complaint categories (contract §2.2). */
    public static final Set<String> COMPLAINT_CATEGORIES = StatusWords.COMPLAINT_CATEGORY.keySet();
    static final Set<String> COMPLAINT_STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED");

    private final BusinessApi api;

    public SupportTools(BusinessApi api) {
        this.api = api;
    }

    public List<ToolSpec> tools() {
        return List.of(
                ToolSpec.run("trackOrder", "Tracked an order",
                        "Where one of the customer's orders is now: its status, the delivery partner's first name "
                                + "and vehicle, and the estimated minutes until it arrives (while it is on the way), "
                                + "or why and when it was cancelled.",
                        RiskClass.READ, ShoppingTools.CUSTOMER,
                        ArgSchema.builder().orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M").build(),
                        this::trackOrder),
                ToolSpec.run("listMyReturns", "Checked returns",
                        "The customer's return requests for items sent back after delivery (newest first, up to "
                                + "10), optionally of one order: status, items, the store's decision and comment, and "
                                + "the refund of the returned items. A cancelled order's refund is not a return: use "
                                + "getPaymentStatus.",
                        RiskClass.READ, ShoppingTools.CUSTOMER,
                        ArgSchema.builder().string("orderNumber", false, 3, 30, "Only the returns of this order, "
                                + "e.g. GA-7K3F9Q2M").build(),
                        this::listMyReturns),
                ToolSpec.run("fileComplaint", "Raised a complaint",
                        "Raise a complaint for the support team, who answer it in the app. Call it only after the "
                                + "customer agreed to the category, the order (if any) and the description you "
                                + "repeated to them. category: DELIVERY (late, not delivered, the delivery partner), "
                                + "PAYMENT (charged but not confirmed, charged twice, refund not received), ITEMS "
                                + "(quality, damaged, wrong or missing items — a refund for items is requestReturn "
                                + "instead), STORE (the store's or restaurant's service), APP (the app does not "
                                + "work) or OTHER.", RiskClass.LOW_RISK_WRITE, ShoppingTools.CUSTOMER,
                        ArgSchema.builder()
                                .requiredEnumeration("category", COMPLAINT_CATEGORIES,
                                        "DELIVERY, PAYMENT, ITEMS, STORE, APP or OTHER")
                                .string("orderNumber", false, 3, 30, "The order it is about, e.g. GA-7K3F9Q2M "
                                        + "(leave out when it is not about an order)")
                                .string("description", true, 10, 1000, "What happened, in the customer's words "
                                        + "(10–1000 characters)")
                                .build(),
                        this::fileComplaint),
                ToolSpec.run("listMyComplaints", "Checked complaints",
                        "The customer's complaints (newest first, up to 10) with their status and, once resolved, "
                                + "the support team's answer.", RiskClass.READ, ShoppingTools.CUSTOMER,
                        ArgSchema.builder().enumeration("status", COMPLAINT_STATUSES,
                                "OPEN, IN_PROGRESS or RESOLVED (all when left out)").build(),
                        this::listMyComplaints));
    }

    // ---- trackOrder -----------------------------------------------------------------------------------------------

    private ToolResult trackOrder(ToolCallContext call) {
        String number = call.args().string("orderNumber");
        Fetch order = api.customerOrder(call.http(), number);
        if (!(order instanceof Fetch.Ok orderOk)) {
            return failed(order);
        }
        JsonNode view = orderOk.body();
        UUID orderId = Json.uuid(view, "id");
        String status = Json.text(view, "status");

        ObjectNode result = Results.pick(view, "orderNumber", "type");
        Results.put(result, "store", Json.text(view, "store.name"));
        Results.put(result, "orderStatus", status);
        Results.put(result, "orderStatusText", StatusWords.order(status));
        Results.put(result, "placedAt", IndiaTime.format(Json.text(view, "createdAt")));
        Results.put(result, "readyBy", IndiaTime.format(Json.text(view, "readyBy")));
        Results.put(result, "pickedUpAt", IndiaTime.format(Json.text(view, "delivery.pickedUpAt")));
        Results.put(result, "deliveredAt", IndiaTime.format(Json.text(view, "delivery.deliveredAt")));

        String summary = "Order " + number + " is " + StatusWords.order(status);
        // "Why was it cancelled?" is the next question: the reason in words, and when.
        String cancelledBecause = StatusWords.cancellation(Json.text(view, "cancellation.reason"));
        if (cancelledBecause != null) {
            result.put("cancelledBecause", cancelledBecause);
            Results.put(result, "cancelledAt", IndiaTime.format(Json.text(view, "cancellation.at")));
            summary += " (" + cancelledBecause + ")";
        }
        // Live tracking exists once a delivery is under way (OrderView.trackable); before that the order's status is
        // the whole story.
        if (Json.bool(view, "trackable")) {
            Fetch tracking = api.get(call.http(), "/api/delivery/orders/" + orderId + "/tracking");
            if (tracking instanceof Fetch.Ok trackingOk) {
                JsonNode live = trackingOk.body();
                ObjectNode delivery = Results.object();
                Results.put(delivery, "status", Json.text(live, "status"));
                Results.put(delivery, "statusText", StatusWords.delivery(Json.text(live, "status")));
                // Only the partner's first name and vehicle: the app's tracking page has the call button, so the
                // model never handles phone numbers or number plates.
                Results.put(delivery, "partnerName", Json.text(live, "partner.name"));
                Results.put(delivery, "vehicleType", Json.text(live, "partner.vehicleType"));
                Results.put(delivery, "pickupFrom", Json.text(live, "pickup.name"));
                Integer eta = Json.integer(live, "etaMinutes");
                if (eta != null) {
                    delivery.put("etaMinutes", eta);
                    summary += ", arriving in about " + eta + (eta == 1 ? " minute" : " minutes");
                }
                Results.put(delivery, "lastLocationAt", IndiaTime.format(Json.text(live, "partnerLocation.at")));
                result.set("delivery", delivery);
            } else {
                result.put("note", "Live tracking is not available right now; the order status above is current.");
            }
        } else {
            Results.put(result, "partnerName", Json.text(view, "delivery.partnerName"));
        }
        return Results.ok(result, summary, orderOk, Pages.myOrder(orderId, number));
    }

    /**
     * " (cancelled because the store could not accept it, on 18 Nov, 8:37 pm IST)" for a cancelled OrderView, else
     * "". The support assistant's context line adds it to the latest order: asked "where is my order?", the small
     * model answered from that line without a tool and made up a reason ("the items were out of stock").
     */
    public static String cancellationNote(JsonNode orderView) {
        String because = StatusWords.cancellation(Json.text(orderView, "cancellation.reason"));
        if (because == null) {
            return "";
        }
        String at = IndiaTime.format(Json.text(orderView, "cancellation.at"));
        return " (cancelled because " + because + (at == null ? "" : ", on " + at) + ")";
    }

    // ---- listMyReturns --------------------------------------------------------------------------------------------

    private ToolResult listMyReturns(ToolCallContext call) {
        String orderNumber = upper(call.args().string("orderNumber"));
        return Results.from(api.get(call.http(), "/api/returns", Map.of("size", 50)), ok -> {
            ArrayNode returns = Results.array();
            int open = 0;
            for (JsonNode entry : Json.list(ok.body(), "content")) {
                if (orderNumber != null && !orderNumber.equalsIgnoreCase(Json.text(entry, "orderNumber"))) {
                    continue;
                }
                if (Set.of("REQUESTED", "ESCALATED").contains(Json.text(entry, "status"))) {
                    open++;
                }
                if (returns.size() < 10) {
                    returns.add(returnLine(entry));
                }
            }
            ObjectNode result = Results.object();
            Results.put(result, "orderNumber", orderNumber);
            result.put("count", returns.size());
            result.set("returns", returns);
            String summary = returns.isEmpty()
                    ? (orderNumber == null ? "No return requests" : "No return requests for " + orderNumber)
                    : returns.size() + (returns.size() == 1 ? " return request" : " return requests")
                    + (open > 0 ? ", " + open + " waiting for a decision" : "");
            return Results.ok(result, summary, ok, Pages.myReturns());
        });
    }

    /**
     * The support assistant's context-line snapshot of a {@code GET /api/returns?size=1} page (newest first):
     * "return requests: 2 (latest RT-7K3M9QX2 for GA-SUP0003: waiting for the store's decision)" or "no return
     * requests". The small model answered "what happened to my returns?" without a tool; with the snapshot it answers
     * from real data, and the details still come from listMyReturns.
     */
    public static String returnsSnapshot(JsonNode page) {
        List<JsonNode> returns = Json.list(page, "content");
        if (returns.isEmpty()) {
            return "no return requests";
        }
        Integer total = Json.integer(page, "totalElements");
        JsonNode latest = returns.getFirst();
        return "return requests: " + (total != null ? total : returns.size()) + " (latest "
                + Json.text(latest, "returnNumber") + " for " + Json.text(latest, "orderNumber") + ": "
                + StatusWords.returnStatus(Json.text(latest, "status")) + ")";
    }

    /** One return for the model: status and reason in codes and words, items, decision, refund, dates in IST. */
    private static ObjectNode returnLine(JsonNode entry) {
        ObjectNode line = Results.pick(entry, "returnNumber", "orderNumber", "refundAmount");
        Results.put(line, "store", Json.text(entry, "storeName"));
        Results.put(line, "status", Json.text(entry, "status"));
        Results.put(line, "statusText", StatusWords.returnStatus(Json.text(entry, "status")));
        Results.put(line, "reason", StatusWords.returnReason(Json.text(entry, "reason")));
        ArrayNode items = Results.array();
        for (JsonNode item : Json.list(entry, "items")) {
            items.add(Results.pick(item, "name", "quantity", "refundAmount"));
        }
        line.set("items", items);
        Results.put(line, "requestedAt", IndiaTime.format(Json.text(entry, "requestedAt")));
        if (Set.of("REQUESTED", "ESCALATED").contains(Json.text(entry, "status"))) {
            Results.put(line, "decisionDueBy", IndiaTime.format(Json.text(entry, "decideBy")));
        }
        Results.put(line, "decidedAt", IndiaTime.format(Json.text(entry, "decidedAt")));
        Results.put(line, "decisionComment", Results.truncate(Json.text(entry, "comment"), 300));
        Results.put(line, "refundedAt", IndiaTime.format(Json.text(entry, "refundedAt")));
        return line;
    }

    // ---- complaints -----------------------------------------------------------------------------------------------

    private ToolResult fileComplaint(ToolCallContext call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", call.args().string("category"));
        String orderNumber = upper(call.args().string("orderNumber"));
        if (orderNumber != null) {
            // The API takes the order's id; the customer knows its number.
            Fetch order = api.customerOrder(call.http(), orderNumber);
            if (!(order instanceof Fetch.Ok orderOk)) {
                return failed(order);
            }
            body.put("orderId", Json.text(orderOk.body(), "id"));
        }
        body.put("description", call.args().string("description"));
        return Results.from(api.send(call.http(), HttpMethod.POST, "/api/complaints", body), ok -> {
            ObjectNode result = Results.object();
            result.set("complaint", complaint(ok.body()));
            result.put("whatHappensNext", "Our support team reads it and answers in the app (Account → Help & "
                    + "support); the customer can also ask this assistant for its status.");
            return Results.ok(result, "Raised complaint " + Json.text(ok.body(), "complaintNumber"), ok,
                    Pages.myComplaints());
        });
    }

    private ToolResult listMyComplaints(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("size", 10);
        return Results.from(api.get(call.http(), "/api/complaints", params), ok -> {
            ArrayNode complaints = Results.array();
            int open = 0;
            for (JsonNode entry : Json.list(ok.body(), "content")) {
                complaints.add(complaint(entry));
                if (!"RESOLVED".equals(Json.text(entry, "status"))) {
                    open++;
                }
            }
            ObjectNode result = Results.object();
            result.set("complaints", complaints);
            String summary = complaints.isEmpty() ? "No complaints"
                    : complaints.size() + (complaints.size() == 1 ? " complaint" : " complaints")
                    + (open > 0 ? ", " + open + " open" : ", all resolved");
            return Results.ok(result, summary, ok, Pages.myComplaints());
        });
    }

    /** A ComplaintView for the model: number, category and status in codes and words, order, text, answer, dates. */
    static ObjectNode complaint(JsonNode view) {
        ObjectNode complaint = Results.pick(view, "complaintNumber", "orderNumber");
        Results.put(complaint, "store", Json.text(view, "storeName"));
        Results.put(complaint, "category", Json.text(view, "category"));
        Results.put(complaint, "categoryText", StatusWords.COMPLAINT_CATEGORY.get(Json.text(view, "category")));
        Results.put(complaint, "status", Json.text(view, "status"));
        Results.put(complaint, "statusText", StatusWords.complaint(Json.text(view, "status")));
        Results.put(complaint, "description", Results.truncate(Json.text(view, "description"), 300));
        Results.put(complaint, "answer", Results.truncate(Json.text(view, "resolution"), 500));
        Results.put(complaint, "raisedAt", IndiaTime.format(Json.text(view, "createdAt")));
        Results.put(complaint, "resolvedAt", IndiaTime.format(Json.text(view, "resolvedAt")));
        return complaint;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static ToolResult failed(Fetch fetch) {
        Fetch.Problem problem = (Fetch.Problem) fetch;
        return new ToolResult.Failed(problem.code(), problem.message(), problem.httpStatus());
    }

    /** An order number as people type it ("ga-28at309q ") in the API's form ("GA-28AT309Q"); null stays null. */
    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
