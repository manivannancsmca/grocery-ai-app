package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a store order: the snapshot from OrderConfirmed, never changed. */
@Embeddable
public class StoreOrderItem {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "item_type", length = 10, updatable = false)
    private String itemType;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    protected StoreOrderItem() {
    }

    public StoreOrderItem(UUID itemId, String itemType, String name, int quantity, BigDecimal unitPrice,
                          BigDecimal lineTotal) {
        this.itemId = itemId;
        this.itemType = itemType;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getItemType() {
        return itemType;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
