package com.grocery.order.entity;

/**
 * order-service's own view of the order's payment, kept from {@code payment.events} (contract §2):
 * {@code NOT_STARTED} (CREATED, waiting for stock), {@code PENDING} (waiting for the customer), {@code PAID},
 * {@code EXPIRED}, {@code NOT_REQUIRED} (rejected before payment / cancelled unpaid), {@code REFUND_PENDING},
 * {@code REFUNDED}, {@code PARTIALLY_REFUNDED}.
 */
public enum PaymentStatus {
    NOT_STARTED,
    PENDING,
    PAID,
    EXPIRED,
    NOT_REQUIRED,
    REFUND_PENDING,
    REFUNDED,
    PARTIALLY_REFUNDED;

    /** Nothing has been paid yet (PaymentInitiated may still set PENDING). */
    public boolean isUnpaid() {
        return this == NOT_STARTED || this == PENDING;
    }

    /** Money was captured and not (fully) given back: cancelling means a refund. */
    public boolean holdsMoney() {
        return this == PAID || this == PARTIALLY_REFUNDED;
    }
}
