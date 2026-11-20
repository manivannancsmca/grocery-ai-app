package com.grocery.cart;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** order.events records as Debezium's outbox router delivers them: routing data in the headers, the payload as value. */
final class OrderEventRecords {

    private OrderEventRecords() {
    }

    static String newEventId() {
        return UUID.randomUUID().toString();
    }

    static ConsumerRecord<String, String> record(String eventType, String eventId, String key, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0L, key, payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Order".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** A full OrderCreated (contract §6) of a grocery order; {@code cartUpdatedAt} exactly as given (may be null). */
    static ConsumerRecord<String, String> orderCreated(JsonMapper json, String eventId, UUID customerId,
                                                       String cartUpdatedAt) {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = orderCreatedPayload(orderId, customerId, cartUpdatedAt);
        return record("OrderCreated", eventId, orderId.toString(), json.writeValueAsString(payload));
    }

    static Map<String, Object> orderCreatedPayload(UUID orderId, UUID customerId, String cartUpdatedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-" + orderId.toString().substring(0, 8).toUpperCase());
        payload.put("orderType", "GROCERY");
        payload.put("customerId", customerId == null ? null : customerId.toString());
        payload.put("storeId", UUID.randomUUID().toString());
        payload.put("storeName", "Green Basket");
        payload.put("items", List.of(Map.of("itemId", UUID.randomUUID().toString(), "type", "PRODUCT",
                "name", "Basmati Rice", "quantity", 2, "unitPrice", 90.00, "lineTotal", 180.00)));
        payload.put("subtotal", 180.00);
        payload.put("deliveryFee", 25.00);
        payload.put("total", 205.00);
        payload.put("currency", "INR");
        payload.put("cartUpdatedAt", cartUpdatedAt);
        payload.put("paymentDueAt", "2026-11-11T10:30:00Z");
        payload.put("createdAt", "2026-11-11T10:15:00Z");
        return payload;
    }
}
