package com.grocery.restaurant.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of a store order (Phase 8 contract §2, Phase 9 §7):
 * <pre>
 * NEW ──accept──▶ ACCEPTED ──preparing──▶ PREPARING ──ready──▶ READY_FOR_PICKUP ──OrderOutForDelivery──▶ PICKED_UP
 *  │                  └──────────────ready────────────────────────▲
 *  ├─reject / deadline──▶ REJECTED
 *  └─(any active) OrderCancelled──▶ CANCELLED
 * </pre>
 * {@code PICKED_UP} (Phase 9) is the terminal success: the delivery partner took the order, it leaves the board.
 */
public enum StoreOrderStatus {
    NEW,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    PICKED_UP,
    REJECTED,
    CANCELLED;

    /** {@code status=ACTIVE} of the board: everything still on the store's to-do list. */
    public static final Set<StoreOrderStatus> ACTIVE = EnumSet.of(NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP);

    /** The statuses a partner can pick the order up from: accepted, not yet gone (normally READY_FOR_PICKUP). */
    public static final Set<StoreOrderStatus> PICKUP_PENDING = EnumSet.of(ACCEPTED, PREPARING, READY_FOR_PICKUP);

    public boolean isTerminal() {
        return this == PICKED_UP || this == REJECTED || this == CANCELLED;
    }
}
