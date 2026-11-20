package com.grocery.order.event;

import com.grocery.order.entity.Actor;
import com.grocery.order.entity.CancellationReason;
import com.grocery.order.entity.DeciderRole;
import com.grocery.order.entity.ItemType;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.RejectionReason;
import com.grocery.order.entity.ReturnReason;
import com.grocery.order.entity.StuckReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code order.events} (v1, docs/api/phase-6-orders-and-payments.md §6,
 * phase-8-vendor-and-restaurant-fulfillment.md §4, phase-9-delivery.md §6, phase-10-event-driven-hardening.md §4 and
 * phase-12-post-mvp-features.md §2, §3; schemas in grocery-event-schemas 1.7.0),
 * aggregate type {@code Order}, key = orderId. Consumers: inventory ({@code inventory-service.order-events}:
 * OrderCreated for grocery orders, OrderConfirmed, OrderCancelled, OrderOutForDelivery, ReturnApproved), cart
 * ({@code cart-service.order-events}: OrderCreated), payment ({@code payment-service.order-events}:
 * OrderAwaitingPayment, OrderCancelled, ReturnApproved), pricing (OrderCreated, OrderCancelled, OrderRejected: coupon
 * redemptions), vendor / restaurant (OrderConfirmed, OrderCancelled, OrderOutForDelivery), delivery (OrderConfirmed,
 * OrderAccepted, OrderReadyForPickup, OrderCancelled) and notification (all). Money = JSON number, 2 decimals.
 */
public final class OrderEvents {

    public static final String TOPIC = "order.events";
    public static final String ORDER_AGGREGATE = "Order";

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_AWAITING_PAYMENT = "OrderAwaitingPayment";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_REJECTED = "OrderRejected";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_ACCEPTED = "OrderAccepted";
    public static final String ORDER_PREPARING = "OrderPreparing";
    public static final String ORDER_READY_FOR_PICKUP = "OrderReadyForPickup";
    public static final String ORDER_OUT_FOR_DELIVERY = "OrderOutForDelivery";
    public static final String ORDER_DELIVERED = "OrderDelivered";
    public static final String ORDER_DELIVERY_FAILED = "OrderDeliveryFailed";
    /** Phase 10: the stuck-order watchdog flagged the order (schemas 1.3.0); the order itself is not changed. */
    public static final String ORDER_STUCK_DETECTED = "OrderStuckDetected";
    /** Phase 12 §3: returns (schemas 1.5.0). */
    public static final String RETURN_REQUESTED = "ReturnRequested";
    public static final String RETURN_APPROVED = "ReturnApproved";
    public static final String RETURN_REJECTED = "ReturnRejected";
    public static final String RETURN_ESCALATED = "ReturnEscalated";

    private OrderEvents() {
    }

    /** A line as the consumers need it ({@code itemId} = productId or menuItemId). */
    public record Item(UUID itemId, ItemType type, String name, int quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal) {
    }

    public record Address(String label, String recipientName, String phone, String line1, String line2,
                          String landmark, String city, String state, String postalCode, Double latitude,
                          Double longitude) {
    }

    /**
     * Every new order. {@code storeId} = vendorId (GROCERY) or restaurantId (RESTAURANT); {@code cartUpdatedAt} = the
     * cart version the order was placed from (cart-service empties the cart only if it has not changed since).
     * Phase 12 (1.5.0): {@code couponCode}, {@code discount}, {@code deliveryFeeDiscount} — all three null without a
     * coupon; {@code total} is the discounted total (pricing-service confirms the redemption on this event).
     * 1.7.0: {@code couponName} and {@code couponFundedBy} (PLATFORM / STORE) for the reports — null without a coupon,
     * and for orders placed before they were stored.
     */
    public record OrderCreated(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                               String storeName, List<Item> items, BigDecimal subtotal, BigDecimal deliveryFee,
                               BigDecimal total, String currency, Instant cartUpdatedAt, Instant paymentDueAt,
                               Instant createdAt, String couponCode, BigDecimal discount,
                               BigDecimal deliveryFeeDiscount, String couponName, String couponFundedBy) {
    }

    /** The order can be paid: payment-service creates the Razorpay order ({@code expiresAt} = payment due time). */
    public record OrderAwaitingPayment(UUID orderId, String orderNumber, UUID customerId, BigDecimal amount,
                                       String currency, Instant expiresAt, String contactPhone) {
    }

    /**
     * Where the order is picked up (Phase 9), snapshotted from the checkout preview. Only sent with both coordinates;
     * {@code name} / {@code city} may be null.
     */
    public record StoreLocation(String name, String city, double latitude, double longitude) {
    }

    /**
     * The order was paid; vendor-service (GROCERY) / restaurant-service (RESTAURANT) create the store order from it,
     * delivery-service the delivery. {@code subtotal} (sum of the line totals) and {@code note} (the customer's
     * delivery note) were added in Phase 8 (optional in the schema, always written now; {@code note} null when the
     * customer left none); {@code storeLocation} in Phase 9 (v3, optional: null when the store had no coordinates);
     * {@code couponCode}, {@code discount}, {@code deliveryFeeDiscount} in Phase 12 (null without a coupon);
     * {@code couponName} and {@code couponFundedBy} in 1.7.0 (as on OrderCreated).
     */
    public record OrderConfirmed(UUID orderId, String orderNumber, OrderType orderType, UUID customerId,
                                 UUID storeId, List<Item> items, BigDecimal total, Address deliveryAddress,
                                 Instant confirmedAt, BigDecimal subtotal, String note, StoreLocation storeLocation,
                                 String couponCode, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                                 String couponName, String couponFundedBy) {
    }

    /** The store accepted the paid order (Phase 8); {@code readyBy} = when the store expects it to be ready. */
    public record OrderAccepted(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                                int readyInMinutes, Instant readyBy, Instant acceptedAt) {
    }

    /** The store started preparing (restaurant) or packing (grocery) the order (Phase 8). */
    public record OrderPreparing(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                                 Instant preparingAt) {
    }

    /** The order is ready for a delivery partner to pick up (Phase 8; delivery arrives in Phase 9). */
    public record OrderReadyForPickup(UUID orderId, String orderNumber, OrderType orderType, UUID customerId,
                                      UUID storeId, Instant readyAt) {
    }

    /** The partner picked the order up (Phase 9): inventory commits the held stock, the store order leaves the board. */
    public record OrderOutForDelivery(UUID orderId, String orderNumber, OrderType orderType, UUID customerId,
                                      UUID storeId, UUID partnerId, Instant pickedUpAt) {
    }

    /** The order was delivered (Phase 9). */
    public record OrderDelivered(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                                 UUID partnerId, Instant deliveredAt) {
    }

    /**
     * The order could not be delivered after pickup (Phase 9): {@code reason} CUSTOMER_UNREACHABLE | ADDRESS_NOT_FOUND
     * | CUSTOMER_REFUSED | OTHER, {@code note} the partner's note (null if none). An admin decides on a refund.
     */
    public record OrderDeliveryFailed(UUID orderId, String orderNumber, OrderType orderType, UUID customerId,
                                      UUID storeId, UUID partnerId, String reason, String note, Instant failedAt) {
    }

    public record UnavailableItem(UUID itemId, int requested, int available) {
    }

    public record OrderRejected(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                                RejectionReason reason, List<UnavailableItem> unavailableItems) {
    }

    /** The single compensation trigger: inventory releases stock, payment cancels the session or refunds. */
    public record OrderCancelled(UUID orderId, String orderNumber, OrderType orderType, UUID customerId, UUID storeId,
                                 OrderStatus previousStatus, CancellationReason reason, Actor cancelledBy,
                                 UUID actorUserId, String note, Instant cancelledAt) {
    }

    /**
     * Phase 10 (docs/api/phase-10-event-driven-hardening.md §4): the order stayed in {@code status} longer than its
     * threshold. Published once per order and status (first detection); {@code since} = when it entered the status,
     * {@code detectedAt} = the watchdog's run. Consumer: notification ({@code STUCK_ORDER} to the admins).
     */
    public record OrderStuckDetected(UUID orderId, String orderNumber, OrderType orderType, UUID customerId,
                                     UUID storeId, OrderStatus status, StuckReason reason, Instant since,
                                     Instant detectedAt) {
    }

    // ---- Phase 12 §3: returns (grocery-event-schemas 1.5.0), key orderId -----------------------------------------

    /** A returned line: the order line's item and name, the quantity and its refund (after the coupon discount). */
    public record ReturnLine(UUID itemId, String name, int quantity, BigDecimal refundAmount) {
    }

    /**
     * The customer asked to return items of a delivered order; the store decides by {@code decideBy}. Consumer:
     * notification ({@code RETURN_REQUESTED} to the store owner).
     */
    public record ReturnRequested(UUID returnId, String returnNumber, UUID orderId, String orderNumber,
                                  OrderType orderType, UUID customerId, UUID storeId, List<ReturnLine> items,
                                  BigDecimal refundAmount, ReturnReason reason, String note, Instant decideBy,
                                  Instant requestedAt) {
    }

    /**
     * The store or an admin approved it: payment-service refunds {@code refundAmount} (reason RETURN), inventory puts
     * the items back when {@code restock} (GROCERY only), notification tells the customer.
     */
    public record ReturnApproved(UUID returnId, String returnNumber, UUID orderId, String orderNumber,
                                 OrderType orderType, UUID customerId, UUID storeId, List<ReturnLine> items,
                                 BigDecimal refundAmount, boolean restock, UUID decidedBy, DeciderRole decidedByRole,
                                 Instant decidedAt) {
    }

    /** The store or an admin rejected it ({@code comment} is shown to the customer); nothing is refunded. */
    public record ReturnRejected(UUID returnId, String returnNumber, UUID orderId, String orderNumber,
                                 OrderType orderType, UUID customerId, UUID storeId, String comment, UUID decidedBy,
                                 DeciderRole decidedByRole, Instant decidedAt) {
    }

    /** The store did not decide by {@code decideBy}: admins decide it now (notification: {@code RETURN_ESCALATED}). */
    public record ReturnEscalated(UUID returnId, String returnNumber, UUID orderId, String orderNumber,
                                  OrderType orderType, UUID customerId, UUID storeId, BigDecimal refundAmount,
                                  Instant requestedAt, Instant escalatedAt) {
    }
}
