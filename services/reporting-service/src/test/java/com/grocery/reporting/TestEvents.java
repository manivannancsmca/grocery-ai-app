package com.grocery.reporting;

import com.grocery.common.id.Ids;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kafka records as Debezium's outbox router delivers them: JSON payloads with exactly the fields of the central
 * schemas (1.7.0; {@link AbstractIntegrationTest#dispatch} encodes every one with the real schema, strictly, so a
 * wrong or unknown field fails the test), and the headers {@code eventId}, {@code eventType}, {@code eventVersion},
 * {@code aggregateType}, {@code correlationId}, {@code occurredAt} (epoch microseconds).
 */
public final class TestEvents {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private TestEvents() {
    }

    /** An India local time, e.g. {@code ist("2026-10-05T10:00")}. */
    public static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(INDIA).toInstant();
    }

    public static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    // ---------------------------------------------------------------- orders

    public record Item(UUID itemId, String type, String name, int quantity, BigDecimal unitPrice) {
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static Item product(UUID productId, String name, int quantity, String unitPrice) {
        return new Item(productId, "PRODUCT", name, quantity, money(unitPrice));
    }

    public static Item dish(UUID menuItemId, String name, int quantity, String unitPrice) {
        return new Item(menuItemId, "MENU_ITEM", name, quantity, money(unitPrice));
    }

    /**
     * An order as order-service published it; total = subtotal + deliveryFee − discount − deliveryFeeDiscount.
     * {@code couponName} / {@code couponFundedBy} (schemas 1.7.0) null = an order placed before them: its events have
     * neither field.
     */
    public record Order(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                        String storeName, List<Item> items, BigDecimal deliveryFee, String couponCode,
                        BigDecimal discount, BigDecimal deliveryFeeDiscount, String couponName,
                        String couponFundedBy) {

        public BigDecimal subtotal() {
            return items.stream().map(Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal total() {
            return subtotal().add(deliveryFee)
                    .subtract(discount == null ? BigDecimal.ZERO : discount)
                    .subtract(deliveryFeeDiscount == null ? BigDecimal.ZERO : deliveryFeeDiscount);
        }

        /** With a coupon, as placed before schemas 1.7.0: no name, no funding. */
        public Order withCoupon(String code, String discount, String deliveryFeeDiscount) {
            return withCoupon(code, discount, deliveryFeeDiscount, null, null);
        }

        /** With a coupon, its name and who funds it (PLATFORM / STORE). */
        public Order withCoupon(String code, String discount, String deliveryFeeDiscount, String name,
                                String fundedBy) {
            return new Order(orderId, orderNumber, orderType, customerId, storeId, storeName, items, deliveryFee, code,
                    discount == null ? null : money(discount),
                    deliveryFeeDiscount == null ? null : money(deliveryFeeDiscount), name, fundedBy);
        }

        /** The same order under a new id and number (convergence tests compare two copies). */
        public Order copy() {
            return new Order(UUID.randomUUID(), orderNumber(), orderType, customerId, storeId, storeName, items,
                    deliveryFee, couponCode, discount, deliveryFeeDiscount, couponName, couponFundedBy);
        }
    }

    public static Order grocery(UUID customerId, UUID vendorId, String vendorName, String deliveryFee, Item... items) {
        return new Order(UUID.randomUUID(), orderNumber(), "GROCERY", customerId, vendorId, vendorName,
                List.of(items), money(deliveryFee), null, null, null, null, null);
    }

    public static Order restaurant(UUID customerId, UUID restaurantId, String name, String deliveryFee,
                                   Item... items) {
        return new Order(UUID.randomUUID(), orderNumber(), "RESTAURANT", customerId, restaurantId, name,
                List.of(items), money(deliveryFee), null, null, null, null, null);
    }

    public static String orderNumber() {
        return "GA-" + code();
    }

    public static String code() {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static List<Map<String, Object>> items(Order order) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item item : order.items()) {
            items.add(map("itemId", item.itemId(), "type", item.type(), "name", item.name(),
                    "quantity", item.quantity(), "unitPrice", item.unitPrice(), "lineTotal", item.lineTotal()));
        }
        return items;
    }

    public static ConsumerRecord<String, String> orderCreated(Order o, Instant createdAt) {
        return event("order.events", "OrderCreated", o.orderId(), couponLabels(o, map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "storeName", o.storeName(),
                "items", items(o), "subtotal", o.subtotal(), "deliveryFee", o.deliveryFee(), "total", o.total(),
                "currency", "INR", "cartUpdatedAt", null, "paymentDueAt", createdAt.plusSeconds(900),
                "createdAt", createdAt, "couponCode", o.couponCode(), "discount", o.discount(),
                "deliveryFeeDiscount", o.deliveryFeeDiscount())), createdAt);
    }

    public static ConsumerRecord<String, String> orderConfirmed(Order o, Instant confirmedAt) {
        return event("order.events", "OrderConfirmed", o.orderId(), couponLabels(o, map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "items", items(o), "total", o.total(),
                "deliveryAddress", null, "confirmedAt", confirmedAt, "subtotal", o.subtotal(), "note", null,
                "storeLocation", map("name", o.storeName(), "city", "Bengaluru", "latitude", 12.97,
                        "longitude", 77.59),
                "couponCode", o.couponCode(), "discount", o.discount(),
                "deliveryFeeDiscount", o.deliveryFeeDiscount())), confirmedAt);
    }

    /**
     * Schemas 1.7.0: the coupon's {@code couponName} and {@code couponFundedBy}. An order without them was placed
     * before 1.7.0, so its events are written without the two fields (as the older schema had them).
     */
    private static Map<String, Object> couponLabels(Order o, Map<String, Object> payload) {
        if (o.couponName() != null || o.couponFundedBy() != null) {
            payload.put("couponName", o.couponName());
            payload.put("couponFundedBy", o.couponFundedBy());
        }
        return payload;
    }

    public static ConsumerRecord<String, String> orderCancelled(Order o, Instant cancelledAt, String previousStatus,
                                                                String reason, String cancelledBy) {
        return event("order.events", "OrderCancelled", o.orderId(), map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "previousStatus", previousStatus,
                "reason", reason, "cancelledBy", cancelledBy, "actorUserId", null, "note", null,
                "cancelledAt", cancelledAt), cancelledAt);
    }

    /** OrderRejected carries no time: {@code at} is its occurredAt header. */
    public static ConsumerRecord<String, String> orderRejected(Order o, Instant at) {
        return event("order.events", "OrderRejected", o.orderId(), map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "reason", "OUT_OF_STOCK",
                "unavailableItems", List.of(map("itemId", o.items().getFirst().itemId(),
                        "requested", o.items().getFirst().quantity(), "available", 0))), at);
    }

    public static ConsumerRecord<String, String> orderDelivered(Order o, UUID partnerId, Instant deliveredAt) {
        return event("order.events", "OrderDelivered", o.orderId(), map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "partnerId", partnerId,
                "deliveredAt", deliveredAt), deliveredAt);
    }

    public static ConsumerRecord<String, String> orderDeliveryFailed(Order o, UUID partnerId, Instant failedAt) {
        return event("order.events", "OrderDeliveryFailed", o.orderId(), map(
                "orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", o.orderType(),
                "customerId", o.customerId(), "storeId", o.storeId(), "partnerId", partnerId,
                "reason", "CUSTOMER_UNREACHABLE", "note", null, "failedAt", failedAt), failedAt);
    }

    public record ReturnedLine(UUID itemId, String name, int quantity, String refundAmount) {
    }

    public static ConsumerRecord<String, String> returnApproved(Order o, UUID returnId, String refundAmount,
                                                                Instant decidedAt, ReturnedLine... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReturnedLine line : lines) {
            items.add(map("itemId", line.itemId(), "name", line.name(), "quantity", line.quantity(),
                    "refundAmount", money(line.refundAmount())));
        }
        return event("order.events", "ReturnApproved", o.orderId(), map(
                "returnId", returnId, "returnNumber", "RT-" + code(), "orderId", o.orderId(),
                "orderNumber", o.orderNumber(), "orderType", o.orderType(), "customerId", o.customerId(),
                "storeId", o.storeId(), "items", items, "refundAmount", money(refundAmount),
                "restock", "GROCERY".equals(o.orderType()), "decidedBy", UUID.randomUUID(),
                "decidedByRole", "STORE", "decidedAt", decidedAt), decidedAt);
    }

    // ---------------------------------------------------------------- payments

    public static ConsumerRecord<String, String> paymentCaptured(UUID orderId, BigDecimal amount, Instant capturedAt,
                                                                 boolean late) {
        return event("payment.events", "PaymentCaptured", orderId, map(
                "orderId", orderId, "paymentId", UUID.randomUUID(), "razorpayPaymentId", "pay_" + code(),
                "amount", amount, "method", "upi", "capturedAt", capturedAt, "late", late), capturedAt);
    }

    /** RefundCompleted carries no time: {@code at} is its occurredAt header. */
    public static ConsumerRecord<String, String> refundCompleted(UUID orderId, UUID refundId, String amount,
                                                                 String reason, UUID returnId, Instant at) {
        return event("payment.events", "RefundCompleted", orderId, map(
                "orderId", orderId, "paymentId", UUID.randomUUID(), "refundId", refundId, "amount", money(amount),
                "reason", reason, "totalRefunded", money(amount), "fullyRefunded", false, "returnId", returnId), at);
    }

    // ---------------------------------------------------------------- deliveries

    public static ConsumerRecord<String, String> partnerAssigned(Order o, UUID deliveryId, UUID partnerId,
                                                                 String partnerName, Instant assignedAt) {
        return event("delivery.events", "DeliveryPartnerAssigned", o.orderId(), map(
                "deliveryId", deliveryId, "orderId", o.orderId(), "orderNumber", o.orderNumber(),
                "partnerId", partnerId, "partnerName", partnerName, "vehicleType", "SCOOTER", "manual", false,
                "assignedAt", assignedAt), assignedAt);
    }

    public static ConsumerRecord<String, String> pickedUp(Order o, UUID deliveryId, UUID partnerId,
                                                          Instant pickedUpAt) {
        return event("delivery.events", "DeliveryPickedUp", o.orderId(), map(
                "deliveryId", deliveryId, "orderId", o.orderId(), "orderNumber", o.orderNumber(),
                "partnerId", partnerId, "pickedUpAt", pickedUpAt), pickedUpAt);
    }

    public static ConsumerRecord<String, String> deliveryCompleted(Order o, UUID deliveryId, UUID partnerId,
                                                                   Instant deliveredAt) {
        return event("delivery.events", "DeliveryCompleted", o.orderId(), map(
                "deliveryId", deliveryId, "orderId", o.orderId(), "orderNumber", o.orderNumber(),
                "partnerId", partnerId, "deliveredAt", deliveredAt), deliveredAt);
    }

    // ---------------------------------------------------------------- reviews

    public static ConsumerRecord<String, String> reviewSubmitted(UUID reviewId, String targetType, UUID targetId,
                                                                 UUID storeId, int rating, String status,
                                                                 boolean edited, Instant submittedAt) {
        return event("review.events", "ReviewSubmitted", reviewId, map(
                "reviewId", reviewId, "targetType", targetType, "targetId", targetId, "storeId", storeId,
                "customerId", UUID.randomUUID(), "orderId", UUID.randomUUID(), "rating", rating, "status", status,
                "flagReason", "FLAGGED".equals(status) ? "PROFANITY" : null, "edited", edited,
                "submittedAt", submittedAt), submittedAt);
    }

    public static ConsumerRecord<String, String> ratingSummary(String targetType, UUID targetId, String average,
                                                               int count, List<Integer> distribution, long version,
                                                               Instant updatedAt) {
        return event("review.events", "RatingSummaryUpdated", targetId, map(
                "targetType", targetType, "targetId", targetId, "averageRating", money(average),
                "ratingCount", count, "ratingDistribution", distribution, "summaryVersion", version,
                "updatedAt", updatedAt), updatedAt);
    }

    // ---------------------------------------------------------------- purchasing

    public record ReceivedLine(UUID productId, int quantity, String unitCost) {
    }

    public static ConsumerRecord<String, String> goodsReceived(UUID vendorId, UUID purchaseOrderId,
                                                               String purchaseOrderNumber, UUID receiptId,
                                                               Instant receivedAt, ReceivedLine... lines) {
        List<Map<String, Object>> received = new ArrayList<>();
        for (ReceivedLine line : lines) {
            received.add(map("productId", line.productId(), "quantity", line.quantity(),
                    "unitCost", money(line.unitCost())));
        }
        return event("vendor.events", "GoodsReceived", vendorId, map(
                "vendorId", vendorId, "purchaseOrderId", purchaseOrderId,
                "purchaseOrderNumber", purchaseOrderNumber, "receiptId", receiptId, "supplierId", UUID.randomUUID(),
                "lines", received, "receivedAt", receivedAt), receivedAt);
    }

    // ---------------------------------------------------------------- records

    /** {@code occurredAt} null = no occurredAt header. */
    public static ConsumerRecord<String, String> event(String topic, String eventType, Object key,
                                                       Map<String, Object> payload, Instant occurredAt) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, String.valueOf(key),
                JSON.writeValueAsString(payload));
        header(record, "eventId", Ids.newId().toString());
        header(record, "eventType", eventType);
        header(record, "eventVersion", "1");
        header(record, "aggregateType", topic.substring(0, topic.indexOf('.')));
        header(record, "correlationId", UUID.randomUUID().toString());
        if (occurredAt != null) {
            header(record, "occurredAt", Long.toString(occurredAt.getEpochSecond() * 1_000_000L
                    + occurredAt.getNano() / 1_000L));
        }
        return record;
    }

    /** The same event delivered again under a NEW event id (a re-publication, not a Kafka redelivery). */
    public static ConsumerRecord<String, String> republished(ConsumerRecord<String, String> record) {
        ConsumerRecord<String, String> copy = new ConsumerRecord<>(record.topic(), 0, 0L, record.key(),
                record.value());
        record.headers().forEach(h -> {
            if (!h.key().equals("eventId")) {
                copy.headers().add(h.key(), h.value());
            }
        });
        header(copy, "eventId", Ids.newId().toString());
        return copy;
    }

    public static String eventId(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
    }

    private static void header(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A map that allows null values; UUIDs and instants rendered as the producers' Jackson does. */
    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value instanceof UUID || value instanceof Instant) {
                value = value.toString();
            }
            map.put((String) keyValues[i], value);
        }
        return map;
    }
}
