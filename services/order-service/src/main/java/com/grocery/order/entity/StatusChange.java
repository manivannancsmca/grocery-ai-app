package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

/** One entry of the order's status history (timeline), append-only. */
@Embeddable
public class StatusChange {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OrderStatus status;

    /** The rejection or cancellation reason; null for the other statuses. */
    @Column(length = 30, updatable = false)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected StatusChange() {
    }

    public StatusChange(OrderStatus status, String reason, Instant changedAt) {
        this.status = status;
        this.reason = reason;
        this.changedAt = changedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
