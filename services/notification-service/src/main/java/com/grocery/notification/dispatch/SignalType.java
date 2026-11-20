package com.grocery.notification.dispatch;

/** The {@code type} of a {@code signal} event (contract §2): what the client should refetch. */
public final class SignalType {

    public static final String ORDER_UPDATED = "ORDER_UPDATED";
    public static final String ADMIN_ORDERS_CHANGED = "ADMIN_ORDERS_CHANGED";
    public static final String STOCK_CHANGED = "STOCK_CHANGED";
    public static final String PRICE_CHANGED = "PRICE_CHANGED";
    public static final String RESTAURANT_UPDATED = "RESTAURANT_UPDATED";
    public static final String INVENTORY_UPDATED = "INVENTORY_UPDATED";
    public static final String PRICING_UPDATED = "PRICING_UPDATED";
    public static final String APPROVALS_CHANGED = "APPROVALS_CHANGED";
    public static final String ACCOUNT_UPDATED = "ACCOUNT_UPDATED";
    /** Phase 8: {@code orderId}, {@code status} → the store owner; the order board refreshes. */
    public static final String STORE_ORDERS_CHANGED = "STORE_ORDERS_CHANGED";
    /** Phase 9: {@code deliveryId}, {@code offerId} → the partner; their offer list refreshes. */
    public static final String DELIVERY_OFFERS_CHANGED = "DELIVERY_OFFERS_CHANGED";
    /** Phase 9: {@code deliveryId}, {@code orderId} → the assigned partner; their active delivery refreshes. */
    public static final String DELIVERY_UPDATED = "DELIVERY_UPDATED";
    /** Phase 9: {@code deliveryId}, {@code orderId} → SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER (delivery monitor). */
    public static final String ADMIN_DELIVERIES_CHANGED = "ADMIN_DELIVERIES_CHANGED";
    /**
     * Phase 9: {@code orderId}, {@code latitude}, {@code longitude}, {@code heading}, {@code at} → the order's customer
     * only; the tracking map moves the partner's marker without refetching.
     */
    public static final String DELIVERY_LOCATION = "DELIVERY_LOCATION";
    /**
     * Phase 11: {@code approvalId} → every party of an assistant's approval request (requester, the customer it is
     * for, the approver user or roles); approval cards, lists and to-decide queues refresh.
     */
    public static final String AI_APPROVALS_CHANGED = "AI_APPROVALS_CHANGED";
    /**
     * Phase 12: {@code reviewId}, {@code targetType}, {@code targetId}, {@code event} → the owner of the store the
     * review belongs to (never for DELIVERY_PARTNER reviews); the store's reviews page refreshes. Sent when a review
     * appears (ReviewSubmitted PUBLISHED, ReviewModerated PUBLISHED), disappears (ReviewModerated REMOVED) or got the
     * store's reply (ReviewReplied).
     */
    public static final String STORE_REVIEWS_CHANGED = "STORE_REVIEWS_CHANGED";
    /** Phase 12: {@code reviewId}, {@code status}, {@code event} → the reviewing customer; "my reviews" refreshes. */
    public static final String REVIEW_UPDATED = "REVIEW_UPDATED";
    /** Phase 12: {@code reviewId}, {@code status}, {@code event} → all admin roles; the moderation queue refreshes. */
    public static final String ADMIN_REVIEWS_CHANGED = "ADMIN_REVIEWS_CHANGED";
    /**
     * Phase 12: {@code returnId}, {@code orderId}, {@code status} (REQUESTED, ESCALATED, APPROVED, REJECTED) → the owner
     * of the order's store; the store's returns page refreshes.
     */
    public static final String STORE_RETURNS_CHANGED = "STORE_RETURNS_CHANGED";
    /** Phase 12: {@code returnId}, {@code orderId}, {@code status} → all admin roles; the admin returns page refreshes. */
    public static final String ADMIN_RETURNS_CHANGED = "ADMIN_RETURNS_CHANGED";
    /** Sent by the stream itself to one connection; never published. */
    public static final String RESYNC = "RESYNC";

    /** The {@code kind} of APPROVALS_CHANGED for price change requests. */
    public static final String KIND_PRICE_CHANGE = "PRICE_CHANGE";

    private SignalType() {
    }
}
