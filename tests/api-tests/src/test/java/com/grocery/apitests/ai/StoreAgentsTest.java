package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** The vendor and restaurant assistants (Phase 12 §6): read tools run, store changes wait for the owner's approval. */
@Tag("ai")
@Order(22)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI: vendor and restaurant assistants")
class StoreAgentsTest {

    World.Store store;
    World.Product atta;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        store = World.storeA();
        atta = World.createProduct(store.vendor(), "Apit Agent Atta " + Config.TAG, "120.00", "100.00", 12);
    }

    @Test
    void vendor_read_tools() {
        Chat.send(store.vendor(), "vendor", "low stock").streamed().tool("listInventory", "SUCCEEDED");
        Chat sales = Chat.send(store.vendor(), "vendor", "sales").streamed().tool("getSalesReport", "SUCCEEDED");
        assertThat(sales.answer()).contains("orders received");
        for (String tool : List.of("listSuppliers", "listPurchaseOrders", "listReturns", "listReviews", "listStoreOrders")) {
            Chat.send(store.vendor(), "vendor", "use tool " + tool + " {}").streamed().tool(tool, "SUCCEEDED");
        }
        Chat.send(store.vendor(), "vendor", "use tool getStock {\"productId\":\"" + atta.id() + "\"}").streamed()
                .tool("getStock", "SUCCEEDED");
    }

    @Test
    void a_stock_change_waits_for_the_vendors_approval() {
        Chat chat = Chat.send(store.vendor(), "vendor", "set stock " + atta.name() + " to 30").streamed()
                .tool("listInventory", "SUCCEEDED").tool("adjustStock", "APPROVAL_REQUIRED");
        JsonPath approval = chat.approval();
        assertThat(approval.getString("action")).isEqualTo("ADJUST_STOCK");
        assertThat(approval.getString("approverUserId")).isEqualTo(store.vendor().userId());
        Api.as(store.vendor()).get("/api/vendor/inventory/" + atta.id()).ok().has("onHand", 12);
        Api.as(World.adminWithRole("SUPER_ADMIN")).body(obj()).post("/api/approvals/" + approval.getString("id") + "/approve")
                .problem(404, "APPROVAL_NOT_FOUND");
        Api.as(store.vendor()).body(obj()).post("/api/approvals/" + approval.getString("id") + "/approve").ok()
                .has("status", "COMPLETED");
        Api.as(store.vendor()).get("/api/vendor/inventory/" + atta.id()).ok().has("onHand", 30);
    }

    @Test
    void a_price_change_waits_for_the_vendors_approval() {
        Chat chat = Chat.send(store.vendor(), "vendor", "price " + atta.name() + " 95").streamed()
                .tool("listProducts", "SUCCEEDED").tool("changePrice", "APPROVAL_REQUIRED");
        String id = chat.approvalId();
        assertThat(chat.approval().getString("action")).isEqualTo("CHANGE_PRICE");
        Api.as(store.vendor()).get("/api/vendor/prices/" + atta.id()).ok().has("price.sellingPrice", new BigDecimal("100.00"));
        Api.as(store.vendor()).body(obj()).post("/api/approvals/" + id + "/approve").ok().has("status", "COMPLETED");
        Api.as(store.vendor()).get("/api/vendor/prices/" + atta.id()).ok().has("price.sellingPrice", new BigDecimal("95.00"));
    }

    @Test
    void restaurant_tools_and_approved_store_actions() {
        World.Kitchen kitchen = World.kitchen();
        Chat.send(kitchen.owner(), "restaurant", "orders").streamed().tool("getOrderQueue", "SUCCEEDED");
        Chat.send(kitchen.owner(), "restaurant", "sales").streamed().tool("getSalesReport", "SUCCEEDED");
        Chat.send(kitchen.owner(), "restaurant", "use tool getMenu {}").streamed().tool("getMenu", "SUCCEEDED");

        Chat soldOut = Chat.send(kitchen.owner(), "restaurant", "mark " + kitchen.agentDishName() + " sold out").streamed()
                .tool("getMenu", "SUCCEEDED").tool("setMenuItemAvailability", "APPROVAL_REQUIRED");
        assertThat(soldOut.approval().getString("action")).isEqualTo("SET_MENU_ITEM_AVAILABILITY");
        assertThat(dish(kitchen, kitchen.agentDishId()).get("available")).isEqualTo(true);
        Api.as(kitchen.owner()).body(obj()).post("/api/approvals/" + soldOut.approvalId() + "/approve").ok()
                .has("status", "COMPLETED");
        assertThat(dish(kitchen, kitchen.agentDishId()).get("available")).isEqualTo(false);

        World.Customer diner = World.newCustomer("AgentDiner");
        Api.as(diner.session()).body(obj("type", "MENU_ITEM", "restaurantId", kitchen.restaurantId(),
                "menuItemId", kitchen.dosaId(), "quantity", 1, "replaceCart", true)).post("/api/cart/items").ok();
        World.Order order = World.place(diner, World.key("agent-accept"));
        World.pay(diner, order.id());
        World.awaitStoreOrder(kitchen.owner(), "/api/restaurant/orders", order.id(), "NEW");
        Chat accept = Chat.send(kitchen.owner(), "restaurant", "accept " + order.number() + " in 25 min").streamed()
                .tool("acceptOrder", "APPROVAL_REQUIRED");
        assertThat(accept.approval().getString("action")).isEqualTo("ACCEPT_STORE_ORDER");
        Api.as(kitchen.owner()).get("/api/restaurant/orders/" + order.id()).ok().has("status", "NEW");
        Api.as(kitchen.owner()).body(obj()).post("/api/approvals/" + accept.approvalId() + "/approve").ok()
                .has("status", "COMPLETED");
        Api.as(kitchen.owner()).get("/api/restaurant/orders/" + order.id()).ok().has("status", "ACCEPTED")
                .has("readyInMinutes", 25);
    }

    private static Map<String, Object> dish(World.Kitchen kitchen, String id) {
        Res menu = Api.as(kitchen.owner()).get("/api/restaurant/menu").ok();
        for (Map<String, Object> category : menu.<Map<String, Object>>list("categories")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) category.get("items");
            for (Map<String, Object> item : items) {
                if (id.equals(item.get("id"))) {
                    return item;
                }
            }
        }
        throw new AssertionError("dish " + id + " not on the menu: " + menu.describe());
    }
}
