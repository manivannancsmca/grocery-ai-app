package com.grocery.restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for every status change of a restaurant. */
@Entity
@Table(name = "restaurant_status_change")
public class RestaurantStatusChange {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private RestaurantStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private RestaurantStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected RestaurantStatusChange() {
    }

    public RestaurantStatusChange(UUID id, UUID restaurantId, RestaurantStatus fromStatus, RestaurantStatus toStatus,
                                  String reason, UUID changedBy, Instant changedAt) {
        this.id = id;
        this.restaurantId = restaurantId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public RestaurantStatus getFromStatus() {
        return fromStatus;
    }

    public RestaurantStatus getToStatus() {
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
