package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.Json;
import com.grocery.common.security.Roles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code delivery} agent's tools (Phase 12 §6; Phase 9 APIs of delivery-service). One agent for two roles, split
 * by ToolGuard's role check: a DELIVERY_PARTNER uses the partner tools (their own active delivery, offers, history),
 * a DELIVERY_MANAGER the manager tools (the delivery monitor and reassignment, SENSITIVE). Either one asking for the
 * other's tool is BLOCKED ROLE_NOT_ALLOWED.
 */
@Component
public class DeliveryTools {

    static final Set<String> PARTNER = Set.of(Roles.DELIVERY_PARTNER);
    static final Set<String> MANAGER = Set.of(Roles.DELIVERY_MANAGER);
    static final Set<String> DELIVERY_STATUSES = Set.of("PENDING", "SEARCHING", "ASSIGNED", "PICKED_UP", "DELIVERED",
            "FAILED", "CANCELLED");

    private final BusinessApi api;

    public DeliveryTools(BusinessApi api) {
        this.api = api;
    }

    public List<ToolSpec> tools() {
        return List.of(
                // partner
                ToolSpec.run("getActiveDelivery", "Checked the active delivery",
                        "Delivery partners: your current delivery (pickup, drop address, status), if any, with the "
                                + "next stop and its straight-line distance from your last reported position.",
                        RiskClass.READ, PARTNER, ArgSchema.none(), this::getActiveDelivery),
                ToolSpec.run("getMyOffers", "Checked offers",
                        "Delivery partners: your pending delivery offers (store, drop area, distances, expiry).",
                        RiskClass.READ, PARTNER, ArgSchema.none(), this::getMyOffers),
                ToolSpec.run("getMyHistory", "Read delivery history",
                        "Delivery partners: your recent finished deliveries (up to 10, newest first).", RiskClass.READ,
                        PARTNER, ArgSchema.none(), this::getMyHistory),
                ToolSpec.run("getMySummary", "Read delivery summary",
                        "Delivery partners: deliveries done today and this week, failed this week.", RiskClass.READ,
                        PARTNER, ArgSchema.none(), this::getMySummary),
                // manager
                ToolSpec.run("listDeliveries", "Listed deliveries",
                        "Delivery managers: deliveries (up to 20, newest first) with their partner, optionally only "
                                + "a status or only those where no partner was found.", RiskClass.READ, MANAGER,
                        ArgSchema.builder()
                                .enumeration("status", DELIVERY_STATUSES, "Delivery status")
                                .bool("assignmentFailed", "Only deliveries for which no partner was found")
                                .build(),
                        this::listDeliveries),
                ToolSpec.run("getDelivery", "Looked up a delivery",
                        "Delivery managers: one delivery by its order number, with the offers sent.", RiskClass.READ,
                        MANAGER,
                        ArgSchema.builder().orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M").build(),
                        this::getDelivery),
                ToolSpec.propose("reassignDelivery", "Prepared a reassignment",
                        "Delivery managers: prepare reassigning a SEARCHING or ASSIGNED delivery, to a given online "
                                + "partner or (without partnerId) to a new search excluding the current partner. It "
                                + "does NOT reassign: a delivery manager or operations admin approves it.", MANAGER,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .uuid("partnerId", false, "The partner to assign (leave out to search again)")
                                .string("reason", false, 1, 300, "Why the delivery is reassigned")
                                .rationale()
                                .build(),
                        ApprovalAction.REASSIGN_DELIVERY));
    }

    // ---- partner ------------------------------------------------------------------------------------------------

    private ToolResult getActiveDelivery(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/delivery/deliveries/active"), ok -> {
            ObjectNode result = Results.object();
            if (ok.body() == null || !ok.body().isObject() || Json.text(ok.body(), "deliveryId") == null) {
                result.put("active", false);
                return Results.ok(result, "No active delivery", ok, Pages.myDeliveries());
            }
            result.put("active", true);
            ObjectNode delivery = delivery(ok.body());
            // Navigation help without coordinates: the next stop and its straight-line distance (NextStop). For
            // turn-by-turn directions the agent's prompt points to the app's "Navigate" button.
            NextStop next = NextStop.of(ok.body(), lastPosition(call));
            delivery.put("nextStop", next.type());
            if (next.distanceKm() != null) {
                delivery.put("distanceToNextStopKm", next.distanceKm());
            }
            result.set("delivery", delivery);
            return Results.ok(result, "Order " + Json.text(ok.body(), "orderNumber") + " is "
                    + Json.text(ok.body(), "status"), ok, Pages.myDeliveries());
        });
    }

    /** The partner's last reported position (the {@code location} of their own status), or null when unknown. */
    private JsonNode lastPosition(ToolCallContext call) {
        return api.get(call.http(), "/api/delivery/partners/me/status") instanceof BusinessApi.Fetch.Ok ok
                ? Json.at(ok.body(), "location") : null;
    }

    /**
     * A DeliveryView trimmed for the model: what to pick up where and the drop address; the recipient's phone and
     * coordinates stay in the app.
     */
    static ObjectNode delivery(JsonNode view) {
        ObjectNode delivery = Results.pick(view, "orderNumber", "orderType", "status", "readyForPickup", "readyBy",
                "note", "itemCount", "total", "assignedAt", "pickedUpAt");
        JsonNode pickup = Json.at(view, "pickup");
        if (pickup != null) {
            delivery.set("pickup", Results.pick(pickup, "name", "city"));
        }
        JsonNode drop = Json.at(view, "drop");
        if (drop != null) {
            delivery.set("drop", Results.pick(drop, "recipientName", "line1", "line2", "landmark", "city",
                    "postalCode"));
        }
        return delivery;
    }

    private ToolResult getMyOffers(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/delivery/offers"), ok -> {
            ArrayNode offers = Results.array();
            for (JsonNode offer : StoreTools.entries(ok.body())) {
                ObjectNode trimmed = Results.pick(offer, "orderNumber", "orderType", "dropArea", "distanceKm",
                        "tripKm", "itemCount", "expiresAt");
                Results.put(trimmed, "store", Json.text(offer, "store.name"));
                offers.add(trimmed);
            }
            ObjectNode result = Results.object();
            result.set("offers", offers);
            return Results.ok(result, offers.isEmpty() ? "No pending offers"
                    : offers.size() + (offers.size() == 1 ? " pending offer" : " pending offers"), ok,
                    Pages.myDeliveries());
        });
    }

    private ToolResult getMyHistory(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/delivery/history", Map.of("size", 10)), ok -> {
            ArrayNode deliveries = Results.array();
            for (JsonNode entry : Json.list(ok.body(), "content")) {
                deliveries.add(Results.pick(entry, "orderNumber", "status", "store", "dropArea", "tripKm",
                        "finishedAt"));
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    deliveries.size()));
            result.set("deliveries", deliveries);
            return Results.ok(result, deliveries.isEmpty() ? "No finished deliveries yet"
                    : deliveries.size() + " recent deliveries", ok, Pages.deliveryHistory());
        });
    }

    private ToolResult getMySummary(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/delivery/history/summary"), ok -> {
            ObjectNode result = Results.object();
            result.set("summary", Results.pick(ok.body(), "deliveredToday", "deliveredThisWeek", "failedThisWeek"));
            return Results.ok(result, Objects.requireNonNullElse(Json.integer(ok.body(), "deliveredToday"), 0)
                    + " delivered today, " + Objects.requireNonNullElse(Json.integer(ok.body(), "deliveredThisWeek"),
                    0) + " this week", ok, Pages.deliveryHistory());
        });
    }

    // ---- manager ------------------------------------------------------------------------------------------------

    private ToolResult listDeliveries(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("assignmentFailed", call.args().bool("assignmentFailed"));
        params.put("size", 20);
        return Results.from(api.get(call.http(), "/api/admin/deliveries", params), ok -> {
            ArrayNode deliveries = Results.array();
            int failed = 0;
            for (JsonNode view : Json.list(ok.body(), "content")) {
                deliveries.add(adminDelivery(view));
                if (Json.bool(view, "assignmentFailed")) {
                    failed++;
                }
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    deliveries.size()));
            result.set("deliveries", deliveries);
            return Results.ok(result, (deliveries.isEmpty() ? "No deliveries" : deliveries.size()
                    + (deliveries.size() == 1 ? " delivery" : " deliveries"))
                    + (failed > 0 ? " (" + failed + " without a partner)" : ""), ok,
                    Pages.deliveryMonitor(call.args().string("status"), call.args().isTrue("assignmentFailed")));
        });
    }

    private ToolResult getDelivery(ToolCallContext call) {
        String number = call.args().string("orderNumber");
        return Results.from(api.adminDelivery(call.http(), number), ok -> {
            ObjectNode delivery = adminDelivery(ok.body());
            ArrayNode offers = Results.array();
            for (JsonNode offer : Json.list(ok.body(), "offers")) {
                if (offers.size() < 10) {
                    offers.add(Results.pick(offer, "partnerId", "partnerName", "distanceKm", "status", "sentAt"));
                }
            }
            delivery.set("offers", offers);
            ObjectNode result = Results.object();
            result.set("delivery", delivery);
            return Results.ok(result, "Delivery of " + number + " is " + Json.text(ok.body(), "status"), ok,
                    Pages.delivery(number));
        });
    }

    /** An AdminDeliveryView trimmed for the model (no drop phone or exact address). */
    static ObjectNode adminDelivery(JsonNode view) {
        ObjectNode delivery = Results.pick(view, "orderNumber", "orderType", "status", "assignmentFailed",
                "offersSent", "partnerId", "partnerName", "readyForPickup", "assignedAt", "pickedUpAt");
        Results.put(delivery, "store", Json.text(view, "pickup.name"));
        Results.put(delivery, "dropCity", Json.text(view, "drop.city"));
        return delivery;
    }
}
