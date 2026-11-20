package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a goods receipt (snapshot, never changed): units received now at the purchase order's unit cost. */
@Embeddable
public class ReceiptLine {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 150, updatable = false)
    private String productName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitCost;

    protected ReceiptLine() {
    }

    public ReceiptLine(UUID productId, String productName, int quantity, BigDecimal unitCost) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitCost = unitCost;
    }

    public BigDecimal cost() {
        return unitCost.multiply(BigDecimal.valueOf(quantity));
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
