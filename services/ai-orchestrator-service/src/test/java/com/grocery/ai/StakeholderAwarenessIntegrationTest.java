package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistants know who is signed in and answer from that user's own, authorised data: the admin's partner
 * directories (vendors, restaurants, delivery partners, customers), each role's server-built context line, only the
 * tools the role may use, and natural requests (a category by name, "orders today", "assigned to me").
 */
class StakeholderAwarenessIntegrationTest extends AbstractIntegrationTest {

    private static final Map<String, Integer> VENDOR_COUNTS = Map.of("APPROVED", 112, "PENDING_APPROVAL", 5,
            "SUSPENDED", 23, "REJECTED", 0, "DRAFT", 3);

    private static String page(int total, String content) {
        return "{\"content\":" + content + ",\"page\":0,\"size\":10,\"totalElements\":" + total
                + ",\"totalPages\":" + Math.max(1, (total + 9) / 10) + "}";
    }

    private static String lastToolResult(Prompt prompt) {
        return ((ToolResponseMessage) prompt.getInstructions().getLast()).getResponses().getLast().responseData();
    }

    private static Set<String> offeredTools(Prompt prompt) {
        List<ToolCallback> callbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).collect(Collectors.toSet());
    }

    private static List<String> links(Result result) {
        JsonNode links = result.named("tool").getLast().data().get("links");
        return links == null ? List.of() : links.valueStream().map(link -> link.get("path").asString()).toList();
    }

    // ---- the reported problem: "How many vendors have been approved by the admin?" ----------------------------------

    @Test
    void aSuperAdminGetsTheRealVendorApprovalCountsReadWithTheirOwnToken() {
        UUID admin = UUID.randomUUID();
        String token = TOKENS.token(admin, "SUPER_ADMIN");
        GATEWAY.on("GET", "/api/admin/vendors", request -> FakeGateway.Response.json(200,
                page(VENDOR_COUNTS.get(request.param("status")), "[]")));

        Result result = chat(token, "admin-bi", "How many vendors have been approved by the admin?");

        assertThat(toolEvents(result)).containsExactly("getStakeholderCounts:STARTED",
                "getStakeholderCounts:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("Vendors: 112 approved, 5 waiting for approval, 23 suspended, "
                + "0 rejected, 3 not submitted yet (143 in all).");
        assertThat(result.named("tool").getLast().text("summary")).isEqualTo("112 approved vendors (5 pending)");
        // One count per status, as the admin (the vendor service applies the admin's own rights).
        List<FakeGateway.Request> calls = GATEWAY.requests("GET", "/api/admin/vendors");
        assertThat(calls).extracting(r -> r.param("status")).containsExactly("APPROVED", "PENDING_APPROVAL",
                "SUSPENDED", "REJECTED", "DRAFT");
        assertThat(calls).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + token));
        // The model saw the exact numbers and what the statuses mean.
        JsonNode forModel = json.readTree(lastToolResult(model.prompts().getLast()));
        assertThat(forModel.at("/vendors/byStatus/APPROVED").asLong()).isEqualTo(112);
        assertThat(forModel.at("/vendors/total").asLong()).isEqualTo(143);
        assertThat(forModel.get("statusMeaning").asString()).contains("APPROVED = approved by an admin");
        // Links to the vendor directory and the approval queue.
        assertThat(links(result)).containsExactly("/admin/vendors", "/admin/approvals?type=vendors");
        assertThat(auditRows(admin)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tool", "getStakeholderCounts").containsEntry("status", "SUCCEEDED"));
    }

    @Test
    void withoutATypeEveryDirectoryAndTheCustomersAreCounted() {
        UUID admin = UUID.randomUUID();
        GATEWAY.on("GET", "/api/admin/(vendors|restaurants|delivery-partners)", request -> FakeGateway.Response.json(
                200, page("APPROVED".equals(request.param("status")) ? 4 : 1, "[]")));
        GATEWAY.json("GET", "/api/admin/customers", 200, page(250, "[]"));

        Result result = chat(TOKENS.token(admin, "OPS_ADMIN"), "admin-bi", "how many customers do we have");

        assertThat(result.answer()).isEqualTo("Vendors: 4 approved, 1 waiting for approval, 1 suspended, 1 rejected, "
                + "1 not submitted yet (8 in all). Restaurants: 4 approved, 1 waiting for approval, 1 suspended, "
                + "1 rejected, 1 not submitted yet (8 in all). Delivery partners: 4 approved, 1 waiting for approval, "
                + "1 suspended, 1 rejected, 1 not submitted yet (8 in all). Customers: 250.");
        assertThat(GATEWAY.requests("GET", "/api/admin/.*")).hasSize(16);
    }

    @Test
    void pendingApprovalsAreListedWithoutContactDetailsForTheModel() {
        UUID admin = UUID.randomUUID();
        GATEWAY.json("GET", "/api/admin/vendors", 200, page(2, """
                [{"id":"01a1dac2-a4c2-7442-a141-fe36cf4ffaa1","status":"PENDING_APPROVAL","businessName":"Fresh Mart",
                  "contactEmail":"owner@freshmart.example","contactPhone":"+919800000001","gstin":"29ABCDE1234F1Z5",
                  "address":{"line1":"1 Main Road","city":"Pune"},"submittedAt":"2026-11-14T10:00:00Z"},
                 {"id":"01a1dac2-a4c2-7442-a141-fe36cf4ffaa2","status":"PENDING_APPROVAL","businessName":"Green Grocer",
                  "contactEmail":"hello@green.example","address":{"city":"Mysuru"},"submittedAt":"2026-11-14T11:00:00Z"}]
                """));

        Result result = chat(TOKENS.token(admin, "SUPER_ADMIN"), "admin-bi", "Show pending vendor approvals");

        assertThat(result.answer()).isEqualTo("2 vendors waiting for approval: Fresh Mart (Pune), Green Grocer (Mysuru).");
        assertThat(GATEWAY.requests("GET", "/api/admin/vendors").getFirst().param("status"))
                .isEqualTo("PENDING_APPROVAL");
        String forModel = lastToolResult(model.prompts().getLast());
        // when they applied, in India time (the API's 10:00 UTC)
        assertThat(forModel).contains("\"submittedAt\":\"14 Nov, 3:30 pm IST\"").doesNotContain("2026-11-14T10:00:00Z");
        assertThat(forModel).contains("01a1dac2-a4c2-7442-a141-fe36cf4ffaa1").contains("\"city\":\"Pune\"")
                .doesNotContain("owner@freshmart.example").doesNotContain("+919800000001")
                .doesNotContain("29ABCDE1234F1Z5").doesNotContain("1 Main Road");
        assertThat(links(result)).containsExactly("/admin/approvals?type=vendors",
                "/admin/vendors?status=PENDING_APPROVAL");
    }

    @Test
    void aRefusedDirectoryIsReportedNotInvented() {
        UUID admin = UUID.randomUUID();
        GATEWAY.on("GET", "/api/admin/vendors", request -> FakeGateway.Response.problem(503, "SERVICE_UNAVAILABLE",
                "Vendor service is down"));

        Result result = chat(TOKENS.token(admin, "SUPER_ADMIN"), "admin-bi", "how many vendors are approved");

        assertThat(toolEvents(result)).containsExactly("getStakeholderCounts:STARTED", "getStakeholderCounts:FAILED");
        assertThat(result.answer()).startsWith("That didn't work: Vendor service is down");
    }

    @Test
    void otherRolesCannotReachThePartnerDirectories() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool getStakeholderCounts {}");

        assertThat(result.named("tool").getLast().text("status")).isEqualTo("BLOCKED");
        assertThat(auditRows(customer)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tool", "getStakeholderCounts").containsEntry("reason", "TOOL_NOT_ALLOWED"));
        assertThat(GATEWAY.requests("GET", "/api/admin/.*")).isEmpty();
        // A vendor asking the admin assistant is refused before anything runs.
        assertThat(chat(TOKENS.token(UUID.randomUUID(), "VENDOR"), "admin-bi", "how many vendors").status())
                .isEqualTo(403);
    }

    // ---- each role gets only its own tools and its own context ----------------------------------------------------

    @Test
    void theModelIsOfferedOnlyTheToolsTheRoleMayUse() {
        GATEWAY.json("GET", "/api/admin/stats", 200, "{\"date\":\"2026-11-15\",\"orders\":0}");

        chat(TOKENS.token(UUID.randomUUID(), "SUPPORT"), "admin-bi", "stats");
        Set<String> support = offeredTools(model.prompts().getLast());
        chat(TOKENS.token(UUID.randomUUID(), "SUPER_ADMIN"), "admin-bi", "stats");
        Set<String> superAdmin = offeredTools(model.prompts().getLast());
        chat(TOKENS.token(UUID.randomUUID(), "DELIVERY_PARTNER"), "delivery", "hello");
        Set<String> partner = offeredTools(model.prompts().getLast());

        assertThat(support).contains("getStakeholderCounts", "listStakeholders", "refundOrder")
                .doesNotContain("suspendVendor", "suspendRestaurant");
        assertThat(superAdmin).contains("getStakeholderCounts", "suspendVendor", "suspendRestaurant");
        assertThat(partner).contains("getActiveDelivery", "getMyOffers")
                .doesNotContain("listDeliveries", "reassignDelivery");
    }

    @Test
    void eachRoleSeesWhoTheyAreAndTheirOwnBusinessInTheContext() {
        GATEWAY.json("GET", "/api/vendors/me", 200,
                "{\"businessName\":\"FreshCart Store\",\"status\":\"APPROVED\",\"contactPhone\":\"+919876543210\"}");
        chat(TOKENS.token(UUID.randomUUID(), "VENDOR"), "vendor", "hello");
        String vendor = TestModels.systemText(model.prompts().getLast());
        assertThat(vendor).contains("user role VENDOR (a grocery vendor: sees only their own store's")
                .contains("store: FreshCart Store (status APPROVED)").doesNotContain("+919876543210")
                .contains("never reveal other people's private details")
                .contains("never ask for their role, id or account");

        GATEWAY.json("GET", "/api/vendors/me", 200, "{\"businessName\":\"New Shop\",\"status\":\"PENDING_APPROVAL\"}");
        chat(TOKENS.token(UUID.randomUUID(), "VENDOR"), "vendor", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("store: New Shop (status PENDING_APPROVAL); the store is not approved");

        GATEWAY.json("GET", "/api/restaurants/me", 200,
                "{\"name\":\"Spice Route\",\"status\":\"APPROVED\",\"acceptingOrders\":false}");
        chat(TOKENS.token(UUID.randomUUID(), "RESTAURANT_OWNER"), "restaurant", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("restaurant: Spice Route (status APPROVED), not accepting orders now");

        GATEWAY.json("GET", "/api/delivery/partners/me", 200, "{\"fullName\":\"Ravi\",\"status\":\"APPROVED\"}");
        chat(TOKENS.token(UUID.randomUUID(), "DELIVERY_PARTNER"), "delivery", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("user role DELIVERY_PARTNER (a delivery partner: sees only their own deliveries")
                .contains("delivery partner profile status APPROVED");

        int before = GATEWAY.requests().size();
        chat(TOKENS.token(UUID.randomUUID(), "SUPER_ADMIN"), "admin-bi", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("user role SUPER_ADMIN (a super admin: sees the whole platform)")
                .contains("[Vendors](/admin/vendors)").contains("[Onboarding approvals](/admin/approvals)");
        assertThat(GATEWAY.requests()).hasSize(before);       // an admin's context needs no lookup
    }

    @Test
    void theContextCarriesALiveSnapshotSoAModelThatSkipsToolsStillAnswersFromRealData() {
        // A customer's latest order.
        UUID customer = UUID.randomUUID();
        String customerToken = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/orders", 200, "{\"content\":[{\"orderNumber\":\"GA-AB12CD34\","
                + "\"status\":\"OUT_FOR_DELIVERY\",\"total\":260.00,\"store\":{\"name\":\"Fresh Mart\"},"
                + "\"createdAt\":\"2026-11-15T04:00:00Z\"}],\"totalElements\":1,\"totalPages\":1}");
        model.use(TestModels.text("Your latest order GA-AB12CD34 is out for delivery."));
        Result fromSnapshot = chat(customerToken, "shopping", "Where is my latest order?");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("latest order GA-AB12CD34: OUT_FOR_DELIVERY, ₹260.00, from Fresh Mart");
        assertThat(GATEWAY.requests("GET", "/api/orders").getFirst().param("size")).isEqualTo("1");
        assertThat(fromSnapshot.named("notice")).isEmpty();
        // The live failure this guards against: an example order number from a tool description.
        model.use(TestModels.text("Your latest order GA-7K3F9Q2M is delivered."));
        assertThat(chat(customerToken, "shopping", "Where is my latest order?").first("notice").text("code"))
                .isEqualTo("UNVERIFIED_FIGURES");
        model.reset();

        // An approved delivery partner's active delivery and offers.
        GATEWAY.json("GET", "/api/delivery/partners/me", 200, "{\"status\":\"APPROVED\"}");
        GATEWAY.json("GET", "/api/delivery/deliveries/active", 200,
                "{\"deliveryId\":\"d1\",\"orderNumber\":\"GA-ZZ99YY88\",\"status\":\"PICKED_UP\"}");
        GATEWAY.json("GET", "/api/delivery/offers", 200, "[{\"orderNumber\":\"GA-1\"},{\"orderNumber\":\"GA-2\"}]");
        chat(TOKENS.token(UUID.randomUUID(), "DELIVERY_PARTNER"), "delivery", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("active delivery: order GA-ZZ99YY88, status PICKED_UP, pickup at the store, next stop the "
                        + "customer; 2 delivery offers waiting for an answer");

        // An approved vendor's orders today and low stock.
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        GATEWAY.json("GET", "/api/vendors/me", 200, "{\"businessName\":\"FreshCart Store\",\"status\":\"APPROVED\"}");
        GATEWAY.json("GET", "/api/vendor/reports/sales", 200, "{\"ordersReceived\":7,\"ordersAccepted\":6}");
        GATEWAY.json("GET", "/api/vendor/inventory", 200, "{\"content\":[],\"totalElements\":3}");
        chat(TOKENS.token(UUID.randomUUID(), "VENDOR"), "vendor", "hello");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("store: FreshCart Store (status APPROVED); today so far: 7 orders received, 6 accepted; "
                        + "3 products low on stock");
        FakeGateway.Request sales = GATEWAY.requests("GET", "/api/vendor/reports/sales").getFirst();
        assertThat(sales.param("from")).isEqualTo(today);
        assertThat(sales.param("to")).isEqualTo(today);
        assertThat(GATEWAY.requests("GET", "/api/vendor/inventory").getFirst().param("filter")).isEqualTo("LOW_STOCK");
    }

    @Test
    void aProfileThatCannotBeReadStillAnswers() {
        Result result = chat(TOKENS.token(UUID.randomUUID(), "VENDOR"), "vendor", "hello");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(TestModels.systemText(model.prompts().getLast()))
                .contains("the store profile is unavailable right now");
    }

    // ---- natural requests -------------------------------------------------------------------------------------------

    @Test
    void aVendorAskingForTodaysOrdersGetsTodaysSalesReport() {
        UUID vendor = UUID.randomUUID();
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        GATEWAY.json("GET", "/api/vendor/reports/sales", 200, "{\"from\":\"" + today + "\",\"to\":\"" + today
                + "\",\"ordersReceived\":7,\"ordersAccepted\":6,\"grossSales\":1890.00,\"averageOrderValue\":270.00}");

        Result result = chat(TOKENS.token(vendor, "VENDOR"), "vendor", "How many orders did I get today?");

        FakeGateway.Request call = GATEWAY.requests("GET", "/api/vendor/reports/sales").getFirst();
        assertThat(call.param("from")).isEqualTo(today);
        assertThat(call.param("to")).isEqualTo(today);
        assertThat(result.answer()).contains("7 orders received, 6 accepted");
    }

    @Test
    void aPartnerAskingWhatIsAssignedGetsTheirActiveDeliveryAndOffers() {
        GATEWAY.json("GET", "/api/delivery/deliveries/active", 204, "");
        GATEWAY.json("GET", "/api/delivery/offers", 200, "[{\"orderNumber\":\"GA-1\",\"store\":{\"name\":\"A\"}}]");

        Result result = chat(TOKENS.token(UUID.randomUUID(), "DELIVERY_PARTNER"), "delivery",
                "How many deliveries are assigned to me?");

        assertThat(toolEvents(result)).containsExactly("getActiveDelivery:STARTED", "getActiveDelivery:SUCCEEDED",
                "getMyOffers:STARTED", "getMyOffers:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("You have no active delivery right now. 1 offer is waiting for your "
                + "answer.");
    }

    @Test
    void aCategoryNamedLooselyBecomesTheSearchFilter() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        UUID fruits = UUID.randomUUID();
        GATEWAY.json("GET", "/api/catalog/categories", 200, "[{\"id\":\"" + UUID.randomUUID()
                + "\",\"name\":\"Vegetables\",\"productCount\":30,\"children\":[]},{\"id\":\"" + fruits
                + "\",\"name\":\"Fruits\",\"productCount\":12,\"children\":[]}]");
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Organic Apple", "180.00", true))));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "Find organic apples in the fruit category");

        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("q")).isEqualTo("organic apples");
        assertThat(search.param("categoryId")).isEqualTo(fruits.toString());
        assertThat(result.named("tool").getLast().text("summary")).endsWith(" in Fruits");
        assertThat(links(result)).singleElement().asString()
                .startsWith("/search?q=organic+apples&categoryId=" + fruits);
    }

    @Test
    void howManyCountsTheDifferentProductsNotEveryStoresListing() {
        // Live 2026-11-21: "Show me all available rice varieties" → "There are 30 rice varieties": 30 listings of
        // 7 products (Daawat Basmati in 7 stores), with products that only mention rice (Dog Food).
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        List<Map<String, Object>> cards = List.of(
                Fixtures.productCard(UUID.randomUUID(), "Red Rice", "105.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Brown Rice", "125.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Idli Rice", "54.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Ponni Rice", "62.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Daawat Basmati Rice", "120.00", true));
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.json(Fixtures.map("content", cards, "page", 0,
                "size", 5, "totalElements", 30, "totalPages", 6, "facets", Fixtures.map("categories", List.of(),
                        "brands", List.of(), "foodTypes", List.of()), "distinctProducts", 7)));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "find rice");

        assertThat(result.named("tool").getLast().text("summary")).isEqualTo("Found 7 products for “rice” (showing 5)");
        assertThat(result.answer()).startsWith("I found 7 products for “rice”. The first 5:");
        JsonNode forModel = json.readTree(lastToolResult(model.prompts().getLast()));
        assertThat(forModel.get("totalFound").asInt()).isEqualTo(7);
        assertThat(forModel.get("listings").asInt()).isEqualTo(30);
    }

    @Test
    void aCategoryThatDoesNotExistSearchesEveryCategory() {
        // Live 2026-11-21: "Show me all available rice varieties" became rice in "grains", a category nobody has; the
        // search failed and the assistant answered with a list of categories instead of the rice.
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/catalog/categories", 200, "[{\"id\":\"" + UUID.randomUUID()
                + "\",\"name\":\"Vegetables\",\"productCount\":30,\"children\":[]},{\"id\":\"" + UUID.randomUUID()
                + "\",\"name\":\"Rice and Rice Products\",\"productCount\":12,\"children\":[]}]");
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Ponni Rice", "62.00", true))));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "find rice in the grains category");

        assertThat(GATEWAY.requests("GET", "/api/search/products")).singleElement().satisfies(search -> {
            assertThat(search.param("q")).isEqualTo("rice");
            assertThat(search.param("categoryId")).isNull();
        });
        assertThat(result.named("tool").getLast().text("status")).isEqualTo("SUCCEEDED");
        assertThat(result.named("tool").getLast().text("summary")).isEqualTo("Found 1 product for “rice”");
        assertThat(result.answer()).contains("Ponni Rice");
        assertThat(links(result)).singleElement().asString().isEqualTo("/search?q=rice");
        // the model is told why the category was left out
        assertThat(json.readTree(lastToolResult(model.prompts().getLast())).get("note").asString())
                .isEqualTo("There is no category like \"grains\"; these are from every category.");
        assertThat(auditRows(customer)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tool", "searchProducts").containsEntry("status", "SUCCEEDED"));
    }

    @Test
    void nothingInTheCategoryTheModelPickedSearchesEveryCategory() {
        // Live: "also add a kg of tomatoes" became a search in Fruits, and the model said there were no tomatoes.
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        UUID fruits = UUID.randomUUID();
        GATEWAY.json("GET", "/api/catalog/categories", 200, "[{\"id\":\"" + UUID.randomUUID()
                + "\",\"name\":\"Vegetables\",\"productCount\":30,\"children\":[]},{\"id\":\"" + fruits
                + "\",\"name\":\"Fruits\",\"productCount\":12,\"children\":[]}]");
        GATEWAY.on("GET", "/api/search/products", request -> FakeGateway.Response.json(200,
                request.param("categoryId") != null ? Fixtures.searchResults(List.of())
                        : Fixtures.searchResults(List.of(Fixtures.productCard(UUID.randomUUID(), "Tomato", "40.00",
                        true)))));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "find tomatoes in the fruits category");

        List<FakeGateway.Request> searches = GATEWAY.requests("GET", "/api/search/products");
        assertThat(searches).hasSize(2);
        assertThat(searches.get(0).param("categoryId")).isEqualTo(fruits.toString());
        assertThat(searches.get(1).param("categoryId")).isNull();
        assertThat(searches.get(1).param("q")).isEqualTo("tomatoes");
        assertThat(result.named("tool").getLast().text("summary")).isEqualTo("Found 1 product for “tomatoes” (none in "
                + "Fruits)");
        assertThat(result.answer()).contains("Tomato");
        assertThat(links(result)).singleElement().asString().isEqualTo("/search?q=tomatoes");
    }
}
