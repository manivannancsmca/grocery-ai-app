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

import java.util.List;
import java.util.Set;

/**
 * The {@code restaurant} agent's tools (Phase 12 §6), all called as the restaurant owner through the gateway: the
 * order queue and sales (restaurant-service), the menu, returns ("report a problem") and reviews. Marking a dish sold
 * out (or available), accepting and rejecting an order are SENSITIVE: the owner approves them.
 */
@Component
public class RestaurantTools {

    static final Set<String> OWNER = Set.of(Roles.RESTAURANT_OWNER);
    static final String PREFIX = "/api/restaurant";
    /** Phase 8 §2 reject reasons. */
    public static final Set<String> REJECT_REASONS = Set.of("OUT_OF_STOCK", "STORE_CLOSED", "TOO_BUSY", "OTHER");
    static final int MENU_ITEMS_SHOWN = 80;

    private final BusinessApi api;

    public RestaurantTools(BusinessApi api) {
        this.api = api;
    }

    public List<ToolSpec> tools() {
        return List.of(
                ToolSpec.run("getOrderQueue", "Checked the order queue",
                        "The restaurant's orders (up to 20, new ones first; default the active ones: new, accepted, "
                                + "preparing, ready).", RiskClass.READ, OWNER,
                        ArgSchema.builder().enumeration("status", StoreTools.STORE_ORDER_STATUSES,
                                "A store status, or ACTIVE (default)").build(),
                        call -> StoreTools.storeOrders(api, call, PREFIX, true, Pages.restaurantOrders())),
                ToolSpec.run("getSalesReport", "Read the sales report",
                        "The restaurant's sales report: orders received, accepted, rejected, gross sales, average "
                                + "order value and top dishes (default the last 7 days).", RiskClass.READ, OWNER,
                        StoreTools.salesReportArgs(), call -> StoreTools.salesReport(api, call, PREFIX,
                                Pages.restaurantReports())),
                ToolSpec.run("getMenu", "Read the menu",
                        "The restaurant's menu: dishes with ids, prices and availability (sold out or not).",
                        RiskClass.READ, OWNER, ArgSchema.none(), this::getMenu),
                ToolSpec.run("listReturns", "Listed reported problems",
                        "Customers' return requests / reported problems for the restaurant (up to 20).",
                        RiskClass.READ, OWNER,
                        ArgSchema.builder().enumeration("status", StoreTools.RETURN_STATUSES, "Return status").build(),
                        call -> StoreTools.returns(api, call, Pages.restaurantReturns())),
                ToolSpec.run("listReviews", "Read reviews",
                        "Published customer reviews of the restaurant and its dishes (up to 20, newest first).",
                        RiskClass.READ, OWNER, ArgSchema.none(), call -> StoreTools.reviews(api, call,
                                Pages.restaurantReviews())),
                ToolSpec.propose("setMenuItemAvailability", "Prepared a menu change",
                        "Prepare marking a dish sold out (available=false) or available again (available=true). It "
                                + "does NOT change it: the owner approves it in the app.", OWNER,
                        ArgSchema.builder()
                                .uuid("menuItemId", true, "The dish id (from getMenu)")
                                .requiredBool("available", "false = sold out, true = available again")
                                .rationale()
                                .build(),
                        ApprovalAction.SET_MENU_ITEM_AVAILABILITY),
                ToolSpec.propose("acceptOrder", "Prepared accepting an order",
                        "Prepare accepting a NEW order with a preparation time. It does NOT accept it: the owner "
                                + "approves it in the app.", OWNER,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .integer("readyInMinutes", true, 5, 180, "Minutes until it is ready (5–180)")
                                .rationale()
                                .build(),
                        ApprovalAction.ACCEPT_STORE_ORDER),
                ToolSpec.propose("rejectOrder", "Prepared rejecting an order",
                        "Prepare rejecting a NEW order (the customer is refunded). It does NOT reject it: the owner "
                                + "approves it in the app.", OWNER,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .requiredEnumeration("reason", REJECT_REASONS,
                                        "OUT_OF_STOCK, STORE_CLOSED, TOO_BUSY or OTHER")
                                .string("note", false, 1, 300, "Details for the customer (required for OTHER)")
                                .rationale()
                                .check(values -> {
                                    if ("OTHER".equals(values.get("reason")) && !values.containsKey("note")) {
                                        throw new InvalidArgumentsException("note is required when reason is OTHER");
                                    }
                                })
                                .build(),
                        ApprovalAction.REJECT_STORE_ORDER));
    }

    private ToolResult getMenu(ToolCallContext call) {
        return Results.from(api.get(call.http(), PREFIX + "/menu"), ok -> {
            JsonNode body = ok.body();
            ArrayNode categories = Results.array();
            int dishes = 0;
            int soldOut = 0;
            for (JsonNode category : Json.list(body, "categories")) {
                ArrayNode items = Results.array();
                for (JsonNode item : Json.list(category, "items")) {
                    if (dishes++ < MENU_ITEMS_SHOWN) {
                        items.add(Results.pick(item, "id", "name", "price", "foodType", "available"));
                    }
                    if (!Json.bool(item, "available")) {
                        soldOut++;
                    }
                }
                ObjectNode section = Results.object();
                section.put("category", Json.text(category, "name"));
                section.set("items", items);
                categories.add(section);
            }
            ObjectNode menu = Results.pick(body, "restaurantStatus", "acceptingOrders");
            menu.set("categories", categories);
            ObjectNode result = Results.object();
            result.set("menu", menu);
            return Results.ok(result, "Menu: " + dishes + (dishes == 1 ? " dish" : " dishes")
                    + (soldOut > 0 ? ", " + soldOut + " sold out" : ""), ok, Pages.restaurantMenu());
        });
    }
}
