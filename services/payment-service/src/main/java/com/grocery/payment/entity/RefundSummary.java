package com.grocery.payment.entity;

import java.math.BigDecimal;

/** {@code PaymentView.refundStatus}: a refund in flight, else how much of the captured amount was refunded. */
public enum RefundSummary {
    NONE, PENDING, PARTIAL, FULL;

    public static RefundSummary of(boolean anyPending, BigDecimal refunded, BigDecimal amount) {
        if (anyPending) {
            return PENDING;
        }
        if (refunded.signum() == 0) {
            return NONE;
        }
        return refunded.compareTo(amount) >= 0 ? FULL : PARTIAL;
    }
}
