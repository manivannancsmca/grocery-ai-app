package com.grocery.vendor;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Receiving goods (Phase 12 §4): receipts, GoodsReceived in the same transaction, partial / full, the Idempotency-Key
 * (same key → same result and no second event), RECEIPT_EXCEEDS_ORDERED, and the status rules.
 */
class GoodsReceivingIntegrationTest extends PurchasingTestSupport {

    private static final Instant T1 = Instant.parse("2026-11-13T06:15:00.123456Z");
    private static final Instant T2 = Instant.parse("2026-11-14T07:00:00Z");

    @Test
    void aPartialThenTheFinalReceiptPublishGoodsReceivedEachAndCompleteThePurchaseOrder() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "Sharma Wholesale");
        UUID rice = product(store, "Basmati Rice");
        UUID dal = product(store, "Toor Dal");
        UUID poId = sentPo(store, supplierId, List.of(poLine(rice, 20, "45.50"), poLine(dal, 10, "98.00")));
        String number = poStatusNumber(poId);

        clock.set(T1);
        JsonNode partial = body(receiveRequest(store, poId, newKey(),
                receiveBody(List.of(Map.entry(dal, 4), Map.entry(rice, 20)), "  First truck  "))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
        assertThat(partial.get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(partial.get("receivedUnits").asInt()).isEqualTo(24);
        assertThat(partial.get("receivedCost").decimalValue()).isEqualByComparingTo("1302.00");
        assertThat(partial.get("lines").get(0).get("receivedQuantity").asInt()).isEqualTo(20);
        assertThat(partial.get("lines").get(0).get("openQuantity").asInt()).isZero();
        assertThat(partial.get("lines").get(1).get("openQuantity").asInt()).isEqualTo(6);
        assertThat(partial.get("fullyReceivedAt").isNull()).isTrue();
        assertThat(partial.get("receipts")).hasSize(1);
        JsonNode receipt = partial.get("receipts").get(0);
        assertThat(fieldNames(receipt)).containsExactly("id", "receivedAt", "receivedBy", "note", "lines", "units",
                "totalCost");
        assertThat(Instant.parse(receipt.get("receivedAt").asString())).isEqualTo(T1);
        assertThat(receipt.get("receivedBy").asString()).isEqualTo(store.ownerUserId().toString());
        assertThat(receipt.get("note").asString()).isEqualTo("First truck");
        assertThat(receipt.get("units").asInt()).isEqualTo(24);
        assertThat(receipt.get("totalCost").decimalValue()).isEqualByComparingTo("1302.00");
        assertThat(fieldNames(receipt.get("lines").get(0))).containsExactly("productId", "productName", "quantity",
                "unitCost", "lineCost");
        assertThat(receipt.get("lines").get(0).get("productName").asString()).isEqualTo("Toor Dal");   // request order
        assertThat(receipt.get("lines").get(0).get("lineCost").decimalValue()).isEqualByComparingTo("392.00");

        // GoodsReceived: vendor.events, aggregate PurchaseOrder, key = vendorId, exactly the 1.5.0 schema's fields
        UUID receiptId = UUID.fromString(receipt.get("id").asString());
        assertThat(goodsReceivedRow(receiptId)).containsEntry("topic", "vendor.events")
                .containsEntry("aggregate_type", PO_AGGREGATE)
                .containsEntry("aggregate_id", store.vendorId().toString())
                .containsEntry("event_version", 1);
        List<JsonNode> events = goodsReceived(poId);
        assertThat(events).hasSize(1);
        JsonNode event = events.getFirst();
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("vendorId", "purchaseOrderId", "purchaseOrderNumber",
                "receiptId", "supplierId", "lines", "receivedAt");
        assertThat(event.get("vendorId").asString()).isEqualTo(store.vendorId().toString());
        assertThat(event.get("purchaseOrderNumber").asString()).isEqualTo(number);
        assertThat(event.get("receiptId").asString()).isEqualTo(receiptId.toString());
        assertThat(event.get("supplierId").asString()).isEqualTo(supplierId.toString());
        assertThat(Instant.parse(event.get("receivedAt").asString())).isEqualTo(T1);
        assertThat(event.get("lines")).hasSize(2);
        assertThat(fieldNames(event.get("lines").get(0))).containsExactlyInAnyOrder("productId", "quantity", "unitCost");
        assertThat(event.get("lines").get(0).get("productId").asString()).isEqualTo(dal.toString());
        assertThat(event.get("lines").get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(event.get("lines").get(1).get("unitCost").decimalValue()).isEqualByComparingTo("45.50");

        // the rest: RECEIVED, a second receipt and a second event
        clock.set(T2);
        JsonNode full = body(receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(dal, 6)), null))
                .andExpect(status().isOk()));
        assertThat(full.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(Instant.parse(full.get("fullyReceivedAt").asString())).isEqualTo(T2);
        assertThat(full.get("receivedUnits").asInt()).isEqualTo(30);
        assertThat(full.get("receipts")).hasSize(2);
        assertThat(full.get("receipts").get(1).get("note").isNull()).isTrue();
        assertThat(receiptCount(poId)).isEqualTo(2);
        assertThat(goodsReceived(poId)).hasSize(2);
        assertThat(goodsReceived(poId).get(1).get("lines").get(0).get("quantity").asInt()).isEqualTo(6);
    }

    @Test
    void theSameIdempotencyKeyAnswersTheSameResultWithoutASecondReceiptOrEvent() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID poId = sentPo(store, supplier(store, "S"), List.of(poLine(rice, 10, "40.00")));
        String key = newKey();
        String body = receiveBody(List.of(Map.entry(rice, 3)), "Van 1");

        JsonNode first = body(receiveRequest(store, poId, key, body).andExpect(status().isOk()));
        JsonNode retry = body(receiveRequest(store, poId, key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(retry).isEqualTo(first);
        assertThat(receiptCount(poId)).isEqualTo(1);
        assertThat(goodsReceived(poId)).hasSize(1);
        assertThat(first.get("receivedUnits").asInt()).isEqualTo(3);

        // the same key with another body (or for another purchase order) → 422, nothing done
        receiveRequest(store, poId, key, receiveBody(List.of(Map.entry(rice, 4)), "Van 1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        UUID otherPo = sentPo(store, supplier(store, "S2"), List.of(poLine(rice, 10, "40.00")));
        receiveRequest(store, otherPo, key, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(receiptCount(otherPo)).isZero();

        // another vendor may use the same key (keys are per vendor)
        Store other = newStore();
        UUID theirRice = product(other, "Rice");
        UUID theirPo = sentPo(other, supplier(other, "S"), List.of(poLine(theirRice, 10, "40.00")));
        receiveRequest(other, theirPo, key, receiveBody(List.of(Map.entry(theirRice, 3)), "Van 1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        // after the purchase order moved on, the retry still answers (the purchase order as it is now)
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(rice, 7)), null))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        receiveRequest(store, poId, key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(goodsReceived(poId)).hasSize(2);
    }

    @Test
    void concurrentRetriesWithOneKeyReceiveOnce() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID poId = sentPo(store, supplier(store, "S"), List.of(poLine(rice, 10, "40.00")));
        String key = newKey();
        String body = receiveBody(List.of(Map.entry(rice, 2)), null);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> mvc.perform(receive(store, poId, key, body)).andReturn());
            }
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : pool.invokeAll(calls)) {
                results.add(future.get());
            }
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(results.stream().filter(r -> r.getResponse().getHeader("Idempotent-Replayed") == null))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(receiptCount(poId)).isEqualTo(1);
        assertThat(goodsReceived(poId)).hasSize(1);
        assertThat(jdbc().sql("SELECT received_quantity FROM purchase_order_line WHERE purchase_order_id = UUID_TO_BIN(?)")
                .param(poId.toString()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void receivingMoreThanIsOpenIs422AndChangesNothing() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID dal = product(store, "Dal");
        UUID notOnPo = product(store, "Salt");
        UUID poId = sentPo(store, supplier(store, "S"), List.of(poLine(rice, 10, "40.00"), poLine(dal, 5, "90.00")));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(rice, 6)), null))
                .andExpect(status().isOk());

        String key = newKey();
        receiveRequest(store, poId, key, receiveBody(List.of(Map.entry(dal, 5), Map.entry(rice, 5)), null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_EXCEEDS_ORDERED"))
                .andExpect(jsonPath("$.productId").value(rice.toString()))
                .andExpect(jsonPath("$.openQuantity").value(4));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(notOnPo, 1)), null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_EXCEEDS_ORDERED"))
                .andExpect(jsonPath("$.productId").value(notOnPo.toString()))
                .andExpect(jsonPath("$.openQuantity").value(0));
        assertThat(receiptCount(poId)).isEqualTo(1);
        assertThat(goodsReceived(poId)).hasSize(1);
        assertThat(poStatus(poId)).isEqualTo("PARTIALLY_RECEIVED");

        // the failed key was rolled back with the error: it can be used once the request is fixed
        receiveRequest(store, poId, key, receiveBody(List.of(Map.entry(dal, 5), Map.entry(rice, 4)), null))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(goodsReceived(poId)).hasSize(2);
    }

    @Test
    void goodsCanOnlyBeReceivedOnASentOrPartiallyReceivedPurchaseOrder() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID supplierId = supplier(store, "S");
        UUID draft = UUID.fromString(createPo(store, supplierId, List.of(poLine(rice, 10, "1.00"))).get("id")
                .asString());
        receiveRequest(store, draft, newKey(), receiveBody(List.of(Map.entry(rice, 1)), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("DRAFT"));
        poAction(store, draft, "send");
        poAction(store, draft, "cancel");
        receiveRequest(store, draft, newKey(), receiveBody(List.of(Map.entry(rice, 1)), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        assertThat(receiptCount(draft)).isZero();
        assertThat(goodsReceived(draft)).isEmpty();
    }

    @Test
    void theKeyAndTheBodyAreValidated() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID poId = sentPo(store, supplier(store, "S"), List.of(poLine(rice, 10, "1.00")));
        String body = receiveBody(List.of(Map.entry(rice, 1)), null);

        receiveRequest(store, poId, null, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        receiveRequest(store, poId, "short", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(rice, 0)), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(), null))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(rice, 1), Map.entry(rice, 1)), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[1].productId"));
        receiveRequest(store, poId, newKey(), receiveBody(List.of(Map.entry(rice, 1)), "n".repeat(301)))
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        receiveRequest(store, poId, newKey(), "").andExpect(status().isBadRequest());
        assertThat(receiptCount(poId)).isZero();
        assertThat(poStatus(poId)).isEqualTo("SENT");
    }

    private String poStatusNumber(UUID poId) {
        return jdbc().sql("SELECT number FROM purchase_order WHERE id = UUID_TO_BIN(?)").param(poId.toString())
                .query(String.class).single();
    }
}
