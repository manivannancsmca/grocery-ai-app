package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/** A line inventory could not reserve ({@code StockReservationFailed}), with the order line's name. */
@Embeddable
public class RejectedItem {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(length = 200, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private int requested;

    @Column(nullable = false, updatable = false)
    private int available;

    protected RejectedItem() {
    }

    public RejectedItem(UUID itemId, String name, int requested, int available) {
        this.itemId = itemId;
        this.name = name;
        this.requested = requested;
        this.available = available;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
