package com.grocery.order.entity;

/**
 * Why an order was cancelled. {@code STORE_REJECTED} (the store said no, by {@code STORE}) and
 * {@code STORE_NO_RESPONSE} (nobody accepted before the deadline, by {@code SYSTEM}) arrive in Phase 8.
 */
public enum CancellationReason {
    CUSTOMER_CANCELLED,
    ADMIN_CANCELLED,
    PAYMENT_TIMEOUT,
    RESERVATION_EXPIRED,
    STORE_REJECTED,
    STORE_NO_RESPONSE
}
