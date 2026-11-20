package com.grocery.delivery.dispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.UUID;

/** One entry of a delivery's status history (its timeline), append-only. */
@Embeddable
public class DeliveryStatusChange {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DeliveryStatus status;

    /** The partner concerned: assigned, unassigned (back to SEARCHING), picking up, delivering … */
    @Column(name = "partner_id", updatable = false)
    private UUID partnerId;

    /** Failure, cancellation or reassignment reason; null otherwise. */
    @Column(length = 300, updatable = false)
    private String reason;

    /** The partner or admin who made the change; null for the system (order events, pollers). */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected DeliveryStatusChange() {
    }

    public DeliveryStatusChange(DeliveryStatus status, UUID partnerId, String reason, UUID actorUserId,
                                Instant changedAt) {
        this.status = status;
        this.partnerId = partnerId;
        this.reason = reason;
        this.actorUserId = actorUserId;
        this.changedAt = changedAt;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public UUID getPartnerId() {
        return partnerId;
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
