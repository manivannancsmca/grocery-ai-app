package com.grocery.vendor;

import com.grocery.common.id.Ids;
import com.grocery.vendor.event.CatalogEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for the purchasing tests (Phase 12 §4): products arriving the way they do in production (a
 * {@code ProductUpserted} record handed to the catalog listener), suppliers and purchase orders through the API, and
 * the receipts / GoodsReceived outbox rows. Tests use their own random vendors and products.
 */
public abstract class PurchasingTestSupport extends StoreOrderTestSupport {

    protected static final String PO_AGGREGATE = "PurchaseOrder";

    @Autowired
    protected CatalogEventsListener catalogListener;

    // ---- catalog.events -----------------------------------------------------------------------------------

    /** A full ProductUpserted snapshot as catalog-command publishes it. */
    protected Map<String, Object> productPayload(UUID productId, UUID vendorId, String name, String status,
                                                 long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId.toString());
        payload.put("vendorId", vendorId.toString());
        payload.put("vendorName", "Green Basket");
        payload.put("vendorActive", true);
        payload.put("name", name);
        payload.put("description", null);
        payload.put("brand", null);
        payload.put("category", Map.of("id", UUID.randomUUID().toString(), "name", "Staples"));
        payload.put("categoryPath", List.of());
        payload.put("categoryActive", true);
        payload.put("unitValue", 1);
        payload.put("unitOfMeasure", "KG");
        payload.put("unitLabel", "1 kg");
        payload.put("sku", "SKU-" + productId.toString().substring(0, 6));
        payload.put("barcode", null);
        payload.put("foodType", "VEG");
        payload.put("tags", List.of());
        payload.put("attributes", List.of());
        payload.put("imageUrls", List.of());
        payload.put("status", status);
        payload.put("publishedAt", "2026-11-11T10:00:00Z");
        payload.put("updatedAt", "2026-11-11T10:00:00Z");
        payload.put("productVersion", version);
        return payload;
    }

    protected ConsumerRecord<String, String> productUpserted(String eventId, Map<String, Object> payload) {
        return record("catalog.events", "Product", "ProductUpserted", eventId, (String) payload.get("productId"),
                json.writeValueAsString(payload));
    }

    protected void upsert(UUID productId, UUID vendorId, String name, String status, long version) {
        catalogListener.on(productUpserted(newEventId(), productPayload(productId, vendorId, name, status, version)));
    }

    /** A new PUBLISHED product of the store (version 1), known to vendor-service. */
    protected UUID product(Store store, String name) {
        UUID productId = Ids.newId();
        upsert(productId, store.vendorId(), name, "PUBLISHED", 1);
        return productId;
    }

    // ---- suppliers ----------------------------------------------------------------------------------------

    protected ResultActions createSupplierRequest(Store store, String body) throws Exception {
        return mvc.perform(post("/api/vendor/suppliers").with(vendor(store))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A new active supplier of the store; returns its id. */
    protected UUID supplier(Store store, String name) throws Exception {
        return UUID.fromString(body(createSupplierRequest(store, "{\"name\":\"" + name + "\",\"leadTimeDays\":3}")
                .andExpect(status().isCreated())).get("id").asString());
    }

    // ---- purchase orders ----------------------------------------------------------------------------------

    /** One purchase order line: {@code unitCost} as JSON number text, e.g. "45.50". */
    protected record PoLine(UUID productId, int quantity, String unitCost) {
    }

    protected static PoLine poLine(UUID productId, int quantity, String unitCost) {
        return new PoLine(productId, quantity, unitCost);
    }

    protected static String poBody(UUID supplierId, String expectedAt, String notes, List<PoLine> lines) {
        List<String> items = new ArrayList<>();
        for (PoLine line : lines) {
            items.add("{\"productId\":\"%s\",\"orderedQuantity\":%d,\"unitCost\":%s}"
                    .formatted(line.productId(), line.quantity(), line.unitCost()));
        }
        return "{\"supplierId\":\"%s\",\"expectedAt\":%s,\"notes\":%s,\"lines\":[%s]}".formatted(supplierId,
                expectedAt == null ? "null" : "\"" + expectedAt + "\"", notes == null ? "null" : "\"" + notes + "\"",
                String.join(",", items));
    }

    protected ResultActions createPoRequest(Store store, String body) throws Exception {
        return mvc.perform(post("/api/vendor/purchase-orders").with(vendor(store))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected JsonNode createPo(Store store, UUID supplierId, List<PoLine> lines) throws Exception {
        return body(createPoRequest(store, poBody(supplierId, null, null, lines)).andExpect(status().isCreated()));
    }

    protected ResultActions updatePoRequest(Store store, UUID poId, String body) throws Exception {
        return mvc.perform(put("/api/vendor/purchase-orders/{id}", poId).with(vendor(store))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions poAction(Store store, UUID poId, String action) throws Exception {
        return mvc.perform(post("/api/vendor/purchase-orders/{id}/" + action, poId).with(vendor(store)));
    }

    /** A SENT purchase order of the store; returns its id. */
    protected UUID sentPo(Store store, UUID supplierId, List<PoLine> lines) throws Exception {
        UUID poId = UUID.fromString(createPo(store, supplierId, lines).get("id").asString());
        poAction(store, poId, "send").andExpect(status().isOk());
        return poId;
    }

    /** {@code lines}: product → quantity, in order. */
    protected static String receiveBody(List<Map.Entry<UUID, Integer>> lines, String note) {
        List<String> items = new ArrayList<>();
        for (Map.Entry<UUID, Integer> line : lines) {
            items.add("{\"productId\":\"%s\",\"quantity\":%d}".formatted(line.getKey(), line.getValue()));
        }
        return "{\"lines\":[%s],\"note\":%s}".formatted(String.join(",", items),
                note == null ? "null" : "\"" + note + "\"");
    }

    protected MockHttpServletRequestBuilder receive(Store store, UUID poId, String key, String body) {
        MockHttpServletRequestBuilder request = post("/api/vendor/purchase-orders/{id}/receive", poId)
                .with(vendor(store)).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request;
    }

    protected ResultActions receiveRequest(Store store, UUID poId, String key, String body) throws Exception {
        return mvc.perform(receive(store, poId, key, body));
    }

    protected static String newKey() {
        return "rcv-" + UUID.randomUUID();
    }

    // ---- database -----------------------------------------------------------------------------------------

    /** The GoodsReceived payloads of one purchase order, oldest first. */
    protected List<JsonNode> goodsReceived(UUID poId) {
        return jdbc().sql("SELECT payload FROM outbox_event WHERE event_type = 'GoodsReceived' "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.purchaseOrderId')) = ? ORDER BY occurred_at, id")
                .param(poId.toString()).query(String.class).list().stream().map(json::readTree).toList();
    }

    /** The routing columns of the GoodsReceived row of one receipt. */
    protected Map<String, Object> goodsReceivedRow(UUID receiptId) {
        return jdbc().sql("SELECT topic, aggregate_type, aggregate_id, event_version FROM outbox_event "
                        + "WHERE event_type = 'GoodsReceived' "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.receiptId')) = ?")
                .param(receiptId.toString()).query().singleRow();
    }

    protected long receiptCount(UUID poId) {
        return jdbc().sql("SELECT COUNT(*) FROM purchase_receipt WHERE purchase_order_id = UUID_TO_BIN(?)")
                .param(poId.toString()).query(Long.class).single();
    }

    protected String poStatus(UUID poId) {
        return jdbc().sql("SELECT status FROM purchase_order WHERE id = UUID_TO_BIN(?)")
                .param(poId.toString()).query(String.class).single();
    }
}
