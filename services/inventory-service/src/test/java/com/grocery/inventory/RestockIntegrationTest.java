package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.inventory.event.VendorEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12: stock coming back in from events — {@code vendor.events → GoodsReceived} (group
 * inventory-service.vendor-events, INCREASE / RESTOCK) and {@code order.events → ReturnApproved} with restock (the
 * order-events consumer, INCREASE / RETURN). The listeners are called directly, as Kafka would, with the routing data in
 * the record headers.
 */
class RestockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    VendorEventsListener vendorListener;

    // ---- records ------------------------------------------------------------------------------------------

    /** GoodsReceived exactly as vendor-service publishes it (schemas 1.5.0); {@code lines}: product → quantity. */
    private ConsumerRecord<String, String> goodsReceived(String eventId, UUID vendorId, UUID purchaseOrderId,
                                                         String number, List<Map.Entry<UUID, Integer>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vendorId", vendorId.toString());
        payload.put("purchaseOrderId", purchaseOrderId.toString());
        payload.put("purchaseOrderNumber", number);
        payload.put("receiptId", Ids.newId().toString());
        payload.put("supplierId", UUID.randomUUID().toString());
        payload.put("lines", lines.stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", line.getKey().toString());
            item.put("quantity", line.getValue());
            item.put("unitCost", 42.50);
            return item;
        }).toList());
        payload.put("receivedAt", "2026-11-13T06:15:00.123456Z");
        return record("vendor.events", "PurchaseOrder", "GoodsReceived", eventId, vendorId.toString(),
                json.writeValueAsString(payload));
    }

    /** ReturnApproved exactly as order-service publishes it (schemas 1.5.0); {@code items}: product → quantity. */
    private ConsumerRecord<String, String> returnApproved(String eventId, UUID orderId, String orderType,
                                                          UUID storeId, boolean restock,
                                                          List<Map.Entry<UUID, Integer>> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnId", Ids.newId().toString());
        payload.put("returnNumber", "RT-7K3M9QX2");
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-4F8KQ2ZD");
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", storeId.toString());
        payload.put("items", items.stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", line.getKey().toString());
            item.put("name", "Item");
            item.put("quantity", line.getValue());
            item.put("refundAmount", 47.50);
            return item;
        }).toList());
        payload.put("refundAmount", 95.00);
        payload.put("restock", restock);
        payload.put("decidedBy", UUID.randomUUID().toString());
        payload.put("decidedByRole", "STORE");
        payload.put("decidedAt", "2026-11-13T09:00:00Z");
        return orderEvent("ReturnApproved", eventId, orderId, payload);
    }

    private long processedBy(String eventId, String consumer) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
                .param(eventId).param(consumer).query(Long.class).single();
    }

    /** The movements of a product, oldest first: type, delta, reason, note, reference, actor. */
    private List<Map<String, Object>> movementRows(UUID productId) {
        return jdbc().sql("SELECT type, quantity_delta, on_hand_after, reason, note, reference, actor_user_id "
                        + "FROM stock_movement WHERE product_id = UUID_TO_BIN(?) ORDER BY created_at, id")
                .param(productId.toString()).query().listOfRows();
    }

    // ---- GoodsReceived ------------------------------------------------------------------------------------

    @Test
    void goodsReceivedIncreasesStockOnceWithReasonRestock() throws Exception {
        UUID rice = newProduct(vendorId, "Rice");
        UUID dal = newProductWithStock(vendorId, "Dal", 3);
        UUID poId = Ids.newId();
        String eventId = newEventId();
        long dalVersion = stockVersion(dal);

        vendorListener.on(goodsReceived(eventId, vendorId, poId, "PO-7K3M9QX2",
                List.of(Map.entry(rice, 12), Map.entry(dal, 5))));

        assertThat(onHand(rice)).isEqualTo(12);
        assertThat(onHand(dal)).isEqualTo(8);
        assertThat(stockVersion(dal)).isEqualTo(dalVersion + 1);
        assertThat(processedBy(eventId, "inventory-service.vendor-events")).isEqualTo(1);
        Map<String, Object> movement = movementRows(rice).getLast();
        assertThat(movement).containsEntry("type", "INCREASE").containsEntry("quantity_delta", 12)
                .containsEntry("on_hand_after", 12).containsEntry("reason", "RESTOCK")
                .containsEntry("note", "PO-7K3M9QX2").containsEntry("reference", poId.toString());
        assertThat(movement.get("actor_user_id")).isNull();

        // the stock events of any adjustment: StockAdjusted (system change, no actor) and the availability transition
        JsonNode adjusted = events(rice, "StockAdjusted").getLast();
        assertThat(adjusted.get("type").asString()).isEqualTo("INCREASE");
        assertThat(adjusted.get("delta").asInt()).isEqualTo(12);
        assertThat(adjusted.get("onHand").asInt()).isEqualTo(12);
        assertThat(adjusted.get("reason").asString()).isEqualTo("RESTOCK");
        assertThat(adjusted.get("actorUserId").isNull()).isTrue();
        assertThat(adjusted.get("stockVersion").asLong()).isEqualTo(stockVersion(rice));
        assertThat(events(rice, "ProductAvailabilityChanged")).hasSize(1);
        assertThat(events(rice, "ProductAvailabilityChanged").getFirst().get("inStock").asBoolean()).isTrue();
        assertThat(events(dal, "ProductAvailabilityChanged")).hasSize(1);          // only its first INCREASE

        // the vendor sees it in the movements
        JsonNode movements = body(mvc.perform(get("/api/vendor/inventory/{id}/movements", rice).with(vendor(vendorId)))
                .andExpect(status().isOk()));
        assertThat(movements.get("content").get(0).get("reason").asString()).isEqualTo("RESTOCK");
        assertThat(movements.get("content").get(0).get("note").asString()).isEqualTo("PO-7K3M9QX2");

        // redelivered: nothing changes, nothing is published again
        long riceVersion = stockVersion(rice);
        vendorListener.on(goodsReceived(eventId, vendorId, poId, "PO-7K3M9QX2",
                List.of(Map.entry(rice, 12), Map.entry(dal, 5))));
        assertThat(onHand(rice)).isEqualTo(12);
        assertThat(onHand(dal)).isEqualTo(8);
        assertThat(stockVersion(rice)).isEqualTo(riceVersion);
        assertThat(movementCount(rice)).isEqualTo(1);
        assertThat(events(rice, "StockAdjusted")).hasSize(1);

        // a second receipt (another event) adds again; lines of one product are added up into one movement
        vendorListener.on(goodsReceived(newEventId(), vendorId, poId, "PO-7K3M9QX2",
                List.of(Map.entry(rice, 2), Map.entry(rice, 3))));
        assertThat(onHand(rice)).isEqualTo(17);
        assertThat(movementCount(rice)).isEqualTo(2);
        assertThat(movementRows(rice).getLast()).containsEntry("quantity_delta", 5);
    }

    @Test
    void otherVendorEventTypesAreIgnored() {
        String eventId = newEventId();
        vendorListener.on(record("vendor.events", "Vendor", "VendorApproved", eventId, vendorId.toString(),
                "{\"vendorId\":\"" + vendorId + "\",\"ownerUserId\":\"" + UUID.randomUUID()
                        + "\",\"businessName\":\"Green Basket\",\"reason\":null,\"actorUserId\":null}"));
        UUID orderId = Ids.newId();
        vendorListener.on(record("vendor.events", "VendorOrder", "VendorOrderAccepted", newEventId(),
                orderId.toString(), "{\"orderId\":\"" + orderId + "\"}"));
        assertThat(processedBy(eventId, "inventory-service.vendor-events")).isZero();
    }

    @Test
    void aProductNotKnownYetFailsTheWholeReceiptSoItIsRetried() throws Exception {
        UUID rice = newProduct(vendorId, "Rice");
        UUID late = Ids.newId();
        String eventId = newEventId();
        ConsumerRecord<String, String> receipt = goodsReceived(eventId, vendorId, Ids.newId(), "PO-LATE0001",
                List.of(Map.entry(rice, 4), Map.entry(late, 6)));

        assertThatThrownBy(() -> vendorListener.on(receipt)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(late.toString());
        assertThat(onHand(rice)).isZero();
        assertThat(processedBy(eventId, "inventory-service.vendor-events")).isZero();

        // the product arrives from catalog.events; the retry applies everything once
        upsert(late, vendorId, "Late Product", null, "PUBLISHED", 1);
        vendorListener.on(receipt);
        assertThat(onHand(rice)).isEqualTo(4);
        assertThat(onHand(late)).isEqualTo(6);
        assertThat(processedBy(eventId, "inventory-service.vendor-events")).isEqualTo(1);
    }

    @Test
    void archivedAndForeignProductsAreSkippedTheRestApplies() throws Exception {
        UUID rice = newProduct(vendorId, "Rice");
        UUID archived = newProduct(vendorId, "Old Rice");
        upsert(archived, vendorId, "Old Rice", null, "ARCHIVED", 2);
        UUID foreign = newProduct(UUID.randomUUID(), "Their Rice");

        vendorListener.on(goodsReceived(newEventId(), vendorId, Ids.newId(), "PO-MIXED001",
                List.of(Map.entry(archived, 5), Map.entry(foreign, 5), Map.entry(rice, 5))));
        assertThat(onHand(rice)).isEqualTo(5);
        assertThat(onHand(archived)).isZero();
        assertThat(onHand(foreign)).isZero();
        assertThat(movementCount(archived)).isZero();
        assertThat(movementCount(foreign)).isZero();
    }

    @Test
    void stockAboveTheLimitDeadLettersTheReceiptAndAppliesNothing() throws Exception {
        UUID rice = newProduct(vendorId, "Rice");
        UUID full = newProduct(vendorId, "Full");
        adjust(vendorId, full, "SET", 999_999, "STOCK_COUNT");
        String eventId = newEventId();

        assertThatThrownBy(() -> vendorListener.on(goodsReceived(eventId, vendorId, Ids.newId(), "PO-BIG00001",
                List.of(Map.entry(rice, 1), Map.entry(full, 2))))).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("STOCK_LIMIT_EXCEEDED");
        assertThat(onHand(rice)).isZero();
        assertThat(onHand(full)).isEqualTo(999_999);
        assertThat(processedBy(eventId, "inventory-service.vendor-events")).isZero();
    }

    @Test
    void brokenReceiptsAreRejected() {
        UUID poId = Ids.newId();
        assertThatThrownBy(() -> vendorListener.on(goodsReceived(newEventId(), vendorId, poId, "PO-EMPTY001",
                List.of()))).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> vendorListener.on(goodsReceived(newEventId(), vendorId, poId, "PO-ZERO0001",
                List.of(Map.entry(Ids.newId(), 0))))).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> vendorListener.on(record("vendor.events", "PurchaseOrder", "GoodsReceived",
                newEventId(), vendorId.toString(), "{\"lines\":[]}"))).isInstanceOf(InvalidEventException.class);
    }

    // ---- ReturnApproved -----------------------------------------------------------------------------------

    @Test
    void anApprovedGroceryReturnWithRestockPutsTheItemsBackOnce() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID dal = newProduct(vendorId, "Dal");
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        long riceVersion = stockVersion(rice);

        orderListener.on(returnApproved(eventId, orderId, "GROCERY", vendorId, true,
                List.of(Map.entry(rice, 2), Map.entry(dal, 1))));

        assertThat(onHand(rice)).isEqualTo(12);
        assertThat(onHand(dal)).isEqualTo(1);
        assertThat(stockVersion(rice)).isEqualTo(riceVersion + 1);
        assertThat(processed(eventId)).isEqualTo(1);                       // the order-events consumer
        assertThat(movementRows(rice).getLast()).containsEntry("type", "INCREASE")
                .containsEntry("quantity_delta", 2).containsEntry("reason", "RETURN")
                .containsEntry("note", "RT-7K3M9QX2 (order GA-4F8KQ2ZD)")
                .containsEntry("reference", orderId.toString());
        assertThat(events(rice, "StockAdjusted").getLast().get("reason").asString()).isEqualTo("RETURN");
        assertThat(events(dal, "ProductAvailabilityChanged").getLast().get("inStock").asBoolean()).isTrue();

        // redelivered: once only
        orderListener.on(returnApproved(eventId, orderId, "GROCERY", vendorId, true,
                List.of(Map.entry(rice, 2), Map.entry(dal, 1))));
        assertThat(onHand(rice)).isEqualTo(12);
        assertThat(onHand(dal)).isEqualTo(1);
        assertThat(movementRows(rice).stream().filter(m -> "RETURN".equals(m.get("reason")))).hasSize(1);
        // reservations are untouched
        assertThat(reserved(rice)).isZero();
    }

    @Test
    void aReturnWithoutRestockOrOfARestaurantOrderChangesNothing() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        long version = stockVersion(rice);

        String noRestock = newEventId();
        orderListener.on(returnApproved(noRestock, Ids.newId(), "GROCERY", vendorId, false,
                List.of(Map.entry(rice, 2))));
        String restaurant = newEventId();
        orderListener.on(returnApproved(restaurant, Ids.newId(), "RESTAURANT", UUID.randomUUID(), true,
                List.of(Map.entry(rice, 2))));

        assertThat(onHand(rice)).isEqualTo(10);
        assertThat(stockVersion(rice)).isEqualTo(version);
        assertThat(processed(noRestock)).isZero();
        assertThat(processed(restaurant)).isZero();
    }

    @Test
    void returnedItemsThatCannotBeRestockedAreSkipped() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 1);
        UUID archived = newProduct(vendorId, "Gone");
        upsert(archived, vendorId, "Gone", null, "ARCHIVED", 2);
        String eventId = newEventId();

        orderListener.on(returnApproved(eventId, Ids.newId(), "GROCERY", vendorId, true,
                List.of(Map.entry(Ids.newId(), 1), Map.entry(archived, 1), Map.entry(rice, 1))));
        assertThat(onHand(rice)).isEqualTo(2);
        assertThat(onHand(archived)).isZero();
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void brokenReturnsAreRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        UUID orderId = Ids.newId();
        payload.put("orderId", orderId.toString());
        payload.put("orderType", "GROCERY");
        assertThatThrownBy(() -> orderListener.on(orderEvent("ReturnApproved", newEventId(), orderId, payload)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("restock");
        payload.put("restock", true);
        assertThatThrownBy(() -> orderListener.on(orderEvent("ReturnApproved", newEventId(), orderId, payload)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("items");
    }
}
