package com.grocery.delivery.dispatch.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * PENDING (paid) ─OrderAccepted─▶ SEARCHING ─accept / admin─▶ ASSIGNED ─pickup─▶ PICKED_UP ─deliver─▶ DELIVERED
 *                                    ▲                           │ admin reassign            └─fail──▶ FAILED
 *                                    └───────────────────────────┘
 * PENDING / SEARCHING / ASSIGNED ─OrderCancelled─▶ CANCELLED
 * </pre>
 * A SEARCHING delivery that found nobody inside the largest radius has {@code assignmentFailed = true} and stays
 * SEARCHING (retried).
 */
public enum DeliveryStatus {
    PENDING,
    SEARCHING,
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    FAILED,
    CANCELLED;

    /** The partner is on the way (live location, ETA). */
    public static final Set<DeliveryStatus> ACTIVE = EnumSet.of(ASSIGNED, PICKED_UP);

    /** An order cancellation still cancels the delivery (not after pickup). */
    public static final Set<DeliveryStatus> CANCELLABLE = EnumSet.of(PENDING, SEARCHING, ASSIGNED);

    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED || this == CANCELLED;
    }
}
