package com.grocery.ai.tool;

import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.link.PageLink;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the vendor and restaurant assistants share (Phase 8 store orders and sales reports, Phase 12 §1 store reviews
 * and §3 store returns): the same APIs under the store's prefix, always called as the store user.
 */
final class StoreTools {

    static final Set<String> STORE_ORDER_STATUSES = Set.of("ACTIVE", "NEW", "ACCEPTED", "PREPARING",
            "READY_FOR_PICKUP", "REJECTED", "CANCELLED");
    static final Set<String> RETURN_STATUSES = Set.of("REQUESTED", "ESCALATED", "APPROVED", "REJECTED", "REFUNDED");
    /** At most 92 days (Phase 8 §3). */
    static final int MAX_REPORT_DAYS = 92;

    private StoreTools() {
    }

    /** {@code from?}, {@code to?} (India dates, inclusive; default the last 7 days); from ≤ to, at most 92 days. */
    static ArgSchema salesReportArgs() {
        return ArgSchema.builder()
                .date("from", "First day, yyyy-MM-dd (India time; default 6 days before 'to')")
                .date("to", "Last day, yyyy-MM-dd (India time; default today)")
                .check(values -> {
                    LocalDate from = (LocalDate) values.get("from");
                    LocalDate to = (LocalDate) values.get("to");
                    if (from != null && to != null) {
                        if (from.isAfter(to)) {
                            throw new InvalidArgumentsException("from must not be after to");
                        }
                        if (from.plusDays(MAX_REPORT_DAYS - 1).isBefore(to)) {
                            throw new InvalidArgumentsException("the range may cover at most " + MAX_REPORT_DAYS
                                    + " days");
                        }
                    }
                })
                .build();
    }

    /** {@code GET {prefix}/reports/sales?from=&to=} → the SalesReport trimmed (top 5 items); {@code page} = the store's reports page. */
    static ToolResult salesReport(BusinessApi api, ToolCallContext call, String prefix, PageLink page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", call.args().date("from"));
        params.put("to", call.args().date("to"));
        return Results.from(api.get(call.http(), prefix + "/reports/sales", params), ok -> {
            JsonNode body = ok.body();
            ObjectNode report = Results.pick(body, "from", "to", "currency", "ordersReceived", "ordersAccepted",
                    "ordersRejected", "autoRejected", "ordersCancelled", "acceptanceRate", "grossSales", "itemsSold",
                    "averageOrderValue");
            ArrayNode top = Results.array();
            for (JsonNode item : Json.list(body, "topItems")) {
                if (top.size() < 5) {
                    top.add(Results.pick(item, "name", "quantity", "sales"));
                }
            }
            report.set("topItems", top);
            ObjectNode result = Results.object();
            result.set("report", report);
            return Results.ok(result, Objects.requireNonNullElse(Json.integer(body, "ordersReceived"), 0)
                    + " orders, gross sales " + Money.inr(Json.decimal(body, "grossSales")) + " ("
                    + Json.text(body, "from") + " – " + Json.text(body, "to") + ")", ok, page);
        });
    }

    /** {@code GET {prefix}/orders?status=&size=20} (status default ACTIVE when {@code activeByDefault}); {@code page} = the store's orders page. */
    static ToolResult storeOrders(BusinessApi api, ToolCallContext call, String prefix, boolean activeByDefault,
                                  PageLink page) {
        String status = call.args().string("status");
        if (status == null && activeByDefault) {
            status = "ACTIVE";
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status);
        params.put("size", 20);
        String shown = status;
        return Results.from(api.get(call.http(), prefix + "/orders", params), ok -> {
            ArrayNode orders = Results.array();
            int fresh = 0;
            for (JsonNode view : Json.list(ok.body(), "content")) {
                orders.add(storeOrder(view));
                if ("NEW".equals(Json.text(view, "status"))) {
                    fresh++;
                }
            }
            ObjectNode result = Results.object();
            Results.put(result, "status", shown);
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    orders.size()));
            result.set("orders", orders);
            String summary = orders.isEmpty() ? "No orders" + (shown == null ? "" : " (" + shown + ")")
                    : orders.size() + (orders.size() == 1 ? " order" : " orders")
                    + (fresh > 0 ? " (" + fresh + " new)" : "");
            return Results.ok(result, summary, ok, page);
        });
    }

    /** A StoreOrderView trimmed for the model (no customer phone or address exists in it anyway). */
    static ObjectNode storeOrder(JsonNode view) {
        ObjectNode order = Results.pick(view, "orderNumber", "status", "itemCount", "subtotal", "total", "note",
                "receivedAt", "acceptBy", "readyInMinutes", "readyBy");
        Results.put(order, "customerArea", Json.text(view, "customer.area"));
        ArrayNode items = Results.array();
        for (JsonNode item : Json.list(view, "items")) {
            items.add(Results.pick(item, "name", "quantity", "lineTotal"));
        }
        order.set("items", items);
        JsonNode rejection = Json.at(view, "rejection");
        if (rejection != null) {
            order.set("rejection", Results.pick(rejection, "reason", "note", "auto"));
        }
        return order;
    }

    /** {@code GET /api/store/returns?status=&size=20}: the caller's store's return requests; {@code page} = its returns page. */
    static ToolResult returns(BusinessApi api, ToolCallContext call, PageLink page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("size", 20);
        return Results.from(api.get(call.http(), "/api/store/returns", params), ok -> {
            ArrayNode returns = Results.array();
            for (JsonNode view : Json.list(ok.body(), "content")) {
                ObjectNode entry = Results.pick(view, "returnNumber", "orderNumber", "status", "reason",
                        "refundAmount", "decideBy", "requestedAt");
                Results.put(entry, "note", Results.truncate(Json.text(view, "note"), 200));
                ArrayNode items = Results.array();
                for (JsonNode item : Json.list(view, "items")) {
                    items.add(Results.pick(item, "name", "quantity", "refundAmount"));
                }
                entry.set("items", items);
                returns.add(entry);
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    returns.size()));
            result.set("returns", returns);
            return Results.ok(result, returns.isEmpty() ? "No return requests"
                    : returns.size() + (returns.size() == 1 ? " return request" : " return requests"), ok, page);
        });
    }

    /** {@code GET /api/store/reviews?size=20}: the published reviews of the store and its products or dishes; {@code page} = its reviews page. */
    static ToolResult reviews(BusinessApi api, ToolCallContext call, PageLink page) {
        return Results.from(api.get(call.http(), "/api/store/reviews", Map.of("size", 20)), ok -> {
            ArrayNode reviews = Results.array();
            for (JsonNode view : Json.list(ok.body(), "content")) {
                ObjectNode review = Results.pick(view, "targetType", "targetName", "rating", "title", "createdAt");
                Results.put(review, "comment", Results.truncate(Json.text(view, "comment"), 300));
                Results.put(review, "author", Objects.requireNonNullElse(Json.text(view, "authorName"),
                        Json.text(view, "customerName")));
                Results.put(review, "reply", Results.truncate(replyText(view), 200));
                reviews.add(review);
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    reviews.size()));
            result.set("reviews", reviews);
            return Results.ok(result, reviews.isEmpty() ? "No reviews yet"
                    : reviews.size() + (reviews.size() == 1 ? " review" : " reviews"), ok, page);
        });
    }

    /** The entries of an answer that is either a JSON array or a page ({@code content}). */
    static List<JsonNode> entries(JsonNode body) {
        if (body != null && body.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            body.forEach(items::add);
            return items;
        }
        return Json.list(body, "content");
    }

    /** A review's reply text, whether the API gives it as {@code reply: "…"} or {@code reply: { text }}. */
    private static String replyText(JsonNode review) {
        JsonNode reply = Json.at(review, "reply");
        if (reply == null) {
            return null;
        }
        return reply.isObject() ? Json.text(reply, "text") : reply.asString();
    }
}
