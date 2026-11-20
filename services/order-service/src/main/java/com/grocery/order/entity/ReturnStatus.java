package com.grocery.order.entity;

/**
 * A return request's status (docs/api/phase-12-post-mvp-features.md §3): {@code REQUESTED → APPROVED → REFUNDED};
 * {@code REQUESTED → REJECTED}; {@code REQUESTED → ESCALATED} (the store did not decide in time) {@code → APPROVED /
 * REJECTED} by an admin.
 */
public enum ReturnStatus {
    REQUESTED,
    ESCALATED,
    APPROVED,
    REJECTED,
    REFUNDED;

    /** Waiting for a decision: at most one open request per order. */
    public boolean isOpen() {
        return this == REQUESTED || this == ESCALATED;
    }

    /** Its quantities are taken (open, approved or refunded); a rejected request frees them again. */
    public boolean holdsQuantities() {
        return this != REJECTED;
    }
}
