package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.UUID;

/** One entry of a store order's status history (its timeline), append-only. */
@Embeddable
public class StoreOrderStatusChange {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private StoreOrderStatus status;

    /** The rejection or cancellation reason; null for the other statuses. */
    @Column(length = 40, updatable = false)
    private String reason;

    /** The store user who made the change; null for the system (received, auto-reject, cancellation). */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected StoreOrderStatusChange() {
    }

    public StoreOrderStatusChange(StoreOrderStatus status, String reason, UUID actorUserId, Instant changedAt) {
        this.status = status;
        this.reason = reason;
        this.actorUserId = actorUserId;
        this.changedAt = changedAt;
    }

    public StoreOrderStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
