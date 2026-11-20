package com.grocery.events.schemas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module ships exactly these (topic, eventType) schemas. Adding, removing or renaming an event is a deliberate
 * change: update this list, the README event table and (for a new event) its compatibility baseline together.
 */
class SchemaManifestTest {

    /** Producer service per topic (one producer per topic). */
    static final Map<String, String> PRODUCERS = plus(plus(plus(Map.of(
            "identity.events", "identity-service",
            "vendor.events", "vendor-service",
            "restaurant.events", "restaurant-service",
            "delivery.events", "delivery-service",
            "delivery.location", "delivery-service",
            "catalog.events", "catalog-command-service",
            "pricing.events", "pricing-service",
            "inventory.events", "inventory-service",
            "order.events", "order-service",
            "payment.events", "payment-service"),
            // Phase 11 (1.4.0): approvals of the AI assistants
            "ai.events", "ai-orchestrator-service"),
            // Phase 12 (1.5.0): reviews and ratings
            "review.events", "review-service"),
            // 1.6.0: a customer's profile changes reach their account (identity) and their emails (notification)
            "customer.events", "customer-service");

    /** topic -> file base names ({@code <EventType>}, or {@code <EventType>.v<n>} for a breaking version). */
    static final Map<String, List<String>> EXPECTED = plus(plus(plus(Map.of(
            "identity.events", List.of("UserRegistered", "UserSuspended", "UserReactivated",
                    // 1.6.0: password management (forgot / reset / change)
                    "PasswordResetRequested", "PasswordChanged",
                    // 1.8.0: the sign-in and account audit, for the central audit trail (reporting-service)
                    "SecurityEventRecorded"),
            "vendor.events", List.of("VendorRegistered", "VendorSubmitted", "VendorApproved", "VendorRejected",
                    "VendorSuspended", "VendorReinstated",
                    // Phase 8: store-side order fulfilment (aggregate VendorOrder, key orderId)
                    "VendorOrderReceived", "VendorOrderAccepted", "VendorOrderRejected", "VendorOrderPreparing",
                    "VendorOrderReadyForPickup",
                    // Phase 12: purchasing (aggregate Vendor, key vendorId)
                    "GoodsReceived",
                    // 1.8.0: platform settings changes (aggregate PlatformSetting, key settingKey)
                    "PlatformSettingChanged"),
            "restaurant.events", List.of("RestaurantRegistered", "RestaurantSubmitted", "RestaurantApproved",
                    "RestaurantRejected", "RestaurantSuspended", "RestaurantReinstated", "RestaurantListingUpdated",
                    // Phase 8: store-side order fulfilment (aggregate RestaurantOrder, key orderId)
                    "RestaurantOrderReceived", "RestaurantOrderAccepted", "RestaurantOrderRejected",
                    "RestaurantOrderPreparing", "RestaurantOrderReadyForPickup",
                    "PlatformSettingChanged"),
            "delivery.events", List.of("DeliveryPartnerRegistered", "DeliveryPartnerSubmitted",
                    "DeliveryPartnerApproved", "DeliveryPartnerRejected", "DeliveryPartnerSuspended",
                    "DeliveryPartnerReinstated",
                    // Phase 9: deliveries (aggregate Delivery, key orderId)
                    "DeliveryOfferCreated", "DeliveryOfferClosed", "DeliveryPartnerAssigned",
                    "DeliveryPartnerUnassigned", "DeliveryPickedUp", "DeliveryCompleted", "DeliveryFailed",
                    "DeliveryAssignmentFailed", "DeliveryCancelled",
                    "PlatformSettingChanged"),
            "delivery.location", List.of("DeliveryLocationUpdated"),
            "catalog.events", List.of("ProductUpserted", "CategoryChanged"),
            "pricing.events", List.of("PriceChanged", "PriceChangeRequested", "PriceChangeApproved",
                    "PriceChangeRejected", "PriceChangeCancelled", "PlatformSettingChanged"),
            "inventory.events", List.of("StockAdjusted", "ProductAvailabilityChanged", "LowStockDetected",
                    "StockReserved", "StockReservationFailed", "StockReservationExpired", "StockReleased",
                    "StockCommitted"),
            "order.events", List.of("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderRejected",
                    "OrderCancelled", "OrderAccepted", "OrderPreparing", "OrderReadyForPickup",
                    "OrderOutForDelivery", "OrderDelivered", "OrderDeliveryFailed",
                    // Phase 10: stuck-order watchdog (1.3.0)
                    "OrderStuckDetected",
                    // Phase 12 (1.5.0): returns (aggregate Order, key orderId)
                    "ReturnRequested", "ReturnApproved", "ReturnRejected", "ReturnEscalated",
                    "PlatformSettingChanged"),
            "payment.events", List.of("PaymentInitiated", "PaymentCaptured", "PaymentFailed", "PaymentExpired",
                    "RefundCompleted", "RefundFailed")),
            "ai.events", List.of("ApprovalRequested", "ApprovalDecided", "ApprovalExecuted", "ApprovalFailed")),
            "review.events", List.of("ReviewSubmitted", "ReviewModerated", "ReviewReplied", "RatingSummaryUpdated",
                    "PlatformSettingChanged")),
            "customer.events", List.of("CustomerProfileUpdated"));

    /** Map.of takes at most 10 pairs: each further topic is added here. */
    private static <V> Map<String, V> plus(Map<String, V> base, String key, V value) {
        Map<String, V> all = new java.util.HashMap<>(base);
        all.put(key, value);
        return Map.copyOf(all);
    }

    static Set<String> expectedIds() {
        return EXPECTED.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(name -> e.getKey() + "/" + name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theModuleShipsExactlyTheExpectedSchemas() {
        Set<String> actual = EventSchemaFile.all().stream().map(EventSchemaFile::id)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual).containsExactlyElementsOf(expectedIds());
        // 47 (1.0.0) + 13 (1.1.0) + 14 (1.2.0) + 1 (1.3.0) + 4 ai.events (1.4.0) + 9 Phase 12 (1.5.0)
        // + 3 (1.6.0: PasswordResetRequested, PasswordChanged, CustomerProfileUpdated)
        // + 0 (1.7.0 only adds optional coupon fields to OrderCreated and OrderConfirmed)
        // + 7 (1.8.0: SecurityEventRecorded, PlatformSettingChanged on six topics)
        assertThat(actual).hasSize(98);
    }

    @Test
    void everySchemaIsOnTheClasspathAtAvroTopicEventType() throws IOException {
        for (String id : expectedIds()) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("avro/" + id + ".avsc")) {
                assertThat(in).as("avro/%s.avsc", id).isNotNull();
            }
        }
    }

    @Test
    void everyTopicHasItsProducerAndTheTopLevelDocNamesIt() {
        assertThat(PRODUCERS.keySet()).isEqualTo(EXPECTED.keySet());
        for (EventSchemaFile file : EventSchemaFile.all()) {
            assertThat(file.parse().rawSchema().getDoc()).as(file.id())
                    .contains("Producer: " + PRODUCERS.get(file.topic()) + ", topic " + file.topic());
        }
    }

    @Test
    void subjectsFollowTopicRecordNameStrategy() {
        EventSchemaFile orderCreated = EventSchemaFile.all().stream()
                .filter(file -> file.id().equals("order.events/OrderCreated")).findFirst().orElseThrow();
        assertThat(orderCreated.fullName()).isEqualTo("com.grocery.events.order.OrderCreated");
        assertThat(orderCreated.subject()).isEqualTo("order.events-com.grocery.events.order.OrderCreated");
        assertThat(orderCreated.classpathLocation()).isEqualTo("avro/order.events/OrderCreated.avsc");

        EventSchemaFile partner = EventSchemaFile.all().stream()
                .filter(file -> file.id().equals("delivery.events/DeliveryPartnerApproved")).findFirst()
                .orElseThrow();
        assertThat(partner.subject())
                .isEqualTo("delivery.events-com.grocery.events.delivery.DeliveryPartnerApproved");
    }

    @Test
    void aBreakingVersionFileMapsToItsOwnRecordAndSubject() {
        var root = java.nio.file.Path.of("avro");
        EventSchemaFile v2 = EventSchemaFile.of(root, root.resolve("order.events").resolve("OrderCreated.v2.avsc"));
        assertThat(v2.eventType()).isEqualTo("OrderCreated");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.expectedName()).isEqualTo("OrderCreatedV2");
        assertThat(v2.subject()).isEqualTo("order.events-com.grocery.events.order.OrderCreatedV2");
    }
}
