package com.grocery.vendor;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Purchase orders (Phase 12 §4): create / edit (DRAFT only), the status machine with its 409s, own products and
 * suppliers only, ownership (404), lists and the admin read endpoints.
 */
class PurchaseOrderIntegrationTest extends PurchasingTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-12T05:00:00Z");

    private static List<String> numbers(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(po -> po.get("number").asString()).toList();
    }

    @Test
    void createReturnsADraftWithItsNumberLinesAndTotals() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "Sharma Wholesale");
        UUID rice = product(store, "Basmati Rice");
        UUID dal = product(store, "Toor Dal");
        clock.set(T0);

        String response = createPoRequest(store, poBody(supplierId, "2026-11-20", "Deliver to back door",
                List.of(poLine(rice, 20, "45.50"), poLine(dal, 10, "98")))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode po = json.readTree(response);
        // money always with 2 decimals
        assertThat(response).contains("\"unitCost\":45.50", "\"unitCost\":98.00", "\"totalCost\":1890.00",
                "\"receivedCost\":0.00");

        assertThat(fieldNames(po)).containsExactly("id", "number", "vendorId", "supplierId", "supplierName", "status",
                "expectedAt", "notes", "currency", "lines", "orderedUnits", "receivedUnits", "totalCost",
                "receivedCost", "receipts", "createdAt", "updatedAt", "sentAt", "cancelledAt", "closedAt",
                "fullyReceivedAt");
        assertThat(po.get("number").asString()).matches("^PO-[0-9A-HJKMNP-TV-Z]{8}$");
        assertThat(po.get("vendorId").asString()).isEqualTo(store.vendorId().toString());
        assertThat(po.get("supplierId").asString()).isEqualTo(supplierId.toString());
        assertThat(po.get("supplierName").asString()).isEqualTo("Sharma Wholesale");
        assertThat(po.get("status").asString()).isEqualTo("DRAFT");
        // a date = 00:00 India time
        assertThat(Instant.parse(po.get("expectedAt").asString())).isEqualTo(Instant.parse("2026-11-19T18:30:00Z"));
        assertThat(po.get("notes").asString()).isEqualTo("Deliver to back door");
        assertThat(po.get("currency").asString()).isEqualTo("INR");
        assertThat(po.get("lines")).hasSize(2);
        JsonNode first = po.get("lines").get(0);
        assertThat(fieldNames(first)).containsExactly("productId", "productName", "orderedQuantity",
                "receivedQuantity", "openQuantity", "unitCost", "lineTotal");
        assertThat(first.get("productId").asString()).isEqualTo(rice.toString());
        assertThat(first.get("productName").asString()).isEqualTo("Basmati Rice");
        assertThat(first.get("orderedQuantity").asInt()).isEqualTo(20);
        assertThat(first.get("receivedQuantity").asInt()).isZero();
        assertThat(first.get("openQuantity").asInt()).isEqualTo(20);
        assertThat(first.get("unitCost").decimalValue()).isEqualByComparingTo("45.50");
        assertThat(first.get("lineTotal").decimalValue()).isEqualByComparingTo("910.00");
        assertThat(po.get("lines").get(1).get("unitCost").decimalValue()).isEqualByComparingTo("98.00");
        assertThat(po.get("orderedUnits").asInt()).isEqualTo(30);
        assertThat(po.get("receivedUnits").asInt()).isZero();
        assertThat(po.get("totalCost").decimalValue()).isEqualByComparingTo("1890.00");
        assertThat(po.get("receivedCost").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(po.get("receipts")).isEmpty();
        assertThat(Instant.parse(po.get("createdAt").asString())).isEqualTo(T0);
        assertThat(po.get("sentAt").isNull()).isTrue();

        // the same view on GET; the product name stays the snapshot when the catalog renames it
        upsert(rice, store.vendorId(), "Renamed Rice", "PUBLISHED", 2);
        JsonNode fetched = body(mvc.perform(get("/api/vendor/purchase-orders/{id}", po.get("id").asString())
                .with(vendor(store))).andExpect(status().isOk()));
        assertThat(fetched).isEqualTo(po);
    }

    @Test
    void expectedAtAcceptsAnInstantAndQuantityIsAnAliasOfOrderedQuantity() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "S");
        UUID rice = product(store, "Rice");
        String body = """
                {"supplierId":"%s","expectedAt":"2026-11-20T10:15:30.123456789+05:30",
                 "lines":[{"productId":"%s","quantity":7,"unitCost":0}]}""".formatted(supplierId, rice);
        JsonNode po = body(createPoRequest(store, body).andExpect(status().isCreated()));
        assertThat(Instant.parse(po.get("expectedAt").asString()))
                .isEqualTo(Instant.parse("2026-11-20T04:45:30.123456Z"));
        assertThat(po.get("lines").get(0).get("orderedQuantity").asInt()).isEqualTo(7);
        assertThat(po.get("totalCost").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(po.get("notes").isNull()).isTrue();
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "S");
        UUID rice = product(store, "Rice");
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(rice, 1, "1"), poLine(rice, 2, "1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[1].productId"));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(rice, 0, "1"))))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].orderedQuantity"));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(rice, 100_001, "1"))))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].orderedQuantity"));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(rice, 1, "-0.01"))))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].unitCost"));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(rice, 1, "1.005"))))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].unitCost"));
        createPoRequest(store, poBody(supplierId, null, null, List.of()))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));
        createPoRequest(store, poBody(supplierId, "next tuesday", null, List.of(poLine(rice, 1, "1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expectedAt"));
        createPoRequest(store, poBody(supplierId, null, "n".repeat(501), List.of(poLine(rice, 1, "1"))))
                .andExpect(jsonPath("$.errors[0].field").value("notes"));
        createPoRequest(store, "{\"lines\":[{\"productId\":\"" + rice + "\",\"orderedQuantity\":1,\"unitCost\":1}]}")
                .andExpect(jsonPath("$.errors[0].field").value("supplierId"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM purchase_order WHERE vendor_id = UUID_TO_BIN(?)")
                .param(store.vendorId().toString()).query(Long.class).single()).isZero();
    }

    @Test
    void onlyTheVendorsOwnNonArchivedProductsMayBeOrdered() throws Exception {
        Store store = newStore();
        Store other = newStore();
        UUID supplierId = supplier(store, "S");
        UUID own = product(store, "Own Rice");
        UUID foreign = product(other, "Their Rice");
        UUID archived = product(store, "Old Rice");
        upsert(archived, store.vendorId(), "Old Rice", "ARCHIVED", 2);
        UUID draft = product(store, "Draft Rice");
        upsert(draft, store.vendorId(), "Draft Rice", "DRAFT", 2);

        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(own, 1, "1"), poLine(foreign, 1, "1"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCT"))
                .andExpect(jsonPath("$.productId").value(foreign.toString()));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(UUID.randomUUID(), 1, "1"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCT"));
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(archived, 1, "1"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.productId").value(archived.toString()));
        // DRAFT / UNPUBLISHED products may be stocked up before they are sold
        createPoRequest(store, poBody(supplierId, null, null, List.of(poLine(draft, 1, "1"), poLine(own, 1, "1"))))
                .andExpect(status().isCreated());
        // also on edit
        UUID poId = UUID.fromString(createPo(store, supplierId, List.of(poLine(own, 1, "1"))).get("id").asString());
        updatePoRequest(store, poId, poBody(supplierId, null, null, List.of(poLine(foreign, 1, "1"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCT"));
    }

    @Test
    void onlyADraftIsEditable() throws Exception {
        Store store = newStore();
        UUID first = supplier(store, "First Supplier");
        UUID second = supplier(store, "Second Supplier");
        UUID rice = product(store, "Rice");
        UUID dal = product(store, "Dal");
        UUID poId = UUID.fromString(createPo(store, first, List.of(poLine(rice, 10, "40.00"))).get("id").asString());

        JsonNode edited = body(updatePoRequest(store, poId, poBody(second, "2026-12-01T00:00:00Z", "Edited",
                List.of(poLine(dal, 5, "120.25"), poLine(rice, 2, "41.00")))).andExpect(status().isOk()));
        assertThat(edited.get("status").asString()).isEqualTo("DRAFT");
        assertThat(edited.get("supplierName").asString()).isEqualTo("Second Supplier");
        assertThat(edited.get("notes").asString()).isEqualTo("Edited");
        assertThat(edited.get("lines")).hasSize(2);
        assertThat(edited.get("lines").get(0).get("productName").asString()).isEqualTo("Dal");
        assertThat(edited.get("totalCost").decimalValue()).isEqualByComparingTo("683.25");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM purchase_order_line WHERE purchase_order_id = UUID_TO_BIN(?)")
                .param(poId.toString()).query(Long.class).single()).isEqualTo(2);

        poAction(store, poId, "send").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        updatePoRequest(store, poId, poBody(first, null, null, List.of(poLine(rice, 1, "1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_EDITABLE"))
                .andExpect(jsonPath("$.currentStatus").value("SENT"))
                .andExpect(jsonPath("$.status").value(409));
        assertThat(body(mvc.perform(get("/api/vendor/purchase-orders/{id}", poId).with(vendor(store))))
                .get("supplierName").asString()).isEqualTo("Second Supplier");
    }

    @Test
    void theStatusMachineAllowsOnlyItsTransitions() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "S");
        UUID rice = product(store, "Rice");
        List<PoLine> lines = List.of(poLine(rice, 10, "40.00"));

        // DRAFT: close → 409; cancel → CANCELLED; then everything → 409 CANCELLED
        UUID draft = UUID.fromString(createPo(store, supplierId, lines).get("id").asString());
        poAction(store, draft, "close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("DRAFT"));
        clock.set(T0);
        poAction(store, draft, "cancel").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-11-12T05:00:00Z"));
        clock.reset();
        for (String action : List.of("send", "cancel", "close")) {
            poAction(store, draft, action).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        }

        // SENT: send again / close → 409; cancel → CANCELLED
        UUID sent = UUID.fromString(createPo(store, supplierId, lines).get("id").asString());
        clock.set(T0);
        poAction(store, sent, "send").andExpect(status().isOk())
                .andExpect(jsonPath("$.sentAt").value("2026-11-12T05:00:00Z"));
        clock.reset();
        poAction(store, sent, "send").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("SENT"));
        poAction(store, sent, "close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("SENT"));
        poAction(store, sent, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // PARTIALLY_RECEIVED: send / cancel → 409; close → CLOSED (short); then receive → 409 CLOSED
        UUID partial = sentPo(store, supplierId, lines);
        receiveRequest(store, partial, newKey(), receiveBody(List.of(Map.entry(rice, 4)), null))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"));
        poAction(store, partial, "send").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PARTIALLY_RECEIVED"));
        poAction(store, partial, "cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PARTIALLY_RECEIVED"));
        JsonNode closed = body(poAction(store, partial, "close").andExpect(status().isOk()));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closedAt").isNull()).isFalse();
        assertThat(closed.get("receivedUnits").asInt()).isEqualTo(4);
        receiveRequest(store, partial, newKey(), receiveBody(List.of(Map.entry(rice, 1)), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("CLOSED"));

        // RECEIVED: every action → 409 RECEIVED
        UUID full = sentPo(store, supplierId, lines);
        receiveRequest(store, full, newKey(), receiveBody(List.of(Map.entry(rice, 10)), null))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        for (String action : List.of("send", "cancel", "close")) {
            poAction(store, full, action).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.currentStatus").value("RECEIVED"));
        }
    }

    @Test
    void anotherVendorsPurchaseOrderIsNotFound() throws Exception {
        Store owner = newStore();
        Store other = newStore();
        UUID supplierId = supplier(owner, "S");
        UUID rice = product(owner, "Rice");
        UUID poId = sentPo(owner, supplierId, List.of(poLine(rice, 10, "40.00")));

        mvc.perform(get("/api/vendor/purchase-orders/{id}", poId).with(vendor(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        UUID otherSupplier = supplier(other, "S2");
        UUID otherRice = product(other, "Rice");
        updatePoRequest(other, poId, poBody(otherSupplier, null, null, List.of(poLine(otherRice, 1, "1"))))
                .andExpect(status().isNotFound());
        for (String action : List.of("send", "cancel", "close")) {
            poAction(other, poId, action).andExpect(status().isNotFound());
        }
        receiveRequest(other, poId, newKey(), receiveBody(List.of(Map.entry(rice, 1)), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        assertThat(body(mvc.perform(get("/api/vendor/purchase-orders").with(vendor(other)))).get("totalElements")
                .asLong()).isZero();
        assertThat(poStatus(poId)).isEqualTo("SENT");
        assertThat(receiptCount(poId)).isZero();
        mvc.perform(get("/api/vendor/purchase-orders/{id}", UUID.randomUUID()).with(vendor(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/vendor/purchase-orders/abc/send").with(vendor(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void listFiltersByStatusSupplierAndNumberNewestFirst() throws Exception {
        Store store = newStore();
        UUID a = supplier(store, "A");
        UUID b = supplier(store, "B");
        UUID rice = product(store, "Rice");
        List<PoLine> lines = List.of(poLine(rice, 10, "40.00"));
        clock.set(T0);
        String draftA = createPo(store, a, lines).get("number").asString();
        clock.set(T0.plusSeconds(60));
        JsonNode sentB = createPo(store, b, lines);
        poAction(store, UUID.fromString(sentB.get("id").asString()), "send");
        clock.set(T0.plusSeconds(120));
        JsonNode cancelledA = createPo(store, a, lines);
        poAction(store, UUID.fromString(cancelledA.get("id").asString()), "cancel");
        clock.reset();

        JsonNode all = body(mvc.perform(get("/api/vendor/purchase-orders").with(vendor(store))));
        assertThat(numbers(all)).containsExactly(cancelledA.get("number").asString(), sentB.get("number").asString(),
                draftA);
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(numbers(body(mvc.perform(get("/api/vendor/purchase-orders?status=OPEN").with(vendor(store))))))
                .containsExactly(sentB.get("number").asString(), draftA);
        assertThat(numbers(body(mvc.perform(get("/api/vendor/purchase-orders?status=CANCELLED").with(vendor(store))))))
                .containsExactly(cancelledA.get("number").asString());
        assertThat(numbers(body(mvc.perform(get("/api/vendor/purchase-orders?supplierId=" + a).with(vendor(store))))))
                .containsExactly(cancelledA.get("number").asString(), draftA);
        assertThat(numbers(body(mvc.perform(get("/api/vendor/purchase-orders?number=" + draftA.toLowerCase())
                .with(vendor(store)))))).containsExactly(draftA);
        JsonNode page = body(mvc.perform(get("/api/vendor/purchase-orders?page=1&size=2").with(vendor(store))));
        assertThat(numbers(page)).containsExactly(draftA);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        mvc.perform(get("/api/vendor/purchase-orders?status=SHIPPED").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/purchase-orders?supplierId=nope").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void adminsReadEveryVendorsPurchaseOrders() throws Exception {
        Store first = newStore();
        Store second = newStore();
        UUID rice1 = product(first, "Rice");
        UUID rice2 = product(second, "Rice");
        UUID po1 = sentPo(first, supplier(first, "S1"), List.of(poLine(rice1, 3, "10.00")));
        UUID po2 = UUID.fromString(createPo(second, supplier(second, "S2"), List.of(poLine(rice2, 3, "10.00")))
                .get("id").asString());

        UUID admin = UUID.randomUUID();
        JsonNode firstOnly = body(mvc.perform(get("/api/admin/purchase-orders?vendorId=" + first.vendorId())
                .with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(firstOnly.get("totalElements").asLong()).isEqualTo(1);
        assertThat(firstOnly.get("content").get(0).get("id").asString()).isEqualTo(po1.toString());
        JsonNode drafts = body(mvc.perform(get("/api/admin/purchase-orders?status=DRAFT&vendorId=" + second.vendorId())
                .with(as(admin, "FINANCE_ADMIN"))));
        assertThat(drafts.get("content").get(0).get("id").asString()).isEqualTo(po2.toString());
        assertThat(body(mvc.perform(get("/api/admin/purchase-orders").with(as(admin, "OPS_ADMIN"))))
                .get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/admin/purchase-orders/{id}", po2).with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorId").value(second.vendorId().toString()));
        mvc.perform(get("/api/admin/purchase-orders/{id}", UUID.randomUUID()).with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isNotFound());

        // read-only, admins only
        mvc.perform(get("/api/admin/purchase-orders").with(vendor(first))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/purchase-orders/{id}/send", po2).with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/vendor/purchase-orders/{id}/send", po2).with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(poStatus(po2)).isEqualTo("DRAFT");
    }

    @Test
    void onlyApprovedVendorsMayUsePurchaseOrders() throws Exception {
        mvc.perform(get("/api/vendor/purchase-orders").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        Store store = newStore();
        UUID supplierId = supplier(store, "S");
        UUID rice = product(store, "Rice");
        mvc.perform(post("/api/vendor/purchase-orders").with(as(UUID.randomUUID(), store.vendorId(), "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(poBody(supplierId, null, null, List.of(poLine(rice, 1, "1.00")))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/purchase-orders").with(as(UUID.randomUUID(), store.vendorId(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/purchase-orders")).andExpect(status().isUnauthorized());
    }
}
