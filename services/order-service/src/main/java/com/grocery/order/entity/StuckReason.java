package com.grocery.order.entity;

/**
 * Why the stuck-order watchdog flagged an order (docs/api/phase-10-event-driven-hardening.md §4): the step that should
 * have moved it on is overdue. {@code OrderStuckDetected.reason}, {@code stuck_order.reason}.
 */
public enum StuckReason {
    /** CREATED: inventory never answered the reservation (StockReserved / StockReservationFailed). */
    STOCK_CHECK_OVERDUE,
    /** AWAITING_PAYMENT past {@code payment.dueAt}: payment-service never expired the payment. */
    PAYMENT_EXPIRY_OVERDUE,
    /** CONFIRMED: the store neither accepted nor rejected the paid order. */
    STORE_RESPONSE_OVERDUE,
    /** ACCEPTED / PREPARING past {@code readyBy}: the store never marked it ready. */
    PREPARATION_OVERDUE,
    /** READY_FOR_PICKUP: no partner picked it up. */
    PICKUP_OVERDUE,
    /** OUT_FOR_DELIVERY: neither delivered nor failed. */
    DELIVERY_OVERDUE
}
