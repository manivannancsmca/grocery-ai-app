package com.grocery.notification;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kafka records as Debezium's outbox router delivers them (and grocery-common's Avro deserializer hands them to the
 * listener): JSON payloads with the central schemas' field names, and the headers {@code eventId}, {@code eventType},
 * {@code eventVersion}, {@code aggregateType}, {@code correlationId}, {@code occurredAt} (epoch microseconds).
 */
final class TestEvents {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** An occurredAt far enough in the past to be older than max-event-age: projections only. */
    static final Duration OLD = Duration.ofHours(1);

    private TestEvents() {
    }

    /** An order as order-service published it. */
    record Order(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId, String storeName,
                 BigDecimal total) {
    }

    static Order groceryOrder(UUID customerId, UUID vendorId) {
        return new Order(UUID.randomUUID(), orderNumber(), "GROCERY", customerId, vendorId, "Green Basket",
                new BigDecimal("245.00"));
    }

    static Order restaurantOrder(UUID customerId, UUID restaurantId) {
        return new Order(UUID.randomUUID(), orderNumber(), "RESTAURANT", customerId, restaurantId, "Spice Route",
                new BigDecimal("1359.50"));
    }

    static String orderNumber() {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder number = new StringBuilder("GA-");
        for (int i = 0; i < 8; i++) {
            number.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return number.toString();
    }

    // ---------------------------------------------------------------- records

    static ConsumerRecord<String, String> event(String topic, String eventType, Object key,
                                                Map<String, Object> payload) {
        return event(topic, eventType, key, payload, Instant.now());
    }

    /** {@code occurredAt} null = no occurredAt header. */
    static ConsumerRecord<String, String> event(String topic, String eventType, Object key,
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

    /** The same record with another occurredAt header (a new event id too). */
    static ConsumerRecord<String, String> at(ConsumerRecord<String, String> record, Instant occurredAt) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = JSON.readValue(record.value(), Map.class);
        return event(record.topic(), EventEnvelope.header(record, "eventType"), record.key(), payload, occurredAt);
    }

    static ConsumerRecord<String, String> old(ConsumerRecord<String, String> record) {
        return at(record, Instant.now().minus(OLD));
    }

    static String eventId(ConsumerRecord<String, String> record) {
        return EventEnvelope.header(record, "eventId");
    }

    private static void header(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A map that allows null values (rendered as JSON null, like the producers' Jackson does). */
    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            map.put((String) keyValues[i], value instanceof UUID ? value.toString() : value);
        }
        return map;
    }

    // ---------------------------------------------------------------- vendor / restaurant / delivery / identity

    static ConsumerRecord<String, String> vendor(String eventType, UUID vendorId, UUID ownerUserId, String businessName,
                                                 String reason) {
        return event("vendor.events", eventType, vendorId, map("vendorId", vendorId, "ownerUserId", ownerUserId,
                "businessName", businessName, "reason", reason, "actorUserId", null));
    }

    static ConsumerRecord<String, String> restaurant(String eventType, UUID restaurantId, UUID ownerUserId, String name,
                                                     String reason) {
        return event("restaurant.events", eventType, restaurantId, map("restaurantId", restaurantId,
                "ownerUserId", ownerUserId, "name", name, "reason", reason, "actorUserId", null));
    }

    static ConsumerRecord<String, String> restaurantListing(UUID restaurantId, UUID ownerUserId, String name,
                                                            String status, boolean acceptingOrders) {
        return event("restaurant.events", "RestaurantListingUpdated", restaurantId, map(
                "restaurantId", restaurantId, "ownerUserId", ownerUserId, "status", status, "name", name,
                "description", "Biryani and more", "cuisines", List.of("Hyderabadi"), "address", null,
                "openingHours", List.of(), "acceptingOrders", acceptingOrders, "menu", List.of(),
                "listingVersion", 7, "updatedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> partner(String eventType, UUID partnerId, String fullName, String reason) {
        return event("delivery.events", eventType, partnerId, map("partnerId", partnerId, "fullName", fullName,
                "reason", reason, "actorUserId", null));
    }

    static ConsumerRecord<String, String> user(String eventType, UUID userId) {
        Map<String, Object> payload = "UserSuspended".equals(eventType)
                ? map("userId", userId, "reason", "Fraud check", "suspendedBy", UUID.randomUUID())
                : map("userId", userId, "reactivatedBy", UUID.randomUUID());
        return event("identity.events", eventType, userId, payload);
    }

    // ---------------------------------------------------------------- order.events

    static ConsumerRecord<String, String> orderCreated(Order order) {
        return event("order.events", "OrderCreated", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "storeName", order.storeName(),
                "items", List.of(map("itemId", UUID.randomUUID(), "type", "PRODUCT", "name", "Basmati Rice",
                        "quantity", 2, "unitPrice", new BigDecimal("50.00"), "lineTotal", new BigDecimal("100.00"))),
                "subtotal", order.total(), "deliveryFee", new BigDecimal("0.00"), "total", order.total(),
                "currency", "INR", "cartUpdatedAt", null, "paymentDueAt", Instant.now().plusSeconds(900).toString(),
                "createdAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> orderAwaitingPayment(Order order) {
        return event("order.events", "OrderAwaitingPayment", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "customerId", order.customerId(),
                "amount", order.total(), "currency", "INR", "expiresAt", Instant.now().plusSeconds(900).toString(),
                "contactPhone", null));
    }

    /** {@code quantities}: one order line per value. */
    static ConsumerRecord<String, String> orderConfirmed(Order order, int... quantities) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int quantity : quantities) {
            items.add(map("itemId", UUID.randomUUID(), "type", "PRODUCT", "name", "Item", "quantity", quantity,
                    "unitPrice", new BigDecimal("10.00"), "lineTotal", new BigDecimal("10.00")));
        }
        return event("order.events", "OrderConfirmed", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "items", items,
                "total", order.total(), "deliveryAddress", null, "confirmedAt", Instant.now().toString()));
    }

    /** Phase 8: OrderConfirmed with the optional {@code subtotal} and {@code note} of schema 1.1.0. */
    static ConsumerRecord<String, String> orderConfirmedV11(Order order, String note) {
        return event("order.events", "OrderConfirmed", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(),
                "items", List.of(map("itemId", UUID.randomUUID(), "type", "PRODUCT", "name", "Item", "quantity", 3,
                        "unitPrice", new BigDecimal("10.00"), "lineTotal", new BigDecimal("30.00"))),
                "total", order.total(), "deliveryAddress", null, "confirmedAt", Instant.now().toString(),
                "subtotal", order.total(), "note", note));
    }

    static ConsumerRecord<String, String> orderAccepted(Order order, int readyInMinutes) {
        Instant acceptedAt = Instant.now();
        return event("order.events", "OrderAccepted", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "readyInMinutes", readyInMinutes,
                "readyBy", acceptedAt.plusSeconds(60L * readyInMinutes).toString(),
                "acceptedAt", acceptedAt.toString()));
    }

    static ConsumerRecord<String, String> orderPreparing(Order order) {
        return event("order.events", "OrderPreparing", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(),
                "preparingAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> orderReadyForPickup(Order order) {
        return event("order.events", "OrderReadyForPickup", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "readyAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> orderRejected(Order order) {
        return event("order.events", "OrderRejected", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "reason", "OUT_OF_STOCK",
                "unavailableItems", List.of(map("itemId", UUID.randomUUID(), "requested", 2, "available", 0))));
    }

    static ConsumerRecord<String, String> orderCancelled(Order order, String previousStatus, String reason,
                                                         String note) {
        return event("order.events", "OrderCancelled", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "previousStatus", previousStatus,
                "reason", reason, "cancelledBy", "SYSTEM", "actorUserId", null, "note", note,
                "cancelledAt", Instant.now().toString()));
    }

    // ---------------------------------------------------------------- vendor.events / restaurant.events (Phase 8)

    /**
     * A store order event {@code <Vendor|Restaurant><step>} (e.g. {@code VendorOrderAccepted}) of the order's store
     * (GROCERY → vendor.events with {@code vendorId}, RESTAURANT → restaurant.events with {@code restaurantId}),
     * key orderId, with {@code extra} fields.
     */
    static ConsumerRecord<String, String> storeOrder(String step, Order order, Map<String, Object> extra) {
        boolean vendor = "GROCERY".equals(order.orderType());
        Map<String, Object> payload = map("orderId", order.orderId(), "orderNumber", order.orderNumber(),
                vendor ? "vendorId" : "restaurantId", order.storeId());
        payload.putAll(extra);
        return event(vendor ? "vendor.events" : "restaurant.events", (vendor ? "Vendor" : "Restaurant") + step,
                order.orderId(), payload);
    }

    static ConsumerRecord<String, String> storeOrderReceived(Order order, UUID ownerUserId, int itemCount,
                                                             Instant acceptBy) {
        return storeOrder("OrderReceived", order, map("ownerUserId", ownerUserId, "itemCount", itemCount,
                "total", order.total(), "acceptBy", acceptBy.toString(), "receivedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> storeOrderAccepted(Order order, int readyInMinutes) {
        Instant acceptedAt = Instant.now();
        return storeOrder("OrderAccepted", order, map("readyInMinutes", readyInMinutes,
                "readyBy", acceptedAt.plusSeconds(60L * readyInMinutes).toString(),
                "acceptedAt", acceptedAt.toString(), "actorUserId", UUID.randomUUID()));
    }

    static ConsumerRecord<String, String> storeOrderPreparing(Order order) {
        return storeOrder("OrderPreparing", order, map("preparingAt", Instant.now().toString(),
                "actorUserId", UUID.randomUUID()));
    }

    static ConsumerRecord<String, String> storeOrderReady(Order order) {
        return storeOrder("OrderReadyForPickup", order, map("readyAt", Instant.now().toString(),
                "actorUserId", UUID.randomUUID()));
    }

    static ConsumerRecord<String, String> storeOrderRejected(Order order, String reason, String note, boolean auto) {
        return storeOrder("OrderRejected", order, map("reason", reason, "note", note, "auto", auto,
                "rejectedAt", Instant.now().toString(), "actorUserId", auto ? null : UUID.randomUUID()));
    }

    /** OrderCancelled as order-service writes it for a store decision or an admin. */
    static ConsumerRecord<String, String> orderCancelledBy(Order order, String previousStatus, String reason,
                                                           String cancelledBy, String note) {
        return event("order.events", "OrderCancelled", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "previousStatus", previousStatus,
                "reason", reason, "cancelledBy", cancelledBy,
                "actorUserId", "SYSTEM".equals(cancelledBy) ? null : UUID.randomUUID(), "note", note,
                "cancelledAt", Instant.now().toString()));
    }

    // ---------------------------------------------------------------- order.events, Phase 9

    static ConsumerRecord<String, String> orderOutForDelivery(Order order, UUID partnerId) {
        return event("order.events", "OrderOutForDelivery", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "partnerId", partnerId,
                "pickedUpAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> orderDelivered(Order order, UUID partnerId) {
        return event("order.events", "OrderDelivered", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "partnerId", partnerId,
                "deliveredAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> orderDeliveryFailed(Order order, UUID partnerId, String reason, String note) {
        return event("order.events", "OrderDeliveryFailed", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "partnerId", partnerId,
                "reason", reason, "note", note, "failedAt", Instant.now().toString()));
    }

    // ---------------------------------------------------------------- order.events, Phase 10

    /** The stuck-order watchdog's event (schema 1.3.0): {@code status} since {@code since}, {@code reason}. */
    static ConsumerRecord<String, String> orderStuckDetected(Order order, String status, String reason,
                                                             Instant since) {
        return event("order.events", "OrderStuckDetected", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "status", status, "reason", reason,
                "since", since == null ? null : since.toString(), "detectedAt", Instant.now().toString()));
    }

    // ---------------------------------------------------------------- delivery.events (Phase 9)

    /** A delivery event of {@code order} (aggregate Delivery, key orderId) with {@code extra} fields. */
    static ConsumerRecord<String, String> delivery(String eventType, Order order, UUID deliveryId,
                                                   Map<String, Object> extra) {
        Map<String, Object> payload = map("deliveryId", deliveryId, "orderId", order.orderId(),
                "orderNumber", order.orderNumber());
        payload.putAll(extra);
        return event("delivery.events", eventType, order.orderId(), payload);
    }

    static ConsumerRecord<String, String> offerCreated(Order order, UUID deliveryId, UUID offerId, UUID partnerId,
                                                       String storeName, double distanceKm, Instant expiresAt) {
        return delivery("DeliveryOfferCreated", order, deliveryId, map("offerId", offerId, "partnerId", partnerId,
                "storeName", storeName, "distanceKm", distanceKm, "tripKm", 3.4, "expiresAt", expiresAt.toString()));
    }

    static ConsumerRecord<String, String> offerClosed(Order order, UUID deliveryId, UUID offerId, UUID partnerId,
                                                      String outcome) {
        return delivery("DeliveryOfferClosed", order, deliveryId, map("offerId", offerId, "partnerId", partnerId,
                "outcome", outcome, "closedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> partnerAssigned(Order order, UUID deliveryId, UUID partnerId,
                                                          String partnerName) {
        return delivery("DeliveryPartnerAssigned", order, deliveryId, map("partnerId", partnerId,
                "partnerName", partnerName, "vehicleType", "SCOOTER", "manual", false,
                "assignedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> partnerUnassigned(Order order, UUID deliveryId, UUID partnerId) {
        return delivery("DeliveryPartnerUnassigned", order, deliveryId, map("partnerId", partnerId,
                "reason", "Stuck in traffic", "actorUserId", UUID.randomUUID(),
                "unassignedAt", Instant.now().toString()));
    }

    /** DeliveryPickedUp / DeliveryCompleted (with {@code at} under their time field). */
    static ConsumerRecord<String, String> deliveryStep(String eventType, Order order, UUID deliveryId,
                                                       UUID partnerId) {
        String timeField = "DeliveryPickedUp".equals(eventType) ? "pickedUpAt" : "deliveredAt";
        return delivery(eventType, order, deliveryId, map("partnerId", partnerId,
                timeField, Instant.now().toString()));
    }

    static ConsumerRecord<String, String> deliveryFailed(Order order, UUID deliveryId, UUID partnerId) {
        return delivery("DeliveryFailed", order, deliveryId, map("partnerId", partnerId,
                "reason", "CUSTOMER_UNREACHABLE", "note", null, "failedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> assignmentFailed(Order order, UUID deliveryId) {
        return delivery("DeliveryAssignmentFailed", order, deliveryId, map("attempts", 6, "radiusKm", 10.0,
                "failedAt", Instant.now().toString()));
    }

    /** {@code partnerId} null = no partner was assigned. */
    static ConsumerRecord<String, String> deliveryCancelled(Order order, UUID deliveryId, UUID partnerId) {
        return delivery("DeliveryCancelled", order, deliveryId, map("partnerId", partnerId,
                "cancelledAt", Instant.now().toString()));
    }

    // ---------------------------------------------------------------- delivery.location (Phase 9)

    /** A DeliveryLocationUpdated JSON payload ({@code heading} may be null). */
    static Map<String, Object> locationPayload(Order order, UUID deliveryId, UUID partnerId, double latitude,
                                               double longitude, Double heading, Instant recordedAt) {
        return map("deliveryId", deliveryId, "orderId", order.orderId(), "partnerId", partnerId,
                "latitude", latitude, "longitude", longitude, "heading", heading, "speedKmh", 18.5,
                "accuracyMeters", 6.0, "recordedAt", recordedAt.toString());
    }

    /**
     * A position as delivery-service's direct producer sends it: key orderId, no outbox router in between, so only
     * the headers the producer chooses to add ({@code withHeaders}: eventId and eventType).
     */
    static ConsumerRecord<String, String> location(Map<String, Object> payload, boolean withHeaders) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.location", 0, 0L,
                String.valueOf(payload.get("orderId")), JSON.writeValueAsString(payload));
        if (withHeaders) {
            header(record, "eventId", Ids.newId().toString());
            header(record, "eventType", "DeliveryLocationUpdated");
        }
        return record;
    }

    static ConsumerRecord<String, String> location(Order order, UUID partnerId, double latitude, double longitude,
                                                   Instant recordedAt) {
        return location(locationPayload(order, UUID.randomUUID(), partnerId, latitude, longitude, 90.0, recordedAt),
                true);
    }

    // ---------------------------------------------------------------- payment.events

    static ConsumerRecord<String, String> payment(String eventType, UUID orderId, Map<String, Object> extra) {
        Map<String, Object> payload = map("orderId", orderId, "paymentId", UUID.randomUUID());
        payload.putAll(extra);
        return event("payment.events", eventType, orderId, payload);
    }

    static ConsumerRecord<String, String> paymentInitiated(UUID orderId) {
        return payment("PaymentInitiated", orderId, map("razorpayOrderId", "order_test",
                "amount", new BigDecimal("245.00"), "currency", "INR",
                "expiresAt", Instant.now().plusSeconds(900).toString()));
    }

    static ConsumerRecord<String, String> paymentFailed(UUID orderId, String errorDescription, boolean retryable) {
        return payment("PaymentFailed", orderId, map("razorpayPaymentId", "pay_failed",
                "errorCode", "BAD_REQUEST_ERROR", "errorDescription", errorDescription, "retryable", retryable));
    }

    static ConsumerRecord<String, String> refundCompleted(UUID orderId, BigDecimal amount) {
        return payment("RefundCompleted", orderId, map("refundId", UUID.randomUUID(), "amount", amount,
                "reason", "ORDER_CANCELLED", "totalRefunded", amount, "fullyRefunded", true));
    }

    static ConsumerRecord<String, String> refundFailed(UUID orderId, BigDecimal amount, String reason,
                                                       String errorCode) {
        return payment("RefundFailed", orderId, map("refundId", UUID.randomUUID(), "amount", amount,
                "reason", reason, "errorCode", errorCode));
    }

    // ---------------------------------------------------------------- inventory.events / pricing.events

    static ConsumerRecord<String, String> stockAdjusted(UUID productId, UUID vendorId) {
        return event("inventory.events", "StockAdjusted", productId, map("productId", productId,
                "vendorId", vendorId, "movementId", UUID.randomUUID(), "type", "INCREASE", "delta", 5,
                "onHand", 25, "reserved", 0, "available", 25, "reason", "RESTOCK", "actorUserId", null,
                "stockVersion", 3));
    }

    static ConsumerRecord<String, String> lowStock(UUID productId, UUID vendorId, String productName, int available,
                                                   int threshold) {
        return event("inventory.events", "LowStockDetected", productId, map("productId", productId,
                "vendorId", vendorId, "productName", productName, "available", available, "threshold", threshold));
    }

    static ConsumerRecord<String, String> availability(UUID productId, UUID vendorId, boolean inStock) {
        return event("inventory.events", "ProductAvailabilityChanged", productId, map("productId", productId,
                "vendorId", vendorId, "inStock", inStock, "stockVersion", 4));
    }

    static ConsumerRecord<String, String> priceChanged(UUID productId, UUID vendorId) {
        return event("pricing.events", "PriceChanged", productId, map("productId", productId, "vendorId", vendorId,
                "mrp", new BigDecimal("60.00"), "sellingPrice", new BigDecimal("50.00"), "currency", "INR",
                "priceVersion", 5, "changedAt", Instant.now().toString()));
    }

    static ConsumerRecord<String, String> priceChangeRequested(UUID requestId, UUID productId, UUID vendorId,
                                                               String productName, String oldSellingPrice,
                                                               String newSellingPrice, String changePercent) {
        return event("pricing.events", "PriceChangeRequested", productId, map("requestId", requestId,
                "productId", productId, "vendorId", vendorId, "productName", productName,
                "oldMrp", new BigDecimal("100.00"), "oldSellingPrice", new BigDecimal(oldSellingPrice),
                "newMrp", new BigDecimal("100.00"), "newSellingPrice", new BigDecimal(newSellingPrice),
                "changePercent", new BigDecimal(changePercent), "requestedBy", UUID.randomUUID()));
    }

    static ConsumerRecord<String, String> priceChange(String eventType, UUID requestId, UUID productId, UUID vendorId,
                                                      String reason) {
        Map<String, Object> payload = map("requestId", requestId, "productId", productId, "vendorId", vendorId);
        switch (eventType) {
            case "PriceChangeApproved" -> payload.put("approvedBy", UUID.randomUUID().toString());
            case "PriceChangeRejected" -> {
                payload.put("rejectedBy", UUID.randomUUID().toString());
                payload.put("reason", reason);
            }
            default -> {
                payload.put("cancelledBy", null);
                payload.put("reason", reason);
            }
        }
        return event("pricing.events", eventType, productId, payload);
    }

    // ---------------------------------------------------------------- ai.events (Phase 11)

    /** An assistant's approval request as ai-orchestrator-service publishes it (schemas 1.4.0, key approvalId). */
    record Approval(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf, String title,
                    UUID approverUserId, List<String> approverRoles, boolean fourEyes, BigDecimal amount) {
    }

    /** The Shopping assistant proposes the customer's own order: the customer requests, is served and decides. */
    static Approval customerOrder(UUID customerId) {
        return new Approval(UUID.randomUUID(), "PLACE_ORDER", "shopping", customerId, customerId,
                "Place order: 2 items from Fresh Mart, total 285.00 INR", customerId, List.of(), false,
                new BigDecimal("285.00"));
    }

    /** The Business-insights assistant proposes a refund for an admin; finance roles decide. */
    static Approval adminRefund(UUID adminId) {
        return new Approval(UUID.randomUUID(), "REFUND_PAYMENT", "admin-bi", adminId, null,
                "Refund ₹10.00 for order GA-7K3F9Q2M", null, List.of("SUPER_ADMIN", "FINANCE_ADMIN"), false,
                new BigDecimal("10.00"));
    }

    /** The fields every ai event carries. */
    private static Map<String, Object> approvalBase(Approval approval) {
        return map("approvalId", approval.approvalId(), "action", approval.action(), "agent", approval.agent(),
                "requestedBy", approval.requestedBy(), "onBehalfOf", approval.onBehalfOf(), "title", approval.title());
    }

    static ConsumerRecord<String, String> approvalRequested(Approval approval, Instant expiresAt) {
        Map<String, Object> payload = approvalBase(approval);
        payload.put("approverUserId", approval.approverUserId() == null ? null : approval.approverUserId().toString());
        payload.put("approverRoles", approval.approverRoles());
        payload.put("fourEyes", approval.fourEyes());
        payload.put("amount", approval.amount());
        payload.put("currency", approval.amount() == null ? null : "INR");
        payload.put("expiresAt", expiresAt.toString());
        payload.put("requestedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("ai.events", "ApprovalRequested", approval.approvalId(), payload);
    }

    /** {@code decision} APPROVED, REJECTED, EXPIRED or CANCELLED; {@code decidedBy} null for EXPIRED. */
    static ConsumerRecord<String, String> approvalDecided(Approval approval, String decision, UUID decidedBy,
                                                          String comment) {
        Map<String, Object> payload = approvalBase(approval);
        payload.put("decision", decision);
        payload.put("decidedBy", decidedBy == null ? null : decidedBy.toString());
        payload.put("comment", comment);
        payload.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("ai.events", "ApprovalDecided", approval.approvalId(), payload);
    }

    static ConsumerRecord<String, String> approvalExecuted(Approval approval, UUID decidedBy, String resultReference) {
        Map<String, Object> payload = approvalBase(approval);
        payload.put("decidedBy", decidedBy.toString());
        payload.put("resultReference", resultReference);
        payload.put("executedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("ai.events", "ApprovalExecuted", approval.approvalId(), payload);
    }

    static ConsumerRecord<String, String> approvalFailed(Approval approval, UUID decidedBy, String errorCode,
                                                         String errorMessage, Integer httpStatus) {
        Map<String, Object> payload = approvalBase(approval);
        payload.put("decidedBy", decidedBy == null ? null : decidedBy.toString());
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorMessage);
        payload.put("httpStatus", httpStatus);
        payload.put("failedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("ai.events", "ApprovalFailed", approval.approvalId(), payload);
    }

    /** Phase 12 §6: the Vendor assistant proposes a stock change; the vendor decides it (self-decided). */
    static Approval vendorStock(UUID vendorUserId) {
        return new Approval(UUID.randomUUID(), "ADJUST_STOCK", "vendor", vendorUserId, null,
                "Set stock of Basmati Rice to 40", vendorUserId, List.of(), false, null);
    }

    /** Phase 12 §6: a delivery manager's reassignment; DELIVERY_MANAGER, OPS_ADMIN, SUPER_ADMIN decide. */
    static Approval deliveryReassignment(UUID managerId) {
        return new Approval(UUID.randomUUID(), "REASSIGN_DELIVERY", "delivery", managerId, null,
                "Reassign GA-7K3F9Q2M to another partner", null,
                List.of("DELIVERY_MANAGER", "OPS_ADMIN", "SUPER_ADMIN"), false, null);
    }

    // ---------------------------------------------------------------- Phase 12: identity, reviews, returns

    static ConsumerRecord<String, String> userRegistered(UUID userId, String email, String fullName, String role) {
        return event("identity.events", "UserRegistered", userId, map("userId", userId, "email", email,
                "fullName", fullName, "phone", null, "role", role,
                "registeredAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
    }

    /** OrderCreated with one line of the given item (its name feeds the item_name projection). */
    static ConsumerRecord<String, String> orderCreatedWithItem(Order order, UUID itemId, String itemType,
                                                               String itemName) {
        return event("order.events", "OrderCreated", order.orderId(), map(
                "orderId", order.orderId(), "orderNumber", order.orderNumber(), "orderType", order.orderType(),
                "customerId", order.customerId(), "storeId", order.storeId(), "storeName", order.storeName(),
                "items", List.of(map("itemId", itemId, "type", itemType, "name", itemName,
                        "quantity", 1, "unitPrice", new BigDecimal("50.00"), "lineTotal", new BigDecimal("50.00"))),
                "subtotal", order.total(), "deliveryFee", new BigDecimal("0.00"), "total", order.total(),
                "currency", "INR", "cartUpdatedAt", null, "paymentDueAt", Instant.now().plusSeconds(900).toString(),
                "createdAt", Instant.now().toString()));
    }

    /** A review as review-service publishes it (key reviewId). */
    record Review(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId, UUID orderId) {
    }

    static Review review(String targetType, UUID targetId, UUID storeId, UUID customerId) {
        return new Review(UUID.randomUUID(), targetType, targetId, storeId, customerId, UUID.randomUUID());
    }

    static ConsumerRecord<String, String> reviewSubmitted(Review review, int rating, String status, String flagReason,
                                                          boolean edited) {
        return event("review.events", "ReviewSubmitted", review.reviewId(), map(
                "reviewId", review.reviewId(), "targetType", review.targetType(), "targetId", review.targetId(),
                "storeId", review.storeId(), "customerId", review.customerId(), "orderId", review.orderId(),
                "rating", rating, "status", status, "flagReason", flagReason, "edited", edited,
                "submittedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
    }

    static ConsumerRecord<String, String> reviewModerated(Review review, String decision, String reason) {
        return event("review.events", "ReviewModerated", review.reviewId(), map(
                "reviewId", review.reviewId(), "targetType", review.targetType(), "targetId", review.targetId(),
                "storeId", review.storeId(), "customerId", review.customerId(), "decision", decision,
                "reason", reason, "moderatorId", UUID.randomUUID(),
                "moderatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
    }

    static ConsumerRecord<String, String> reviewReplied(Review review, UUID repliedBy) {
        return event("review.events", "ReviewReplied", review.reviewId(), map(
                "reviewId", review.reviewId(), "targetType", review.targetType(), "targetId", review.targetId(),
                "storeId", review.storeId(), "customerId", review.customerId(), "repliedBy", repliedBy,
                "repliedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
    }

    static ConsumerRecord<String, String> ratingSummaryUpdated(String targetType, UUID targetId) {
        return event("review.events", "RatingSummaryUpdated", targetId, map(
                "targetType", targetType, "targetId", targetId, "averageRating", new BigDecimal("4.50"),
                "ratingCount", 2, "ratingDistribution", List.of(0, 0, 0, 1, 1), "summaryVersion", 3,
                "updatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
    }

    static String returnNumber() {
        return "RT-" + orderNumber().substring(3);
    }

    /** The fields every return event carries. */
    private static Map<String, Object> returnBase(Order order, UUID returnId, String returnNumber) {
        return map("returnId", returnId, "returnNumber", returnNumber, "orderId", order.orderId(),
                "orderNumber", order.orderNumber(), "orderType", order.orderType(), "customerId", order.customerId(),
                "storeId", order.storeId());
    }

    /** {@code quantities}: one returned line per value, each refunding {@code refund / lines}. */
    static ConsumerRecord<String, String> returnRequested(Order order, UUID returnId, String returnNumber,
                                                          BigDecimal refund, Instant decideBy, int... quantities) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int quantity : quantities) {
            items.add(map("itemId", UUID.randomUUID(), "name", "Basmati Rice", "quantity", quantity,
                    "refundAmount", new BigDecimal("10.00")));
        }
        Map<String, Object> payload = returnBase(order, returnId, returnNumber);
        payload.put("items", items);
        payload.put("refundAmount", refund);
        payload.put("reason", "DAMAGED");
        payload.put("note", "The bag was torn");
        payload.put("decideBy", decideBy.toString());
        payload.put("requestedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("order.events", "ReturnRequested", order.orderId(), payload);
    }

    static ConsumerRecord<String, String> returnEscalated(Order order, UUID returnId, String returnNumber,
                                                          BigDecimal refund) {
        Map<String, Object> payload = returnBase(order, returnId, returnNumber);
        payload.put("refundAmount", refund);
        payload.put("requestedAt", Instant.now().minusSeconds(86_400).toString());
        payload.put("escalatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("order.events", "ReturnEscalated", order.orderId(), payload);
    }

    static ConsumerRecord<String, String> returnApproved(Order order, UUID returnId, String returnNumber,
                                                         BigDecimal refund) {
        Map<String, Object> payload = returnBase(order, returnId, returnNumber);
        payload.put("items", List.of(map("itemId", UUID.randomUUID(), "name", "Basmati Rice", "quantity", 1,
                "refundAmount", refund)));
        payload.put("refundAmount", refund);
        payload.put("restock", true);
        payload.put("decidedBy", UUID.randomUUID().toString());
        payload.put("decidedByRole", "STORE");
        payload.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("order.events", "ReturnApproved", order.orderId(), payload);
    }

    static ConsumerRecord<String, String> returnRejected(Order order, UUID returnId, String returnNumber,
                                                         String comment) {
        Map<String, Object> payload = returnBase(order, returnId, returnNumber);
        payload.put("comment", comment);
        payload.put("decidedBy", UUID.randomUUID().toString());
        payload.put("decidedByRole", "STORE");
        payload.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return event("order.events", "ReturnRejected", order.orderId(), payload);
    }

    /** Phase 12: RefundCompleted of a return (reason RETURN, with the optional returnId). */
    static ConsumerRecord<String, String> refundCompletedForReturn(UUID orderId, BigDecimal amount, UUID returnId) {
        return payment("RefundCompleted", orderId, map("refundId", UUID.randomUUID(), "amount", amount,
                "reason", "RETURN", "totalRefunded", amount, "fullyRefunded", false, "returnId", returnId));
    }
}
