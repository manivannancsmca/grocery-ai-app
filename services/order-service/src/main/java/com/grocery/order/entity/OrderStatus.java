package com.grocery.order.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code CREATED → AWAITING_PAYMENT → CONFIRMED → ACCEPTED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY →
 * DELIVERED}; terminal {@code REJECTED}, {@code CANCELLED}, {@code DELIVERED} and {@code DELIVERY_FAILED} (Phase 8: a
 * store may go from ACCEPTED straight to READY_FOR_PICKUP; Phase 9: the delivery partner picks the order up and
 * delivers it, or cannot deliver it). The declaration order is the lifecycle order (the stats list {@code byStatus}
 * in it).
 */
public enum OrderStatus {
    CREATED,
    AWAITING_PAYMENT,
    CONFIRMED,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_FAILED,
    REJECTED,
    CANCELLED;

    /** {@code status=OPEN} in the order lists. */
    public static final Set<OrderStatus> OPEN = EnumSet.of(CREATED, AWAITING_PAYMENT);

    /**
     * The customer may cancel in these statuses: until the store accepts (contract Phase 8 §4).
     * {@code OrderView.cancellable} follows this rule.
     */
    public boolean isCancellable() {
        return this == CREATED || this == AWAITING_PAYMENT || this == CONFIRMED;
    }

    /**
     * An admin (SUPER_ADMIN, OPS_ADMIN, SUPPORT) may cancel in any non-terminal status up to and including
     * {@code READY_FOR_PICKUP} — never after pickup (Phase 9 §7). This is also the widest rule of
     * {@link CustomerOrder#cancel}.
     */
    public boolean isAdminCancellable() {
        return isCancellable() || this == ACCEPTED || this == PREPARING || this == READY_FOR_PICKUP;
    }

    /**
     * The delivery can be followed on the map (Phase 9 §7, {@code OrderView.trackable}): from the store's acceptance
     * (the delivery starts looking for a partner) until the order is delivered or fails.
     */
    public boolean isTrackable() {
        return this == ACCEPTED || this == PREPARING || this == READY_FOR_PICKUP || this == OUT_FOR_DELIVERY;
    }

    /** {@code REJECTED}, {@code CANCELLED}, {@code DELIVERED} and {@code DELIVERY_FAILED} never change again. */
    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == DELIVERED || this == DELIVERY_FAILED;
    }
}
