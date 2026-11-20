package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
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
 * The vendor assistant (Phase 12 §6): its READ tools call the vendor APIs as the vendor; draftPurchaseOrder runs
 * (LOW_RISK); stock and price changes and sending a purchase order are proposals with server-built parameters and
 * summaries, decided by the vendor alone, executed with a token for inventory-, pricing- or vendor-service.
 */
class VendorAssistantIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SUPPLIER = UUID.fromString("0192f000-0000-7000-8000-0000000000e1");

    private static UUID approvalId(Result result) {
        return UUID.fromString(result.first("approval").text("id"));
    }

    @Test
    void readToolsCallTheVendorApisAsTheVendor() {
        UUID vendor = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("GET", "/api/vendor/inventory", 200, Fixtures.page(List.of(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 4, 2, 5))));
        GATEWAY.json("GET", "/api/vendor/reports/sales", 200, Fixtures.salesReport());
        GATEWAY.json("GET", "/api/vendor/inventory/" + Fixtures.TOMATO, 200, Fixtures.json(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 4, 2, 5)));
        GATEWAY.json("GET", "/api/vendor/products", 200, Fixtures.page(List.of(Fixtures.map("id",
                Fixtures.TOMATO.toString(), "name", "Tomato", "unitLabel", "500 g", "status", "PUBLISHED",
                "category", Fixtures.map("name", "Vegetables")))));
        GATEWAY.json("GET", "/api/vendor/prices/" + Fixtures.TOMATO, 200, Fixtures.json(Fixtures.priceView(
                Fixtures.TOMATO, "Tomato", "60.00", "49.00", null)));
        GATEWAY.json("GET", "/api/vendor/orders", 200, Fixtures.page(List.of(
                Fixtures.storeOrder(UUID.randomUUID(), "GA-V0001", "NEW"))));
        GATEWAY.json("GET", "/api/vendor/purchase-orders", 200, Fixtures.page(List.of(
                Fixtures.purchaseOrder(UUID.randomUUID(), "PO-7K3M9QX2", "DRAFT", SUPPLIER))));
        GATEWAY.json("GET", "/api/vendor/suppliers", 200, Fixtures.json(List.of(Fixtures.map("id",
                SUPPLIER.toString(), "name", "Nashik Farms", "leadTimeDays", 2, "active", true))));
        GATEWAY.json("GET", "/api/store/returns", 200, Fixtures.page(List.of(Fixtures.map("returnNumber",
                "RT-7K3M9QX2", "orderNumber", "GA-V0001", "status", "REQUESTED", "reason", "DAMAGED",
                "refundAmount", 95.00, "items", List.of(Fixtures.map("name", "Tomato", "quantity", 1))))));
        GATEWAY.json("GET", "/api/store/reviews", 200, Fixtures.page(List.of(Fixtures.map("targetType",
                "PRODUCT", "targetName", "Tomato", "rating", 4, "title", "Fresh", "comment", "Nice and red",
                "authorName", "Asha R.", "reply", Fixtures.map("text", "Thank you!")))));

        Result lowStock = chat(token, "vendor", "low stock");
        assertThat(toolEvents(lowStock)).containsExactly("listInventory:STARTED", "listInventory:SUCCEEDED");
        assertThat(lowStock.answer()).isEqualTo("Low stock: Tomato (500 g) – 2 available (alert at 5).");
        FakeGateway.Request inventory = GATEWAY.requests("GET", "/api/vendor/inventory").getFirst();
        assertThat(inventory.param("filter")).isEqualTo("LOW_STOCK");
        assertThat(inventory.param("size")).isEqualTo("20");
        assertThat(inventory.param("q")).isNull();
        assertThat(inventory.header("Authorization")).isEqualTo("Bearer " + token);

        Result sales = chat(token, "vendor", "Sales");
        assertThat(sales.answer()).isEqualTo("From 2026-11-07 to 2026-11-13: 12 orders received, 9 accepted, gross "
                + "sales ₹2340.00, average order value ₹292.50.");
        assertThat(GATEWAY.requests("GET", "/api/vendor/reports/sales").getFirst().query()).isNull();
        chat(token, "vendor", "use tool getSalesReport {\"from\":\"2026-11-01\",\"to\":\"2026-11-07\"}");
        FakeGateway.Request ranged = GATEWAY.requests("GET", "/api/vendor/reports/sales").getLast();
        assertThat(ranged.param("from")).isEqualTo("2026-11-01");
        assertThat(ranged.param("to")).isEqualTo("2026-11-07");

        chat(token, "vendor", "use tool getStock {\"productId\":\"" + Fixtures.TOMATO + "\"}");
        chat(token, "vendor", "use tool listProducts {\"query\":\"tomato\"}");
        assertThat(GATEWAY.requests("GET", "/api/vendor/products").getFirst().param("q")).isEqualTo("tomato");
        chat(token, "vendor", "use tool getPrice {\"productId\":\"" + Fixtures.TOMATO + "\"}");
        chat(token, "vendor", "use tool listStoreOrders {\"status\":\"new\"}");
        assertThat(GATEWAY.requests("GET", "/api/vendor/orders").getFirst().param("status")).isEqualTo("NEW");
        chat(token, "vendor", "use tool listPurchaseOrders {\"status\":\"DRAFT\"}");
        assertThat(GATEWAY.requests("GET", "/api/vendor/purchase-orders").getFirst().param("status"))
                .isEqualTo("DRAFT");
        Result suppliers = chat(token, "vendor", "use tool listSuppliers");
        assertThat(suppliers.named("tool").getLast().text("summary")).isEqualTo("1 supplier");
        chat(token, "vendor", "use tool listReturns {\"status\":\"REQUESTED\"}");
        assertThat(GATEWAY.requests("GET", "/api/store/returns").getFirst().param("status")).isEqualTo("REQUESTED");
        Result reviews = chat(token, "vendor", "use tool listReviews");
        assertThat(reviews.answer()).contains("\"reply\":\"Thank you!\"").contains("\"rating\":4");

        assertThat(GATEWAY.requests()).allSatisfy(request -> assertThat(request.header("Authorization"))
                .isEqualTo("Bearer " + token));
        assertThat(GATEWAY.requests("GET", "/api/vendor/inventory/" + Fixtures.TOMATO)).hasSize(1);
        assertThat(GATEWAY.requests("GET", "/api/vendor/prices/" + Fixtures.TOMATO)).hasSize(1);
        assertThat(auditRows(vendor)).hasSize(11).allSatisfy(row -> assertThat(row)
                .containsEntry("agent", "vendor").containsEntry("status", "SUCCEEDED"));
    }

    @Test
    void draftPurchaseOrderCreatesADraftAtOnceAndValidatesItsLines() {
        UUID vendor = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("POST", "/api/vendor/purchase-orders", 201, Fixtures.json(Fixtures.purchaseOrder(
                UUID.randomUUID(), "PO-NEW00001", "DRAFT", SUPPLIER)));

        Result drafted = chat(token, "vendor", "use tool draftPurchaseOrder {\"supplierId\":\"" + SUPPLIER
                + "\",\"lines\":[{\"productId\":\"" + Fixtures.TOMATO + "\",\"quantity\":\"50\",\"unitCost\":18.5},"
                + "{\"productId\":\"" + Fixtures.RICE + "\",\"quantity\":20,\"unitCost\":\"80\"}],\"notes\":\"Weekly\"}");

        assertThat(toolEvents(drafted)).containsExactly("draftPurchaseOrder:STARTED", "draftPurchaseOrder:SUCCEEDED");
        assertThat(drafted.named("approval")).isEmpty();
        assertThat(drafted.named("tool").getLast().text("summary"))
                .isEqualTo("Drafted PO-NEW00001 for Nashik Farms: 2 lines, ₹2525.00");
        FakeGateway.Request create = GATEWAY.requests("POST", "/api/vendor/purchase-orders").getFirst();
        assertThat(create.bodyText()).isEqualTo("{\"supplierId\":\"" + SUPPLIER + "\",\"lines\":["
                + "{\"productId\":\"" + Fixtures.TOMATO + "\",\"orderedQuantity\":50,\"unitCost\":18.50},"
                + "{\"productId\":\"" + Fixtures.RICE + "\",\"orderedQuantity\":20,\"unitCost\":80.00}],"
                + "\"notes\":\"Weekly\"}");
        assertThat(create.header("Authorization")).isEqualTo("Bearer " + token);

        Result invalid = chat(token, "vendor", "use tool draftPurchaseOrder {\"supplierId\":\"" + SUPPLIER
                + "\",\"lines\":[{\"productId\":\"x\",\"quantity\":0}]}");
        assertThat(invalid.named("tool").getLast().text("status")).isEqualTo("BLOCKED");
        assertThat(invalid.answer()).isEqualTo("I can't do that: Invalid arguments: lines[0].productId must be an id "
                + "(UUID); lines[0].quantity must be between 1 and 100000; lines[0].unitCost is required.");
        assertThat(GATEWAY.requests("POST", "/api/vendor/purchase-orders")).hasSize(1);
    }

    @Test
    void setStockProposesAnAdjustmentTheVendorApprovesThemselves() throws Exception {
        UUID vendor = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("GET", "/api/vendor/inventory", 200, Fixtures.page(List.of(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 12, 2, 5))));
        GATEWAY.json("GET", "/api/vendor/inventory/" + Fixtures.TOMATO, 200, Fixtures.json(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 12, 2, 5)));

        Result result = chat(token, "vendor", "set stock tomato to 25");

        assertThat(toolEvents(result)).containsExactly("listInventory:STARTED", "listInventory:SUCCEEDED",
                "adjustStock:STARTED", "adjustStock:APPROVAL_REQUIRED");
        assertThat(GATEWAY.requests("GET", "/api/vendor/inventory").getFirst().param("q")).isEqualTo("tomato");
        JsonNode view = result.first("approval").data();
        UUID approvalId = approvalId(result);
        assertThat(view.get("action").asString()).isEqualTo("ADJUST_STOCK");
        assertThat(view.get("agent").asString()).isEqualTo("vendor");
        assertThat(view.get("requestedBy").asString()).isEqualTo(vendor.toString());
        assertThat(view.get("approverUserId").asString()).isEqualTo(vendor.toString());
        assertThat(view.get("onBehalfOf").isNull()).isTrue();
        assertThat(view.get("approverRoles")).isEmpty();
        assertThat(view.get("fourEyes").asBoolean()).isFalse();
        assertThat(view.get("canDecide").asBoolean()).isTrue();
        assertThat(view.get("rationale").asString()).isEqualTo("You asked me to set the stock of Tomato to 25.");
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Set stock of Tomato (500 g) to 25");
        assertThat(view.get("summary").get("amount").isNull()).isTrue();
        assertThat(summaryLines(view)).containsExactly(
                "Product=Tomato (500 g)",
                "SKU=TOM-500",
                "Current stock=12 on hand · 2 reserved · 10 available",
                "Change=Set to 25 (+13)",
                "New stock=25 on hand · 23 available",
                "Reason=Stock count");
        Map<String, Object> row = approvalRow(approvalId);
        assertThat(row).containsEntry("http_method", "POST")
                .containsEntry("http_path", "/api/vendor/inventory/" + Fixtures.TOMATO + "/adjustments")
                .containsEntry("request_body", "{\"quantity\":25,\"reason\":\"STOCK_COUNT\",\"type\":\"SET\"}");
        assertThat(lifetime(row)).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.answer()).startsWith("I've prepared “Set stock of Tomato (500 g) to 25”. Nothing happens "
                + "until you approve it in the app");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
        assertThat(event(approvalId, "ApprovalRequested").get("approverUserId").asString())
                .isEqualTo(vendor.toString());

        // another vendor, an admin and a customer never see the vendor's own change
        mvc.perform(get("/api/approvals/{id}", approvalId).with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("APPROVAL_NOT_FOUND"));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isNotFound());

        // the vendor approves: exactly the stored call, with the vendor's token and an inventory-service approval
        GATEWAY.json("POST", "/api/vendor/inventory/" + Fixtures.TOMATO + "/adjustments", 200, Fixtures.json(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 25, 2, 5)));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId).with(as(vendor, "VENDOR"))
                        .header("X-Correlation-Id", "vendor-corr-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.productName").value("Tomato"))
                .andExpect(jsonPath("$.result.onHand").value(25))
                .andExpect(jsonPath("$.decidedBy").value(vendor.toString()));
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/vendor/inventory/.*/adjustments").getFirst();
        assertThat(call.bodyText()).isEqualTo(row.get("request_body"));
        assertThat(call.header("Authorization")).isEqualTo("Bearer token-of-" + vendor);
        assertThat(call.header("Idempotency-Key")).isNull();
        assertThat(call.header("X-Correlation-Id")).isEqualTo("vendor-corr-1");
        JWTClaimsSet claims = approvalClaims(call);
        assertThat(claims.getAudience()).containsExactly("inventory-service");
        assertThat(claims.getSubject()).isEqualTo(vendor.toString());
        assertThat(claims.getClaim("obo")).isNull();
        assertThat(claims.getJWTID()).isEqualTo(approvalId.toString());
        assertThat(claims.getStringClaim("act")).isEqualTo("ADJUST_STOCK");
        assertThat(claims.getStringClaim("htm")).isEqualTo("POST");
        assertThat(claims.getStringClaim("htu")).isEqualTo("/api/vendor/inventory/" + Fixtures.TOMATO
                + "/adjustments");
        assertThat(claims.getStringClaim("bsh")).isEqualTo(bodyHash(call.body()));
        assertThat(event(approvalId, "ApprovalExecuted").get("resultReference").asString())
                .isEqualTo("Tomato: 25 on hand");
    }

    @Test
    void impossibleStockChangesAreRefusedBeforeAnyApproval() {
        UUID vendor = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("GET", "/api/vendor/inventory/" + Fixtures.TOMATO, 200, Fixtures.json(
                Fixtures.stock(Fixtures.TOMATO, "Tomato", 12, 2, 5)));

        Result tooMuch = chat(token, "vendor", "use tool adjustStock {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"type\":\"decrease\",\"quantity\":11,\"reason\":\"DAMAGED\"}");
        assertThat(tooMuch.answer()).isEqualTo("I can't do that: Only 10 of Tomato (500 g) are available (2 reserved "
                + "for orders), so the stock can go down by at most 10.");
        Result belowReserved = chat(token, "vendor", "use tool adjustStock {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"type\":\"SET\",\"quantity\":1,\"reason\":\"STOCK_COUNT\"}");
        assertThat(belowReserved.answer()).isEqualTo("I can't do that: Tomato (500 g) has 2 units reserved for "
                + "orders; the stock on hand cannot be set below 2.");
        Result zero = chat(token, "vendor", "use tool adjustStock {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"type\":\"INCREASE\",\"quantity\":0,\"reason\":\"RESTOCK\"}");
        assertThat(zero.answer()).isEqualTo("I can't do that: Invalid arguments: quantity must be between 1 and "
                + "100000 for INCREASE / DECREASE.");
        Result increase = chat(token, "vendor", "use tool adjustStock {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"type\":\"INCREASE\",\"quantity\":10,\"reason\":\"RESTOCK\",\"note\":\"Morning delivery\"}");
        JsonNode view = increase.first("approval").data();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Increase stock of Tomato (500 g) by 10");
        assertThat(summaryLines(view)).contains("Change=+10", "New stock=22 on hand · 20 available",
                "Reason=Restock", "Note=Morning delivery");
        assertThat(approvalRow(approvalId(increase)).get("request_body")).isEqualTo("{\"note\":\"Morning delivery\","
                + "\"quantity\":10,\"reason\":\"RESTOCK\",\"type\":\"INCREASE\"}");
        assertThat(auditRows(vendor)).extracting(r -> r.get("status") + ":" + r.get("reason")).containsExactly(
                "BLOCKED:INVALID_ARGUMENTS", "BLOCKED:INVALID_ARGUMENTS", "BLOCKED:INVALID_ARGUMENTS",
                "APPROVAL_REQUIRED:null");
    }

    @Test
    void aPriceChangeShowsOldAndNewPriceAndWhetherAnAdminMustStillApprove() throws Exception {
        UUID vendor = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("GET", "/api/vendor/products", 200, Fixtures.page(List.of(Fixtures.map("id",
                Fixtures.TOMATO.toString(), "name", "Tomato", "unitLabel", "500 g", "status", "PUBLISHED"))));
        GATEWAY.json("GET", "/api/vendor/prices/" + Fixtures.TOMATO, 200, Fixtures.json(Fixtures.priceView(
                Fixtures.TOMATO, "Tomato", "60.00", "49.00", null)));

        Result small = chat(token, "vendor", "price tomato 45");
        assertThat(toolEvents(small)).containsExactly("listProducts:STARTED", "listProducts:SUCCEEDED",
                "changePrice:STARTED", "changePrice:APPROVAL_REQUIRED");
        JsonNode within = small.first("approval").data();
        assertThat(within.get("action").asString()).isEqualTo("CHANGE_PRICE");
        assertThat(within.get("summary").get("title").asString()).isEqualTo("Change price of Tomato (500 g) to "
                + "₹45.00");
        assertThat(within.get("summary").get("amount").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(summaryLines(within)).containsExactly(
                "Product=Tomato (500 g)",
                "Current price=₹49.00 (MRP ₹60.00)",
                "New price=₹45.00 (MRP ₹60.00)",
                "Change=-8.16 %",
                "Admin approval=Not needed (within the 20 % threshold)");
        assertThat(approvalRow(approvalId(small))).containsEntry("http_method", "PUT")
                .containsEntry("http_path", "/api/vendor/prices/" + Fixtures.TOMATO)
                .containsEntry("request_body", "{\"mrp\":60.00,\"sellingPrice\":45.00}");

        Result big = chat(token, "vendor", "price tomato 30");
        JsonNode above = big.first("approval").data();
        assertThat(summaryLines(above)).contains("Change=-38.78 %", "Admin approval=Needed: the change is above the "
                + "20 % threshold, so after you approve, pricing waits for an admin and the live price stays until "
                + "then");
        assertThat(big.answer()).endsWith("A change above the price threshold then still waits for an admin.");

        // approved by the vendor: pricing-service answers 202 PENDING_APPROVAL and the result says so
        Map<String, Object> pending = Fixtures.priceView(Fixtures.TOMATO, "Tomato", "60.00", "49.00",
                Fixtures.map("requestedMrp", 60.00, "requestedSellingPrice", 30.00, "changePercent", -38.78,
                        "status", "PENDING"));
        pending.put("outcome", "PENDING_APPROVAL");
        GATEWAY.json("PUT", "/api/vendor/prices/" + Fixtures.TOMATO, 202, Fixtures.json(pending));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(big)).with(as(vendor, "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.result.requestedSellingPrice").value(30.00))
                .andExpect(jsonPath("$.result.message").value("The change is above the price threshold: it now "
                        + "waits for an admin's approval in pricing, and the live price stays until then."));
        FakeGateway.Request call = GATEWAY.requests("PUT", "/api/vendor/prices/.*").getFirst();
        assertThat(call.bodyText()).isEqualTo("{\"mrp\":60.00,\"sellingPrice\":30.00}");
        JWTClaimsSet claims = approvalClaims(call);
        assertThat(claims.getAudience()).containsExactly("pricing-service");
        assertThat(claims.getStringClaim("htm")).isEqualTo("PUT");
        assertThat(claims.getStringClaim("act")).isEqualTo("CHANGE_PRICE");
        assertThat(event(approvalId(big), "ApprovalExecuted").get("resultReference").asString())
                .isEqualTo("Tomato: waiting for admin approval");

        // the selling price may not exceed the MRP; a pending change must be cancelled first
        assertThat(chat(token, "vendor", "use tool changePrice {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"sellingPrice\":70}").answer()).isEqualTo("I can't do that: The selling price ₹70.00 may not "
                + "be above the MRP ₹60.00.");
        GATEWAY.json("GET", "/api/vendor/prices/" + Fixtures.TOMATO, 200, Fixtures.json(pending));
        Result blocked = chat(token, "vendor", "use tool changePrice {\"productId\":\"" + Fixtures.TOMATO
                + "\",\"sellingPrice\":40,\"mrp\":65}");
        assertThat(blocked.named("approval")).isEmpty();
        assertThat(blocked.answer()).isEqualTo("That didn't work: A price change of Tomato (500 g) to ₹30.00 already "
                + "waits for an admin; cancel it first. (PRICE_CHANGE_PENDING)");
    }

    @Test
    void sendingAPurchaseOrderShowsSupplierLinesAndTotal() throws Exception {
        UUID vendor = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        String token = TOKENS.token(vendor, "VENDOR");
        GATEWAY.json("GET", "/api/vendor/purchase-orders", 200, Fixtures.page(List.of(Fixtures.map("id",
                poId.toString(), "number", "PO-7K3M9QX2", "status", "DRAFT"))));
        GATEWAY.json("GET", "/api/vendor/purchase-orders/" + poId, 200, Fixtures.json(Fixtures.purchaseOrder(poId,
                "PO-7K3M9QX2", "DRAFT", SUPPLIER)));

        Result result = chat(token, "vendor", "use tool sendPurchaseOrder {\"purchaseOrderNumber\":\"po-7k3m9qx2\"}");

        JsonNode view = result.first("approval").data();
        assertThat(view.get("action").asString()).isEqualTo("SEND_PURCHASE_ORDER");
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Send purchase order PO-7K3M9QX2 to "
                + "Nashik Farms");
        assertThat(view.get("summary").get("amount").decimalValue()).isEqualByComparingTo("2525.00");
        assertThat(summaryLines(view)).containsExactly(
                "Purchase order=PO-7K3M9QX2",
                "Supplier=Nashik Farms",
                "Tomato × 50 @ ₹18.50=₹925.00",
                "Smart Rice × 20 @ ₹80.00=₹1600.00",
                "Expected=2026-11-15",
                "Total=₹2525.00");
        Map<String, Object> row = approvalRow(approvalId(result));
        assertThat(row).containsEntry("http_path", "/api/vendor/purchase-orders/" + poId + "/send")
                .containsEntry("request_body", "{}");

        GATEWAY.json("POST", "/api/vendor/purchase-orders/" + poId + "/send", 200, Fixtures.json(
                Fixtures.purchaseOrder(poId, "PO-7K3M9QX2", "SENT", SUPPLIER)));
        mvc.perform(post("/api/approvals/{id}/approve", approvalId(result)).with(as(vendor, "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.purchaseOrderId").value(poId.toString()))
                .andExpect(jsonPath("$.result.purchaseOrderNumber").value("PO-7K3M9QX2"))
                .andExpect(jsonPath("$.result.number").value("PO-7K3M9QX2"))
                .andExpect(jsonPath("$.result.status").value("SENT"));
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/vendor/purchase-orders/.*/send").getFirst();
        assertThat(new String(call.body(), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(approvalClaims(call).getAudience()).containsExactly("vendor-service");
        assertThat(approvalClaims(call).getStringClaim("bsh")).isEqualTo(bodyHash("{}".getBytes(
                StandardCharsets.UTF_8)));

        // only a draft can be sent
        GATEWAY.json("GET", "/api/vendor/purchase-orders/" + poId, 200, Fixtures.json(Fixtures.purchaseOrder(poId,
                "PO-7K3M9QX2", "SENT", SUPPLIER)));
        assertThat(chat(token, "vendor", "use tool sendPurchaseOrder {\"purchaseOrderNumber\":\"PO-7K3M9QX2\"}")
                .answer()).isEqualTo("That didn't work: Purchase order PO-7K3M9QX2 is SENT; only a draft can be "
                + "sent. (INVALID_STATUS_TRANSITION)");
        assertThat(chat(token, "vendor", "use tool sendPurchaseOrder {\"purchaseOrderNumber\":\"PO-NOPE0000\"}")
                .answer()).contains("PURCHASE_ORDER_NOT_FOUND");
        assertThat(chat(token, "vendor", "use tool sendPurchaseOrder {\"purchaseOrderNumber\":\"#1; DROP\"}")
                .answer()).isEqualTo("I can't do that: Invalid arguments: purchaseOrderNumber must be a purchase "
                + "order number such as PO-7K3M9QX2.");
    }

    @Test
    void eachStoreAssistantIsOnlyForItsRole() {
        UUID vendor = UUID.randomUUID();
        Result restaurant = chat(TOKENS.token(vendor, "VENDOR"), "restaurant", "orders");
        assertThat(restaurant.status()).isEqualTo(403);
        assertThat(restaurant.problem().get("errorCode").asString()).isEqualTo("AGENT_NOT_ALLOWED");
        UUID customer = UUID.randomUUID();
        assertThat(chat(TOKENS.token(customer, "CUSTOMER"), "vendor", "low stock").status()).isEqualTo(403);
        UUID partner = UUID.randomUUID();
        assertThat(chat(TOKENS.token(partner, "DELIVERY_PARTNER"), "admin-bi", "stats").status()).isEqualTo(403);
        assertThat(auditRows(vendor)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("reason", "AGENT_NOT_ALLOWED").containsEntry("agent", "restaurant"));
        assertThat(auditRows(customer)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("agent", "vendor"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    void theVendorAssistantHasNoAdminOrCustomerTools() {
        UUID vendor = UUID.randomUUID();
        Result refund = chat(TOKENS.token(vendor, "VENDOR"), "vendor",
                "use tool refundOrder {\"orderNumber\":\"GA-7K3F9Q2M\",\"note\":\"x\"}");
        assertThat(refund.answer()).isEqualTo("I can't do that: The tool 'refundOrder' is not available to the "
                + "Vendor assistant.");
        Result accept = chat(TOKENS.token(vendor, "VENDOR"), "vendor",
                "use tool acceptOrder {\"orderNumber\":\"GA-7K3F9Q2M\",\"readyInMinutes\":15}");
        assertThat(auditRows(vendor)).extracting(r -> r.get("tool") + ":" + r.get("reason"))
                .containsExactly("refundOrder:TOOL_NOT_ALLOWED", "acceptOrder:TOOL_NOT_ALLOWED");
        assertThat(accept.named("approval")).isEmpty();
        // Only the per-turn context read of the vendor's own store; no blocked tool reached a business API.
        assertThat(GATEWAY.requests()).extracting(FakeGateway.Request::path).containsOnly("/api/vendors/me");
    }
}
