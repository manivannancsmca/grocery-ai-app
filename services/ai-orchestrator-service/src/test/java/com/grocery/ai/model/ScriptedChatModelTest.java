package com.grocery.ai.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The scripted model's grammar (contract §7), row by row, through its ChatModel API (call and stream). */
class ScriptedChatModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SHOPPING = List.of("searchProducts", "getProduct", "searchRestaurants",
            "getRestaurantMenu", "getCart", "addToCart", "updateCartItem", "previewCheckout", "listMyOrders",
            "getOrderStatus", "placeOrder", "cancelOrder");
    private static final List<String> ADMIN = List.of("getOrderStats", "listOrders", "getOrder", "listStuckOrders",
            "listPayments", "getReconciliationSummary", "refundOrder", "cancelOrderAsAdmin", "suspendVendor",
            "suspendRestaurant");
    private static final List<String> VENDOR = List.of("getSalesReport", "listInventory", "getStock", "listProducts",
            "getPrice", "listStoreOrders", "listPurchaseOrders", "listSuppliers", "listReturns", "listReviews",
            "draftPurchaseOrder", "adjustStock", "changePrice", "sendPurchaseOrder");
    private static final List<String> RESTAURANT = List.of("getOrderQueue", "getSalesReport", "getMenu",
            "listReturns", "listReviews", "setMenuItemAvailability", "acceptOrder", "rejectOrder");
    private static final List<String> DELIVERY = List.of("getActiveDelivery", "getMyOffers", "getMyHistory",
            "getMySummary", "listDeliveries", "getDelivery", "reassignDelivery");

    private final ScriptedChatModel model = new ScriptedChatModel(JSON);

    /** A conversation turn: the user message, then the tool calls made and their results so far. */
    private final class Turn {
        private final List<String> tools;
        private final List<Message> messages = new ArrayList<>();

        Turn(List<String> tools, String userText) {
            this.tools = tools;
            messages.add(new SystemMessage("system"));
            messages.add(new UserMessage("an older question"));
            messages.add(new AssistantMessage("an older answer"));
            messages.add(new UserMessage(userText));
        }

        Prompt prompt() {
            List<ToolCallback> callbacks = tools.stream().map(ScriptedChatModelTest::callback).toList();
            return new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
        }

        /** The model's next tool call (asserting there is one). */
        AssistantMessage.ToolCall nextCall() {
            ChatResponse response = model.call(prompt());
            List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
            assertThat(calls).as("a tool call").hasSize(1);
            return calls.getFirst();
        }

        /** Answers the call with {@code result} (JSON). */
        Turn answer(AssistantMessage.ToolCall call, String result) {
            messages.add(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), result))).build());
            return this;
        }

        String text() {
            ChatResponse response = model.call(prompt());
            assertThat(response.getResult().getOutput().getToolCalls()).as("no more tool calls").isEmpty();
            return response.getResult().getOutput().getText();
        }
    }

    private static ToolCallback callback(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder().name(name).description(name)
                .inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static JsonNode args(AssistantMessage.ToolCall call) {
        return JSON.readTree(call.arguments());
    }

    // ---- shopping ---------------------------------------------------------------------------------------------------

    @Test
    void addSearchesInStockThenAddsTheFirstResult() {
        Turn turn = new Turn(SHOPPING, "Add 2 basmati rice.");
        AssistantMessage.ToolCall search = turn.nextCall();
        assertThat(search.name()).isEqualTo("searchProducts");
        assertThat(args(search).get("query").asString()).isEqualTo("basmati rice");
        assertThat(args(search).get("inStockOnly").asBoolean()).isTrue();

        turn.answer(search, "{\"query\":\"basmati rice\",\"products\":[{\"id\":\"p-1\",\"name\":\"Basmati Rice\","
                + "\"price\":180.00},{\"id\":\"p-2\",\"name\":\"Other\"}]}");
        AssistantMessage.ToolCall add = turn.nextCall();
        assertThat(add.name()).isEqualTo("addToCart");
        assertThat(args(add).get("productId").asString()).isEqualTo("p-1");
        assertThat(args(add).get("quantity").asInt()).isEqualTo(2);

        turn.answer(add, "{\"added\":{\"itemId\":\"p-1\",\"name\":\"Basmati Rice\",\"quantity\":2},\"cart\":{"
                + "\"store\":{\"name\":\"Fresh Mart\"},\"items\":[{\"name\":\"Basmati Rice\",\"quantity\":2}],"
                + "\"subtotal\":360.00}}");
        assertThat(turn.text()).isEqualTo("Added 2 × Basmati Rice to your cart. Your cart from Fresh Mart: "
                + "Basmati Rice × 2 — subtotal ₹360.00.");
    }

    @Test
    void addWithoutSearchResultsAddsNothing() {
        Turn turn = new Turn(SHOPPING, "add 1 unicorn milk");
        AssistantMessage.ToolCall search = turn.nextCall();
        turn.answer(search, "{\"query\":\"unicorn milk\",\"products\":[]}");
        assertThat(turn.text()).isEqualTo("I couldn't find any products for “unicorn milk”.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "search tomatoes|searchProducts|{\"query\":\"tomatoes\"}",
            "FIND Fresh Paneer|searchProducts|{\"query\":\"Fresh Paneer\"}",
            "how many products below 50 rupees ?|searchProducts|{\"maxPrice\":\"50\",\"sort\":\"PRICE_ASC\"}",
            "How many items are there under ₹99.50|searchProducts|{\"maxPrice\":\"99.50\",\"sort\":\"PRICE_ASC\"}",
            "show me all products less than rs. 30|searchProducts|{\"maxPrice\":\"30\",\"sort\":\"PRICE_ASC\"}",
            "Find paneer under ₹200|searchProducts|{\"query\":\"paneer\",\"maxPrice\":\"200\"}",
            "cart|getCart|{}",
            "Show my cart|getCart|{}",
            "status of GA-7K3F9Q2M|getOrderStatus|{\"orderNumber\":\"GA-7K3F9Q2M\"}",
            "Find organic apples in the fruits category|searchProducts|{\"query\":\"organic apples\",\"category\":\"fruits\"}",
            "Where is my latest order?|listMyOrders|{}",
            "use tool refundOrder {\"orderNumber\":\"GA-1\",\"note\":\"x\"}|refundOrder|{\"orderNumber\":\"GA-1\",\"note\":\"x\"}",
            "use tool makeCoffee|makeCoffee|{}"
    })
    void shoppingRowsMakeTheListedCall(String message, String tool, String arguments) {
        AssistantMessage.ToolCall call = new Turn(SHOPPING, message).nextCall();
        assertThat(call.name()).isEqualTo(tool);
        assertThat(args(call)).isEqualTo(JSON.readTree(arguments));
    }

    @Test
    void aPriceSearchAnswersWithEveryMatchNotJustTheListedOnes() {
        Turn turn = new Turn(SHOPPING, "how many products below 50 rupees?");
        turn.answer(turn.nextCall(), "{\"maxPrice\":50.00,\"totalFound\":21,\"listed\":2,\"products\":[{\"name\":"
                + "\"Salt\",\"unitLabel\":\"1 kg\",\"price\":28.00},{\"name\":\"Poha\",\"price\":45.00}]}");
        assertThat(turn.text()).isEqualTo("I found 21 products up to ₹50.00. The first 2: 1. Salt (1 kg) – ₹28.00; "
                + "2. Poha – ₹45.00.");

        Turn none = new Turn(SHOPPING, "find paneer under ₹20");
        none.answer(none.nextCall(), "{\"query\":\"paneer\",\"maxPrice\":20.00,\"totalFound\":0,\"products\":[]}");
        assertThat(none.text()).isEqualTo("I couldn't find any products for “paneer” up to ₹20.00.");
    }

    @Test
    void checkoutPreviewsThenProposesTheOrder() {
        for (String message : List.of("checkout", "Place my order!")) {
            Turn turn = new Turn(SHOPPING, message);
            AssistantMessage.ToolCall preview = turn.nextCall();
            assertThat(preview.name()).isEqualTo("previewCheckout");
            turn.answer(preview, "{\"preview\":{\"total\":285.00,\"canCheckout\":true}}");
            AssistantMessage.ToolCall place = turn.nextCall();
            assertThat(place.name()).isEqualTo("placeOrder");
            assertThat(args(place).get("rationale").asString()).isEqualTo("You asked me to check out.");
            turn.answer(place, "{\"status\":\"APPROVAL_REQUIRED\",\"approvalId\":\"a-1\",\"title\":\"Place order: "
                    + "3 items from Fresh Mart\",\"expiresAt\":\"2026-11-13T09:30:00Z\"}");
            assertThat(turn.text()).isEqualTo("Your order total is ₹285.00. I've prepared “Place order: 3 items from "
                    + "Fresh Mart”. Nothing happens until you confirm it in the app (it expires at 15:00).");
        }
    }

    @Test
    void checkoutStopsWhenThePreviewFails() {
        Turn turn = new Turn(SHOPPING, "checkout");
        AssistantMessage.ToolCall preview = turn.nextCall();
        turn.answer(preview, "{\"status\":\"ERROR\",\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"down\"}");
        assertThat(turn.text()).isEqualTo("That didn't work: down (SERVICE_UNAVAILABLE)");
    }

    @Test
    void cancelOrderProposesTheCancellation() {
        AssistantMessage.ToolCall call = new Turn(SHOPPING, "cancel order ga-7k3f9q2m").nextCall();
        assertThat(call.name()).isEqualTo("cancelOrder");
        assertThat(args(call).get("orderNumber").asString()).isEqualTo("ga-7k3f9q2m");
        assertThat(args(call).get("reason").asString()).isEqualTo("Requested through the assistant");
    }

    @Test
    void blockedAndErrorResultsAreExplained() {
        Turn turn = new Turn(SHOPPING, "use tool refundOrder {}");
        AssistantMessage.ToolCall call = turn.nextCall();
        turn.answer(call, "{\"status\":\"BLOCKED\",\"reason\":\"TOOL_NOT_ALLOWED\",\"message\":\"The tool "
                + "'refundOrder' is not available to the Shopping assistant.\"}");
        assertThat(turn.text()).isEqualTo("I can't do that: The tool 'refundOrder' is not available to the Shopping "
                + "assistant.");
    }

    @Test
    void anythingElseIsAHelpAnswerWithoutTools() {
        assertThat(new Turn(SHOPPING, "what is the meaning of life").text())
                .startsWith("I'm the scripted assistant").contains("“add <n> <product>”").contains("checkout");
        assertThat(new Turn(ADMIN, "add 2 rice").text())
                .startsWith("I'm the scripted assistant").contains("“stuck orders”");
    }

    // ---- admin-bi ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "stats|getOrderStats|{}",
            "Sales today|getOrderStats|{}",
            "stuck orders|listStuckOrders|{}",
            "refund 150.50 for GA-7K3F9Q2M because damaged items|refundOrder|{\"orderNumber\":\"GA-7K3F9Q2M\",\"amount\":\"150.50\",\"note\":\"damaged items\",\"rationale\":\"Requested in the admin assistant: damaged items\"}",
            "refund all for GA-7K3F9Q2M because wrong order|refundOrder|{\"orderNumber\":\"GA-7K3F9Q2M\",\"note\":\"wrong order\",\"rationale\":\"Requested in the admin assistant: wrong order\"}",
            "cancel GA-7K3F9Q2M because duplicate order|cancelOrderAsAdmin|{\"orderNumber\":\"GA-7K3F9Q2M\",\"reason\":\"duplicate order\"}",
            "suspend vendor 0192f000-0000-7000-8000-000000000001 because hygiene|suspendVendor|{\"vendorId\":\"0192f000-0000-7000-8000-000000000001\",\"reason\":\"hygiene\"}",
            "suspend restaurant 0192f000-0000-7000-8000-000000000002 because fraud|suspendRestaurant|{\"restaurantId\":\"0192f000-0000-7000-8000-000000000002\",\"reason\":\"fraud\"}",
            "How many vendors have been approved by the admin?|getStakeholderCounts|{\"type\":\"VENDOR\"}",
            "how many restaurants joined|getStakeholderCounts|{\"type\":\"RESTAURANT\"}",
            "How many customers do we have|getStakeholderCounts|{}",
            "Show pending vendor approvals|listStakeholders|{\"type\":\"VENDOR\",\"status\":\"PENDING_APPROVAL\"}",
            "list suspended restaurants|listStakeholders|{\"type\":\"RESTAURANT\",\"status\":\"SUSPENDED\"}",
            "delivery partners|listStakeholders|{\"type\":\"DELIVERY_PARTNER\"}",
            "use tool getCart {}|getCart|{}"
    })
    void adminRowsMakeTheListedCall(String message, String tool, String arguments) {
        AssistantMessage.ToolCall call = new Turn(ADMIN, message).nextCall();
        assertThat(call.name()).isEqualTo(tool);
        assertThat(args(call)).isEqualTo(JSON.readTree(arguments));
    }

    @Test
    void statsAndStuckOrdersAreSummarised() {
        Turn stats = new Turn(ADMIN, "stats");
        stats.answer(stats.nextCall(), "{\"stats\":{\"date\":\"2026-11-13\",\"orders\":42,\"confirmedRevenue\":"
                + "10250.00,\"averageOrderValue\":341.67}}");
        assertThat(stats.text()).isEqualTo("On 2026-11-13: 42 orders, confirmed revenue ₹10250.00, average order "
                + "value ₹341.67.");

        Turn stuck = new Turn(ADMIN, "stuck orders");
        stuck.answer(stuck.nextCall(), "{\"count\":1,\"stuckOrders\":[{\"orderNumber\":\"GA-1\",\"status\":"
                + "\"CONFIRMED\",\"reason\":\"STORE_RESPONSE_OVERDUE\",\"minutesOverdue\":12}]}");
        assertThat(stuck.text()).isEqualTo("1 stuck orders: GA-1 (CONFIRMED, STORE_RESPONSE_OVERDUE, 12 min overdue).");
    }

    // ---- shopping: returns (Phase 12 §6) ----------------------------------------------------------------------------

    @Test
    void returnProposesTheReturnWithTheReasonClassified() {
        AssistantMessage.ToolCall call = new Turn(SHOPPING, "Return 2 smart rice from GA-7K3F9Q2M because the bag "
                + "was torn.").nextCall();
        assertThat(call.name()).isEqualTo("requestReturn");
        assertThat(args(call)).isEqualTo(JSON.readTree("{\"orderNumber\":\"GA-7K3F9Q2M\",\"items\":[{\"name\":"
                + "\"smart rice\",\"quantity\":2}],\"reason\":\"DAMAGED\",\"note\":\"the bag was torn\","
                + "\"rationale\":\"You asked me to return 2 smart rice from GA-7K3F9Q2M.\"}"));

        Turn turn = new Turn(SHOPPING, "return 1 dal from GA-1 because it never arrived");
        AssistantMessage.ToolCall missing = turn.nextCall();
        assertThat(args(missing).get("reason").asString()).isEqualTo("MISSING");
        turn.answer(missing, "{\"status\":\"APPROVAL_REQUIRED\",\"approvalId\":\"a-1\",\"title\":\"Return 1 item from "
                + "order GA-1\",\"expiresAt\":\"2026-11-13T09:30:00Z\"}");
        assertThat(turn.text()).isEqualTo("I've prepared “Return 1 item from order GA-1”. Nothing happens until you "
                + "confirm it in the app (it expires at 15:00).");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "it was damaged|DAMAGED",
            "Broken eggs|DAMAGED",
            "wrong brand|WRONG_ITEM",
            "it never arrived|MISSING",
            "stale bread|QUALITY",
            "rotten tomatoes|QUALITY",
            "changed my mind|OTHER"
    })
    void returnReasonsAreClassifiedLikeAModelWould(String because, String reason) {
        assertThat(ScriptedChatModel.returnReason(because)).isEqualTo(reason);
    }

    // ---- vendor -----------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "low stock|listInventory|{\"filter\":\"LOW_STOCK\"}",
            "Low stock.|listInventory|{\"filter\":\"LOW_STOCK\"}",
            "Which products are running low?|listInventory|{\"filter\":\"LOW_STOCK\"}",
            "sales|getSalesReport|{}",
            "Sales report|getSalesReport|{}",
            "set stock tomato to 25|listInventory|{\"query\":\"tomato\"}",
            "price smart rice 49.50|listProducts|{\"query\":\"smart rice\"}",
            "use tool getStock {\"productId\":\"x\"}|getStock|{\"productId\":\"x\"}"
    })
    void vendorRowsMakeTheListedCall(String message, String tool, String arguments) {
        AssistantMessage.ToolCall call = new Turn(VENDOR, message).nextCall();
        assertThat(call.name()).isEqualTo(tool);
        assertThat(args(call)).isEqualTo(JSON.readTree(arguments));
    }

    @Test
    void setStockFindsTheProductThenProposesTheCount() {
        Turn turn = new Turn(VENDOR, "Set stock of tomato to 25");
        AssistantMessage.ToolCall lookup = turn.nextCall();
        turn.answer(lookup, "{\"query\":\"tomato\",\"products\":[{\"productId\":\"p-1\",\"productName\":\"Tomato\","
                + "\"available\":10}]}");
        AssistantMessage.ToolCall adjust = turn.nextCall();
        assertThat(adjust.name()).isEqualTo("adjustStock");
        assertThat(args(adjust)).isEqualTo(JSON.readTree("{\"productId\":\"p-1\",\"type\":\"SET\",\"quantity\":25,"
                + "\"reason\":\"STOCK_COUNT\",\"rationale\":\"You asked me to set the stock of Tomato to 25.\"}"));
        turn.answer(adjust, "{\"status\":\"APPROVAL_REQUIRED\",\"approvalId\":\"a-1\",\"title\":\"Set stock of Tomato "
                + "(500 g) to 25\",\"expiresAt\":\"2026-11-13T09:30:00Z\"}");
        assertThat(turn.text()).isEqualTo("I've prepared “Set stock of Tomato (500 g) to 25”. Nothing happens until "
                + "you approve it in the app (it expires at 15:00).");

        Turn none = new Turn(VENDOR, "set stock unicorn to 3");
        none.answer(none.nextCall(), "{\"query\":\"unicorn\",\"products\":[]}");
        assertThat(none.text()).isEqualTo("I couldn't find any products matching “unicorn”.");
    }

    @Test
    void priceFindsTheProductThenProposesThePrice() {
        Turn turn = new Turn(VENDOR, "price tomato ₹45");
        AssistantMessage.ToolCall lookup = turn.nextCall();
        assertThat(lookup.name()).isEqualTo("listProducts");
        turn.answer(lookup, "{\"query\":\"tomato\",\"products\":[{\"id\":\"p-1\",\"name\":\"Tomato\"}]}");
        AssistantMessage.ToolCall change = turn.nextCall();
        assertThat(change.name()).isEqualTo("changePrice");
        assertThat(args(change)).isEqualTo(JSON.readTree("{\"productId\":\"p-1\",\"sellingPrice\":\"45\","
                + "\"rationale\":\"You asked me to change the price of Tomato to ₹45.\"}"));
        turn.answer(change, "{\"status\":\"APPROVAL_REQUIRED\",\"approvalId\":\"a-1\",\"title\":\"Change price of "
                + "Tomato (500 g) to ₹45.00\",\"expiresAt\":\"2026-11-13T09:30:00Z\"}");
        assertThat(turn.text()).isEqualTo("I've prepared “Change price of Tomato (500 g) to ₹45.00”. Nothing happens "
                + "until you approve it in the app (it expires at 15:00). A change above the price threshold then "
                + "still waits for an admin.");
    }

    @Test
    void lowStockAndSalesAreSummarised() {
        Turn low = new Turn(VENDOR, "low stock");
        low.answer(low.nextCall(), "{\"filter\":\"LOW_STOCK\",\"products\":[{\"productName\":\"Tomato\",\"unitLabel\":"
                + "\"500 g\",\"available\":2,\"lowStockThreshold\":5},{\"productName\":\"Onion\",\"available\":0,"
                + "\"lowStockThreshold\":0}]}");
        assertThat(low.text()).isEqualTo("Low stock: Tomato (500 g) – 2 available (alert at 5); Onion – 0 available.");
        Turn none = new Turn(VENDOR, "low stock");
        none.answer(none.nextCall(), "{\"filter\":\"LOW_STOCK\",\"products\":[]}");
        assertThat(none.text()).isEqualTo("I couldn't find any products with low stock.");

        Turn sales = new Turn(VENDOR, "sales");
        sales.answer(sales.nextCall(), "{\"report\":{\"from\":\"2026-11-07\",\"to\":\"2026-11-13\",\"ordersReceived\":"
                + "12,\"ordersAccepted\":9,\"grossSales\":2340.00,\"averageOrderValue\":292.50}}");
        assertThat(sales.text()).isEqualTo("From 2026-11-07 to 2026-11-13: 12 orders received, 9 accepted, gross "
                + "sales ₹2340.00, average order value ₹292.50.");
    }

    // ---- restaurant -------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "orders|getOrderQueue|{}",
            "Order queue|getOrderQueue|{}",
            "sales|getSalesReport|{}",
            "mark paneer tikka sold out|getMenu|{}",
            "accept GA-7K3F9Q2M in 20 min|acceptOrder|{\"orderNumber\":\"GA-7K3F9Q2M\",\"readyInMinutes\":20,\"rationale\":\"You asked me to accept order GA-7K3F9Q2M with 20 minutes to prepare.\"}",
            "Accept order GA-1 in 15 minutes.|acceptOrder|{\"orderNumber\":\"GA-1\",\"readyInMinutes\":15,\"rationale\":\"You asked me to accept order GA-1 with 15 minutes to prepare.\"}"
    })
    void restaurantRowsMakeTheListedCall(String message, String tool, String arguments) {
        AssistantMessage.ToolCall call = new Turn(RESTAURANT, message).nextCall();
        assertThat(call.name()).isEqualTo(tool);
        assertThat(args(call)).isEqualTo(JSON.readTree(arguments));
    }

    @Test
    void markSoldOutFindsTheDishOnTheMenu() {
        String menu = "{\"menu\":{\"categories\":[{\"category\":\"Starters\",\"items\":[{\"id\":\"d-1\",\"name\":"
                + "\"Paneer Tikka Masala\",\"available\":true},{\"id\":\"d-2\",\"name\":\"Paneer Tikka\","
                + "\"available\":true}]}]}}";
        Turn turn = new Turn(RESTAURANT, "Mark Paneer Tikka sold out");
        turn.answer(turn.nextCall(), menu);
        AssistantMessage.ToolCall mark = turn.nextCall();
        assertThat(mark.name()).isEqualTo("setMenuItemAvailability");
        assertThat(args(mark)).isEqualTo(JSON.readTree("{\"menuItemId\":\"d-2\",\"available\":false,\"rationale\":"
                + "\"You asked me to mark Paneer Tikka sold out.\"}"));

        Turn partial = new Turn(RESTAURANT, "mark masala sold out");
        partial.answer(partial.nextCall(), menu);
        assertThat(args(partial.nextCall()).get("menuItemId").asString()).isEqualTo("d-1");

        Turn none = new Turn(RESTAURANT, "mark biryani sold out");
        none.answer(none.nextCall(), menu);
        assertThat(none.text()).isEqualTo("I couldn't find a dish matching “biryani” on your menu.");
    }

    @Test
    void theOrderQueueIsSummarised() {
        Turn turn = new Turn(RESTAURANT, "orders");
        turn.answer(turn.nextCall(), "{\"orders\":[{\"orderNumber\":\"GA-1\",\"status\":\"NEW\",\"itemCount\":2,"
                + "\"total\":520.00,\"acceptBy\":\"2026-11-13T09:05:00Z\"},{\"orderNumber\":\"GA-2\",\"status\":"
                + "\"PREPARING\",\"itemCount\":1,\"total\":199.00}]}");
        assertThat(turn.text()).isEqualTo("2 orders: GA-1 (NEW, 2 items, ₹520.00, accept by 14:35); GA-2 (PREPARING, "
                + "1 item, ₹199.00).");
        Turn empty = new Turn(RESTAURANT, "orders");
        empty.answer(empty.nextCall(), "{\"orders\":[]}");
        assertThat(empty.text()).isEqualTo("There are no orders right now.");
    }

    // ---- delivery ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "active delivery|getActiveDelivery|{}",
            "My active delivery|getActiveDelivery|{}",
            "deliveries|listDeliveries|{}",
            "How many deliveries are assigned to me?|getActiveDelivery|{}",
            "reassign GA-7K3F9Q2M|reassignDelivery|{\"orderNumber\":\"GA-7K3F9Q2M\",\"reason\":\"Requested through the assistant\",\"rationale\":\"You asked me to reassign the delivery of order GA-7K3F9Q2M.\"}"
    })
    void deliveryRowsMakeTheListedCall(String message, String tool, String arguments) {
        AssistantMessage.ToolCall call = new Turn(DELIVERY, message).nextCall();
        assertThat(call.name()).isEqualTo(tool);
        assertThat(args(call)).isEqualTo(JSON.readTree(arguments));
    }

    @Test
    void aReassignmentWaitsForAManagerOrOpsAdmin() {
        Turn turn = new Turn(DELIVERY, "reassign GA-1");
        turn.answer(turn.nextCall(), "{\"status\":\"APPROVAL_REQUIRED\",\"approvalId\":\"a-1\",\"title\":\"Reassign "
                + "order GA-1 (search again)\",\"expiresAt\":\"2026-11-14T09:00:00Z\"}");
        assertThat(turn.text()).isEqualTo("I've prepared “Reassign order GA-1 (search again)”. Nothing happens until "
                + "a delivery manager or operations admin approves it (it expires at 14:30).");

        Turn none = new Turn(DELIVERY, "active delivery");
        none.answer(none.nextCall(), "{\"active\":false}");
        assertThat(none.text()).isEqualTo("You have no active delivery right now.");
    }

    @Test
    void eachAgentHasItsOwnHelp() {
        assertThat(new Turn(VENDOR, "hello").text()).contains("“low stock”").contains("“set stock <product> to <n>”")
                .contains("“price <product> <amount>”").contains("“use tool <name> <json>”");
        assertThat(new Turn(RESTAURANT, "hello").text()).contains("“orders”").contains("“mark <dish> sold out”")
                .contains("“accept <ORDER-NUMBER> in <n> min”");
        assertThat(new Turn(DELIVERY, "hello").text()).contains("“active delivery”").contains("“deliveries”")
                .contains("“reassign <ORDER-NUMBER>”");
        assertThat(new Turn(SHOPPING, "hello").text())
                .contains("“return <n> <item> from <ORDER-NUMBER> because <reason>”");
        // another agent's phrase is not understood
        assertThat(new Turn(VENDOR, "orders").text()).startsWith("I'm the scripted assistant");
        assertThat(new Turn(DELIVERY, "low stock").text()).startsWith("I'm the scripted assistant");
    }

    // ---- streaming ----------------------------------------------------------------------------------------------

    @Test
    void streamingEmitsAToolCallOrTheAnswerWordByWord() {
        Turn turn = new Turn(SHOPPING, "show my cart");
        List<ChatResponse> call = model.stream(turn.prompt()).collectList().block();
        assertThat(call).hasSize(1);
        assertThat(call.getFirst().getResult().getOutput().getToolCalls()).extracting(AssistantMessage.ToolCall::name)
                .containsExactly("getCart");

        turn.answer(turn.nextCall(), "{\"cart\":{\"items\":[]}}");
        List<ChatResponse> chunks = model.stream(turn.prompt()).collectList().block();
        assertThat(chunks).hasSize(4);
        assertThat(Stream.of(chunks.toArray(ChatResponse[]::new)).map(c -> c.getResult().getOutput().getText())
                .reduce("", String::concat)).isEqualTo("Your cart is empty.");
        assertThat(chunks.getLast().getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(chunks.getFirst().getResult().getMetadata().getFinishReason()).isNull();
    }

    // ---- payment and support (docs/api/payment-and-support-agents.md) ------------------------------------------------

    private static final List<String> PAYMENT = List.of("listMyPayments", "getPaymentStatus", "cancelOrder");
    private static final List<String> SUPPORT = List.of("listMyOrders", "getOrderStatus", "trackOrder",
            "listMyReturns", "fileComplaint", "listMyComplaints", "cancelOrder", "requestReturn", "getPaymentStatus");

    @Test
    void theLatestPaymentIsLookedUpThenExplainedWithTheToolsOwnWords() {
        Turn turn = new Turn(PAYMENT, "Did my last payment go through?");
        AssistantMessage.ToolCall list = turn.nextCall();
        assertThat(list.name()).isEqualTo("listMyPayments");
        turn.answer(list, "{\"filter\":\"ALL\",\"count\":1,\"orders\":[{\"orderNumber\":\"GA-1\"}]}");
        AssistantMessage.ToolCall status = turn.nextCall();
        assertThat(status.name()).isEqualTo("getPaymentStatus");
        assertThat(args(status).get("orderNumber").asString()).isEqualTo("GA-1");
        turn.answer(status, "{\"situation\":\"PAID\",\"whatHappened\":\"You paid ₹260.00.\","
                + "\"nextStep\":\"Nothing to do for the payment.\"}");
        assertThat(turn.text()).isEqualTo("You paid ₹260.00. Nothing to do for the payment.");
    }

    @Test
    void supportRaisesAComplaintWithItsCategoryOrderAndTheCustomersWords() {
        AssistantMessage.ToolCall call = new Turn(SUPPORT, "complaint delivery about GA-1: The order came two hours "
                + "late.").nextCall();
        assertThat(call.name()).isEqualTo("fileComplaint");
        assertThat(args(call).get("category").asString()).isEqualTo("delivery");
        assertThat(args(call).get("orderNumber").asString()).isEqualTo("GA-1");
        assertThat(args(call).get("description").asString()).isEqualTo("The order came two hours late");
    }

    @Test
    void supportTracksTheLatestOrderAndSharesOnlyTheShoppingOrderRules() {
        Turn turn = new Turn(SUPPORT, "where is my order");
        AssistantMessage.ToolCall list = turn.nextCall();
        assertThat(list.name()).isEqualTo("listMyOrders");
        turn.answer(list, "{\"orders\":[{\"orderNumber\":\"GA-2\"}]}");
        AssistantMessage.ToolCall track = turn.nextCall();
        assertThat(track.name()).isEqualTo("trackOrder");
        assertThat(args(track).get("orderNumber").asString()).isEqualTo("GA-2");

        assertThat(new Turn(SUPPORT, "cancel order GA-1").nextCall().name()).isEqualTo("cancelOrder");
        assertThat(new Turn(PAYMENT, "cancel order GA-1").nextCall().name()).isEqualTo("cancelOrder");
        // the shopping assistant's search and cart rules are not the support assistant's
        assertThat(new Turn(SUPPORT, "search rice").text()).startsWith("I'm the scripted assistant");
    }
}
