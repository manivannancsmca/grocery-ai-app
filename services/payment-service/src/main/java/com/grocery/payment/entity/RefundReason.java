package com.grocery.payment.entity;

public enum RefundReason {
    /** {@code OrderCancelled} after the payment was captured. */
    ORDER_CANCELLED,
    /** Captured after the payment had expired or been cancelled. */
    LATE_CAPTURE,
    /** {@code POST /api/admin/refunds}. */
    ADMIN,
    /** Phase 12: {@code ReturnApproved} — the refund of an approved return (one per return). */
    RETURN
}
