package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a purchase order: the product (with its name as it was when the line was written), the ordered quantity,
 * the unit cost and how much has been received so far ({@code 0 ≤ received ≤ ordered}).
 */
@Embeddable
public class PurchaseOrderLine {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 150)
    private String productName;

    @Column(name = "ordered_quantity", nullable = false)
    private int orderedQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    protected PurchaseOrderLine() {
    }

    public PurchaseOrderLine(UUID productId, String productName, int orderedQuantity, BigDecimal unitCost) {
        this.productId = productId;
        this.productName = productName;
        this.orderedQuantity = orderedQuantity;
        this.unitCost = unitCost;
        this.receivedQuantity = 0;
    }

    /** Units still to come: ordered − received. */
    public int openQuantity() {
        return orderedQuantity - receivedQuantity;
    }

    public BigDecimal lineTotal() {
        return unitCost.multiply(BigDecimal.valueOf(orderedQuantity));
    }

    public BigDecimal receivedCost() {
        return unitCost.multiply(BigDecimal.valueOf(receivedQuantity));
    }

    /** The caller has checked {@code 0 < quantity ≤ openQuantity()}. */
    void receive(int quantity) {
        if (quantity <= 0 || quantity > openQuantity()) {
            throw new IllegalStateException("Cannot receive " + quantity + " of " + productId + ": " + openQuantity()
                    + " open");
        }
        receivedQuantity += quantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public int getOrderedQuantity() {
        return orderedQuantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public int getReceivedQuantity() {
        return receivedQuantity;
    }
}
