package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for every status change of a vendor. */
@Entity
@Table(name = "vendor_status_change")
public class VendorStatusChange {

    @Id
    private UUID id;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private VendorStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private VendorStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected VendorStatusChange() {
    }

    public VendorStatusChange(UUID id, UUID vendorId, VendorStatus fromStatus, VendorStatus toStatus,
                              String reason, UUID changedBy, Instant changedAt) {
        this.id = id;
        this.vendorId = vendorId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public VendorStatus getFromStatus() {
        return fromStatus;
    }

    public VendorStatus getToStatus() {
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
