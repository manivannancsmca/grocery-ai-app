package com.grocery.vendor.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Store order events PUBLISHED on {@code vendor.events} (v1, Phase 8 contract §5): aggregate {@code VendorOrder}, key
 * orderId. The payloads are exactly the central schemas {@code avro/vendor.events/VendorOrder*.avsc}
 * (grocery-event-schemas 1.1.0). Consumers: order-service ({@code order-service.vendor-events}, ignores Received),
 * notification-service. The vendor lifecycle consumers (identity, catalog-command) ignore these types.
 */
public final class VendorOrderEvents {

    public static final String TOPIC = VendorEvents.TOPIC;
    public static final String AGGREGATE = "VendorOrder";

    public static final String RECEIVED = "VendorOrderReceived";
    public static final String ACCEPTED = "VendorOrderAccepted";
    public static final String REJECTED = "VendorOrderRejected";
    public static final String PREPARING = "VendorOrderPreparing";
    public static final String READY_FOR_PICKUP = "VendorOrderReadyForPickup";

    private VendorOrderEvents() {
    }

    /** A paid order is on the board, waiting for acceptance until {@code acceptBy}; {@code itemCount} = units. */
    public record VendorOrderReceived(UUID orderId, String orderNumber, UUID vendorId, UUID ownerUserId, int itemCount,
                                      BigDecimal total, Instant acceptBy, Instant receivedAt) {
    }

    public record VendorOrderAccepted(UUID orderId, String orderNumber, UUID vendorId, int readyInMinutes,
                                      Instant readyBy, Instant acceptedAt, UUID actorUserId) {
    }

    /** {@code reason} OUT_OF_STOCK / STORE_CLOSED / TOO_BUSY / OTHER, or NO_RESPONSE with {@code auto} (no actor). */
    public record VendorOrderRejected(UUID orderId, String orderNumber, UUID vendorId, String reason, String note,
                                      boolean auto, Instant rejectedAt, UUID actorUserId) {
    }

    public record VendorOrderPreparing(UUID orderId, String orderNumber, UUID vendorId, Instant preparingAt,
                                       UUID actorUserId) {
    }

    public record VendorOrderReadyForPickup(UUID orderId, String orderNumber, UUID vendorId, Instant readyAt,
                                            UUID actorUserId) {
    }
}
