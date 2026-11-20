package com.grocery.reporting.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Our own views of the payloads we consume: only the fields reporting-service uses (tolerant reader; field names from
 * the central schemas in libs/grocery-event-schemas/src/main/avro/&lt;topic&gt;/*.avsc, 1.7.0). Unknown fields are
 * ignored; grocery-common's Avro deserializer hands the listener the same JSON the producer wrote.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    // ---------------------------------------------------------------- order.events

    /**
     * OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected, OrderDelivered, OrderDeliveryFailed with the fields
     * each one has: {@code storeName}, {@code subtotal}, {@code deliveryFee}, {@code currency}, {@code createdAt} on
     * OrderCreated; {@code items}, {@code total} and the optional coupon fields ({@code couponCode}, {@code discount},
     * {@code deliveryFeeDiscount}, 1.5.0; {@code couponName}, {@code couponFundedBy}, 1.7.0 — absent in older events)
     * on OrderCreated and OrderConfirmed; {@code confirmedAt}, the optional
     * {@code subtotal} and {@code storeLocation} on OrderConfirmed; {@code previousStatus}, {@code reason},
     * {@code cancelledBy}, {@code cancelledAt} on OrderCancelled; {@code reason} on OrderRejected (no time: the
     * occurredAt header); {@code partnerId} and {@code deliveredAt} / {@code reason}, {@code failedAt} on the
     * delivery outcomes.
     */
    public record OrderEvent(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                             String storeName, List<OrderItem> items, BigDecimal subtotal, BigDecimal deliveryFee,
                             BigDecimal total, String currency, String couponCode, BigDecimal discount,
                             BigDecimal deliveryFeeDiscount, Instant createdAt, Instant confirmedAt,
                             String previousStatus, String reason, String cancelledBy, Instant cancelledAt,
                             UUID partnerId, Instant deliveredAt, Instant failedAt, StoreLocation storeLocation,
                             String couponName, String couponFundedBy) {
    }

    public record OrderItem(UUID itemId, String type, String name, Integer quantity, BigDecimal unitPrice,
                            BigDecimal lineTotal) {
    }

    /** OrderConfirmed.storeLocation (Phase 9): only the store name is used. */
    public record StoreLocation(String name) {
    }

    /** ReturnApproved (Phase 12). */
    public record ReturnApproved(UUID returnId, String returnNumber, UUID orderId, String orderNumber,
                                 String orderType, UUID customerId, UUID storeId, List<ReturnLine> items,
                                 BigDecimal refundAmount, Boolean restock, String decidedByRole, Instant decidedAt) {
    }

    public record ReturnLine(UUID itemId, String name, Integer quantity, BigDecimal refundAmount) {
    }

    // ---------------------------------------------------------------- payment.events

    /** PaymentCaptured ({@code late}: captured after expiry, refunded automatically with reason LATE_CAPTURE). */
    public record PaymentCaptured(UUID orderId, UUID paymentId, BigDecimal amount, Instant capturedAt, Boolean late) {
    }

    /** RefundCompleted ({@code returnId} for reason RETURN, 1.5.0). The payload carries no time. */
    public record RefundCompleted(UUID orderId, UUID paymentId, UUID refundId, BigDecimal amount, String reason,
                                  UUID returnId) {
    }

    // ---------------------------------------------------------------- delivery.events

    /**
     * DeliveryPartnerAssigned ({@code partnerName}, {@code assignedAt}), DeliveryPickedUp ({@code pickedUpAt}),
     * DeliveryCompleted ({@code deliveredAt}).
     */
    public record DeliveryEvent(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId, String partnerName,
                                Instant assignedAt, Instant pickedUpAt, Instant deliveredAt) {
    }

    // ---------------------------------------------------------------- review.events

    public record ReviewSubmitted(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                                  UUID orderId, Integer rating, String status, String flagReason, Boolean edited,
                                  Instant submittedAt) {
    }

    /** {@code ratingDistribution}: index 0 = 1 star … index 4 = 5 stars. */
    public record RatingSummaryUpdated(String targetType, UUID targetId, BigDecimal averageRating,
                                       Integer ratingCount, List<Integer> ratingDistribution, Long summaryVersion,
                                       Instant updatedAt) {
    }

    // ---------------------------------------------------------------- vendor.events

    public record GoodsReceived(UUID vendorId, UUID purchaseOrderId, String purchaseOrderNumber, UUID receiptId,
                                UUID supplierId, List<ReceiptLine> lines, Instant receivedAt) {
    }

    public record ReceiptLine(UUID productId, Integer quantity, BigDecimal unitCost) {
    }
}
