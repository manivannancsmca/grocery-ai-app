package com.grocery.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/** One line of a reservation (immutable once written). */
@Embeddable
public class ReservedItem {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    protected ReservedItem() {
    }

    public ReservedItem(UUID productId, UUID vendorId, int quantity) {
        this.productId = productId;
        this.vendorId = vendorId;
        this.quantity = quantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public int getQuantity() {
        return quantity;
    }
}
