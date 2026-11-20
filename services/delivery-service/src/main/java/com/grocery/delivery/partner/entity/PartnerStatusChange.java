package com.grocery.delivery.partner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for every status change of a delivery partner. */
@Entity
@Table(name = "delivery_partner_status_change")
public class PartnerStatusChange {

    @Id
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private PartnerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PartnerStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected PartnerStatusChange() {
    }

    public PartnerStatusChange(UUID id, UUID partnerId, PartnerStatus fromStatus, PartnerStatus toStatus,
                               String reason, UUID changedBy, Instant changedAt) {
        this.id = id;
        this.partnerId = partnerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public PartnerStatus getFromStatus() {
        return fromStatus;
    }

    public PartnerStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
