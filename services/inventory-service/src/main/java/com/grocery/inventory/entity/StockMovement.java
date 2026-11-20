package com.grocery.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One entry of the append-only stock ledger, written in the same transaction as the change it records. */
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private MovementType type;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;

    @Column(name = "on_hand_after", nullable = false, updatable = false)
    private int onHandAfter;

    @Column(name = "reserved_after", nullable = false, updatable = false)
    private int reservedAfter;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private AdjustmentReason reason;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(length = 64, updatable = false)
    private String reference;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    /** Records a change already applied to {@code stock}: the "after" quantities come from it. */
    public static StockMovement record(UUID id, StockItem stock, MovementType type, int quantityDelta,
                                       AdjustmentReason reason, String note, String reference, UUID actorUserId,
                                       Instant now) {
        StockMovement movement = new StockMovement();
        movement.id = id;
        movement.productId = stock.getProductId();
        movement.vendorId = stock.getVendorId();
        movement.type = type;
        movement.quantityDelta = quantityDelta;
        movement.onHandAfter = stock.getOnHand();
        movement.reservedAfter = stock.getReserved();
        movement.reason = reason;
        movement.note = note;
        movement.reference = reference;
        movement.actorUserId = actorUserId;
        movement.createdAt = now;
        return movement;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public MovementType getType() {
        return type;
    }

    public int getQuantityDelta() {
        return quantityDelta;
    }

    public int getOnHandAfter() {
        return onHandAfter;
    }

    public int getReservedAfter() {
        return reservedAfter;
    }

    public AdjustmentReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public String getReference() {
        return reference;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
