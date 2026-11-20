package com.grocery.notification.entity;

/** Persistent notification types (contract §4), each with its fixed category and severity. */
public enum NotificationType {

    ORDER_CONFIRMED(Category.ORDER, Severity.SUCCESS),
    /** Phase 8: from VendorOrderReceived / RestaurantOrderReceived (no longer from OrderConfirmed). */
    NEW_ORDER(Category.ORDER, Severity.INFO),
    ORDER_REJECTED(Category.ORDER, Severity.ERROR),
    ORDER_CANCELLED(Category.ORDER, Severity.WARNING),
    /** Phase 8: the store accepted the customer's order. */
    ORDER_ACCEPTED(Category.ORDER, Severity.SUCCESS),
    /** Phase 8: the order is ready for pickup. */
    ORDER_READY(Category.ORDER, Severity.INFO),
    /** Phase 8: to the store owner, nobody accepted the order before the deadline. */
    ORDER_AUTO_REJECTED(Category.ORDER, Severity.WARNING),
    /** Phase 9: to a delivery partner, a delivery is offered to them (DeliveryOfferCreated). */
    DELIVERY_OFFER(Category.ORDER, Severity.INFO),
    /** Phase 9: to the customer, a partner will deliver the order (DeliveryPartnerAssigned). */
    PARTNER_ASSIGNED(Category.ORDER, Severity.INFO),
    /** Phase 9: to a partner, an admin took the delivery away from them (DeliveryPartnerUnassigned). */
    DELIVERY_REASSIGNED(Category.ORDER, Severity.WARNING),
    /** Phase 9: to the assigned partner, the order is ready at the store (OrderReadyForPickup). */
    PICKUP_READY(Category.ORDER, Severity.INFO),
    /** Phase 9: to the customer, the partner picked the order up (OrderOutForDelivery). */
    OUT_FOR_DELIVERY(Category.ORDER, Severity.INFO),
    /** Phase 9: to the customer (OrderDelivered). */
    ORDER_DELIVERED(Category.ORDER, Severity.SUCCESS),
    /** Phase 9: to the customer, the order could not be delivered (OrderDeliveryFailed). */
    DELIVERY_FAILED(Category.ORDER, Severity.ERROR),
    /** Phase 9: to SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER — decide on a refund (OrderDeliveryFailed). */
    DELIVERY_FAILED_ADMIN(Category.ORDER, Severity.ERROR),
    /** Phase 9: to SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER — nobody accepted the delivery (DeliveryAssignmentFailed). */
    NO_DELIVERY_PARTNER(Category.ORDER, Severity.WARNING),
    /** Phase 9: to the assigned partner, the order was cancelled before pickup (DeliveryCancelled). */
    DELIVERY_CANCELLED(Category.ORDER, Severity.WARNING),
    /** Phase 10: to SUPER_ADMIN, OPS_ADMIN, SUPPORT — the watchdog found an order stuck (OrderStuckDetected). */
    STUCK_ORDER(Category.ORDER, Severity.WARNING),
    PAYMENT_FAILED(Category.PAYMENT, Severity.ERROR),
    REFUND_COMPLETED(Category.PAYMENT, Severity.SUCCESS),
    REFUND_FAILED(Category.PAYMENT, Severity.ERROR),
    LOW_STOCK(Category.STOCK, Severity.WARNING),
    /** An admin approval queue item, hence APPROVAL (not PRICING). */
    PRICE_APPROVAL_REQUESTED(Category.APPROVAL, Severity.INFO),
    PRICE_CHANGE_APPROVED(Category.PRICING, Severity.SUCCESS),
    PRICE_CHANGE_REJECTED(Category.PRICING, Severity.WARNING),
    APPROVAL_REQUESTED(Category.APPROVAL, Severity.INFO),
    ACCOUNT_APPROVED(Category.ACCOUNT, Severity.SUCCESS),
    ACCOUNT_REJECTED(Category.ACCOUNT, Severity.WARNING),
    ACCOUNT_SUSPENDED(Category.ACCOUNT, Severity.ERROR),
    ACCOUNT_REINSTATED(Category.ACCOUNT, Severity.SUCCESS),
    /** Phase 11: an assistant proposed a sensitive action; the approver user (or roles) must decide it. */
    AI_APPROVAL_REQUESTED(Category.APPROVAL, Severity.INFO),
    /** Phase 11: an assistant's proposal was rejected or expired (never for APPROVED / CANCELLED). */
    AI_APPROVAL_DECIDED(Category.APPROVAL, Severity.WARNING),
    /** Phase 11: an approved action ran (ApprovalExecuted). */
    AI_ACTION_COMPLETED(Category.APPROVAL, Severity.SUCCESS),
    /** Phase 11: an approved action could not run (ApprovalFailed). */
    AI_ACTION_FAILED(Category.APPROVAL, Severity.ERROR),
    /** Phase 12: to the store owner, a review of the store or of its product / dish was published (ReviewSubmitted). */
    NEW_REVIEW(Category.REVIEW, Severity.INFO),
    /** Phase 12: to SUPER_ADMIN, OPS_ADMIN, SUPPORT — automatic moderation flagged a review (ReviewSubmitted FLAGGED). */
    REVIEW_FLAGGED(Category.REVIEW, Severity.WARNING),
    /** Phase 12: to the customer, an admin rejected or removed their review (ReviewModerated). */
    REVIEW_NOT_PUBLISHED(Category.REVIEW, Severity.WARNING),
    /** Phase 12: to the customer, the store replied to their review (ReviewReplied). */
    REVIEW_REPLY(Category.REVIEW, Severity.INFO),
    /** Phase 12: to the store owner, a customer asked to return items (ReturnRequested). */
    RETURN_REQUESTED(Category.ORDER, Severity.WARNING),
    /** Phase 12: to SUPER_ADMIN, OPS_ADMIN, SUPPORT — the store did not decide a return in time (ReturnEscalated). */
    RETURN_ESCALATED(Category.ORDER, Severity.WARNING),
    /** Phase 12: to the customer (ReturnApproved). */
    RETURN_APPROVED(Category.ORDER, Severity.SUCCESS),
    /** Phase 12: to the customer, with the store's or admin's comment (ReturnRejected). */
    RETURN_REJECTED(Category.ORDER, Severity.WARNING),
    /**
     * Security email, never in the inbox: the password reset link (identity.events PasswordResetRequested). Emailed
     * whatever the preferences (EmailProperties#SECURITY_TYPES).
     */
    PASSWORD_RESET_REQUESTED(Category.ACCOUNT, Severity.INFO),
    /** Security email, never in the inbox: "your password was changed" (identity.events PasswordChanged). */
    PASSWORD_CHANGED(Category.ACCOUNT, Severity.WARNING);

    /** Phase 12 adds REVIEW (the review notifications). */
    public enum Category { ORDER, PAYMENT, STOCK, PRICING, ACCOUNT, APPROVAL, REVIEW }

    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    private final Category category;
    private final Severity severity;

    NotificationType(Category category, Severity severity) {
        this.category = category;
        this.severity = severity;
    }

    public Category category() {
        return category;
    }

    public Severity severity() {
        return severity;
    }
}
