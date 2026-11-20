package com.grocery.notification.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Our own views of the payloads we consume: only the fields notification-service uses (tolerant reader; field names
 * from the central schemas in libs/grocery-event-schemas/src/main/avro/&lt;topic&gt;/*.avsc). Unknown fields are
 * ignored; grocery-common's Avro deserializer hands the listener the same JSON the producer wrote.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    // ---------------------------------------------------------------- order.events

    /**
     * OrderCreated, OrderAwaitingPayment, OrderConfirmed, OrderRejected, OrderCancelled, (Phase 8) OrderAccepted,
     * OrderPreparing, OrderReadyForPickup and (Phase 9) OrderOutForDelivery, OrderDelivered, OrderDeliveryFailed (the
     * fields each one has; {@code reason} / {@code note} are the failure's on OrderDeliveryFailed).
     */
    public record OrderEvent(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                             String storeName, BigDecimal total, List<OrderItem> items, String previousStatus,
                             String reason, String note, Integer readyInMinutes) {
    }

    /** An order line: {@code itemId} = productId / menuItemId, {@code type} PRODUCT / MENU_ITEM (Phase 12: names). */
    public record OrderItem(UUID itemId, String type, String name, Integer quantity) {
    }

    /**
     * Phase 12: ReturnRequested, ReturnApproved, ReturnRejected, ReturnEscalated (key orderId) with the fields each one
     * has: {@code items}, {@code reason}, {@code note}, {@code decideBy} on Requested; {@code items}, {@code restock}
     * on Approved; {@code comment} on Rejected; {@code decidedByRole} on Approved / Rejected.
     */
    public record ReturnEvent(UUID returnId, String returnNumber, UUID orderId, String orderNumber, String orderType,
                              UUID customerId, UUID storeId, List<ReturnLine> items, BigDecimal refundAmount,
                              String reason, String comment, Instant decideBy, String decidedByRole) {
    }

    public record ReturnLine(UUID itemId, String name, Integer quantity, BigDecimal refundAmount) {
    }

    /**
     * Phase 10: OrderStuckDetected — the watchdog found the order in {@code status} longer than its threshold
     * ({@code reason}); {@code since} = when it entered the status.
     */
    public record OrderStuckDetected(UUID orderId, String orderNumber, String orderType, UUID customerId,
                                     UUID storeId, String status, String reason, Instant since, Instant detectedAt) {
    }

    // ---------------------------------------------------------------- payment.events

    /** PaymentInitiated, PaymentCaptured, PaymentFailed, PaymentExpired, RefundCompleted, RefundFailed. */
    public record PaymentEvent(UUID orderId, UUID paymentId, UUID refundId, BigDecimal amount, String reason,
                               String errorCode, String errorDescription, Boolean retryable, UUID returnId) {
    }

    // ---------------------------------------------------------------- vendor / restaurant / delivery / identity

    /** Vendor* lifecycle events: {vendorId, ownerUserId, businessName, reason, actorUserId}. */
    public record VendorLifecycle(UUID vendorId, UUID ownerUserId, String businessName, String reason) {
    }

    /** Restaurant* lifecycle events: {restaurantId, ownerUserId, name, reason, actorUserId}. */
    public record RestaurantLifecycle(UUID restaurantId, UUID ownerUserId, String name, String reason) {
    }

    /** RestaurantListingUpdated (the full public listing; we need only these). */
    public record RestaurantListing(UUID restaurantId, UUID ownerUserId, String name, String status,
                                    Boolean acceptingOrders) {
    }

    /** DeliveryPartner* lifecycle events: {partnerId (= userId), fullName, reason, actorUserId}. */
    public record PartnerLifecycle(UUID partnerId, String fullName, String reason) {
    }

    /** UserSuspended, UserReactivated. */
    public record UserEvent(UUID userId) {
    }

    /** Phase 12: UserRegistered — the email address projection (self-registered users; one role). */
    public record UserRegistered(UUID userId, String email, String fullName, String role) {
    }

    /** PasswordResetRequested: the raw token builds the emailed link; it is never logged or stored elsewhere. */
    public record PasswordResetRequested(UUID userId, String email, String fullName, String token,
                                         Instant expiresAt) {
    }

    /** PasswordChanged: {@code method} CHANGE (signed in) or RESET (emailed link). */
    public record PasswordChanged(UUID userId, String email, String fullName, String method, Instant changedAt) {
    }

    /** customer.events CustomerProfileUpdated (customerId = userId): the name for the email address projection. */
    public record CustomerProfileUpdated(UUID customerId, String fullName) {
    }

    /**
     * Phase 8 store order events — VendorOrder* / RestaurantOrder* {Received, Accepted, Rejected, Preparing,
     * ReadyForPickup} — with the fields each one has: the store in {@code vendorId} / {@code restaurantId};
     * {@code ownerUserId}, {@code itemCount}, {@code total}, {@code acceptBy} on Received; {@code reason},
     * {@code note}, {@code auto} on Rejected.
     */
    public record StoreOrderEvent(UUID orderId, String orderNumber, UUID vendorId, UUID restaurantId,
                                  UUID ownerUserId, Integer itemCount, BigDecimal total, Instant acceptBy,
                                  String reason, String note, Boolean auto) {
    }

    // ---------------------------------------------------------------- delivery.events / delivery.location (Phase 9)

    /**
     * The delivery events (DeliveryOfferCreated, DeliveryOfferClosed, DeliveryPartnerAssigned, DeliveryPartnerUnassigned,
     * DeliveryPickedUp, DeliveryCompleted, DeliveryFailed, DeliveryAssignmentFailed, DeliveryCancelled) with the
     * fields each one has: {@code offerId}, {@code storeName}, {@code distanceKm}, {@code expiresAt} on the offer;
     * {@code outcome} on its closing; {@code partnerName} on the assignment; {@code partnerId} on all but
     * DeliveryAssignmentFailed (null on a DeliveryCancelled without partner).
     */
    public record DeliveryEvent(UUID deliveryId, UUID orderId, String orderNumber, UUID offerId, UUID partnerId,
                                String partnerName, String storeName, BigDecimal distanceKm, Instant expiresAt,
                                String outcome) {
    }

    /** DeliveryLocationUpdated (delivery.location, no outbox, possibly without the outbox headers). */
    public record DeliveryLocation(UUID deliveryId, UUID orderId, UUID partnerId, Double latitude, Double longitude,
                                   Double heading, Instant recordedAt) {
    }

    // ---------------------------------------------------------------- inventory.events / pricing.events

    /** StockAdjusted, LowStockDetected, ProductAvailabilityChanged. */
    public record StockEvent(UUID productId, UUID vendorId, String productName, Integer available, Integer threshold,
                             Boolean inStock) {
    }

    /** PriceChanged, PriceChangeRequested, PriceChangeApproved, PriceChangeRejected, PriceChangeCancelled. */
    public record PriceEvent(UUID requestId, UUID productId, UUID vendorId, String productName,
                             BigDecimal oldSellingPrice, BigDecimal newSellingPrice, BigDecimal changePercent,
                             String reason) {
    }

    // ---------------------------------------------------------------- ai.events (Phase 11)

    /**
     * ApprovalRequested, ApprovalDecided, ApprovalExecuted, ApprovalFailed (schemas 1.4.0) with the fields each one
     * has: every event carries {@code approvalId}, {@code action}, {@code agent}, {@code requestedBy},
     * {@code onBehalfOf} (the customer it is for; null for admin actions) and {@code title}; {@code approverUserId},
     * {@code approverRoles}, {@code expiresAt} only on ApprovalRequested; {@code decision}, {@code comment} on
     * ApprovalDecided; {@code resultReference} on ApprovalExecuted; {@code errorCode}, {@code errorMessage} on
     * ApprovalFailed.
     */
    public record ApprovalEvent(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf,
                                String title, UUID approverUserId, List<String> approverRoles, Instant expiresAt,
                                String decision, String comment, String resultReference,
                                String errorCode, String errorMessage) {
    }

    // ---------------------------------------------------------------- review.events (Phase 12)

    /**
     * ReviewSubmitted, ReviewModerated, ReviewReplied (schemas 1.5.0, key reviewId) with the fields each one has:
     * {@code orderId}, {@code rating}, {@code status} (PUBLISHED / FLAGGED), {@code flagReason}, {@code edited} on
     * Submitted; {@code decision} (PUBLISHED / REJECTED / REMOVED), {@code reason} on Moderated. The review's title and
     * comment are not in the events.
     */
    public record ReviewEvent(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                              UUID orderId, Integer rating, String status, String flagReason, Boolean edited,
                              String decision, String reason) {
    }
}
