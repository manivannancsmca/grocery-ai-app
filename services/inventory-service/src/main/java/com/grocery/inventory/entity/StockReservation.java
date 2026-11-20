package com.grocery.inventory.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stock held for one order (at most one reservation per order id). The lines never change after creation; a
 * RESERVED reservation expires at {@code expiresAt} unless the order is confirmed first (then {@code expiresAt} is
 * null and it is held until pickup).
 */
@Entity
@Table(name = "stock_reservation")
public class StockReservation {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    /** When the stock goes back unless the order is paid first; null = held until pickup (order confirmed). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(name = "stock_reservation_item", joinColumns = @JoinColumn(name = "reservation_id"))
    private List<ReservedItem> items = new ArrayList<>();

    protected StockReservation() {
    }

    public StockReservation(UUID id, UUID orderId, List<ReservedItem> items, Instant expiresAt, Instant now) {
        this.id = id;
        this.orderId = orderId;
        this.items = new ArrayList<>(items);
        this.status = ReservationStatus.RESERVED;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markReleased(Instant now) {
        transition(ReservationStatus.RELEASED, now);
    }

    public void markCommitted(Instant now) {
        transition(ReservationStatus.COMMITTED, now);
    }

    /** The order was paid: the stock stays held until it is committed or released, it no longer expires. */
    public void holdUntilPickup(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Reservation " + id + " is " + status + ", not RESERVED");
        }
        this.expiresAt = null;
        this.updatedAt = now;
    }

    private void transition(ReservationStatus target, Instant now) {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Reservation " + id + " is " + status + ", not RESERVED");
        }
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<ReservedItem> getItems() {
        return items;
    }
}
