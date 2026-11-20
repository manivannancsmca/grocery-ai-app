package com.grocery.payment.entity;

/**
 * {@code CREATED} (no Razorpay order yet) → {@code PENDING} (waiting for the customer) → {@code CAPTURED};
 * {@code EXPIRED} (window over) and {@code CANCELLED} (order cancelled) end an unpaid payment. A capture that
 * arrives after EXPIRED / CANCELLED still makes the payment CAPTURED (late capture, refunded at once).
 */
public enum PaymentStatus {
    CREATED, PENDING, CAPTURED, EXPIRED, CANCELLED;

    /** CREATED or PENDING: the customer may still pay (if the window is open). */
    public boolean isOpen() {
        return this == CREATED || this == PENDING;
    }
}
