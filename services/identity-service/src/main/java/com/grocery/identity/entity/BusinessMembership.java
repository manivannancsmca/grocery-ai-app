package com.grocery.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of "user X owns vendor/restaurant Y and its approval state", kept up to date from
 * vendor/restaurant events. Identity needs it to put {@code vendor_id}/{@code restaurant_id} into
 * tokens without calling those services.
 */
@Entity
@Table(name = "business_membership")
public class BusinessMembership {

    public enum BusinessType { VENDOR, RESTAURANT }

    public enum Status { PENDING, ACTIVE, REJECTED, SUSPENDED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 20)
    private BusinessType businessType;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessMembership() {
    }

    public BusinessMembership(UUID id, UUID userId, BusinessType businessType, UUID businessId, Status status, Instant now) {
        this.id = id;
        this.userId = userId;
        this.businessType = businessType;
        this.businessId = businessId;
        this.status = status;
        this.updatedAt = now;
    }

    public void changeStatus(Status newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getUserId() {
        return userId;
    }

    public BusinessType getBusinessType() {
        return businessType;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public Status getStatus() {
        return status;
    }
}
