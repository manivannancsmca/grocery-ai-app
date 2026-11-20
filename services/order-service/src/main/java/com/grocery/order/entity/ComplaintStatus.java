package com.grocery.order.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * A complaint's status (docs/api/payment-and-support-agents.md §2.1): {@code OPEN → IN_PROGRESS → RESOLVED}, or
 * {@code OPEN → RESOLVED} directly. {@code RESOLVED} is final.
 */
public enum ComplaintStatus {
    /** Raised by the customer, nobody works on it yet. */
    OPEN,
    /** An admin started working on it. */
    IN_PROGRESS,
    /** Answered: the customer sees the resolution. */
    RESOLVED;

    /** The statuses that count as open: one per order, at most {@code max-open} per customer. */
    public static final Set<ComplaintStatus> OPEN_STATUSES = EnumSet.of(OPEN, IN_PROGRESS);

    /** Not resolved yet. */
    public boolean isOpen() {
        return OPEN_STATUSES.contains(this);
    }

    /** An admin may start it: only an OPEN complaint. */
    public boolean canStart() {
        return this == OPEN;
    }

    /** An admin may resolve it: OPEN or IN_PROGRESS. */
    public boolean canResolve() {
        return isOpen();
    }
}
