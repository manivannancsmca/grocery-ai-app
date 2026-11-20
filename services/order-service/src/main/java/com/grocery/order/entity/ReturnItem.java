package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.UUID;

/** One returned line: the order line's item and name, the quantity and its prorated refund (never changed). */
@Embeddable
public class ReturnItem {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal refundAmount;

    protected ReturnItem() {
    }

    public ReturnItem(UUID itemId, String name, int quantity, BigDecimal refundAmount) {
        this.itemId = itemId;
        this.name = name;
        this.quantity = quantity;
        this.refundAmount = refundAmount;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }
}
