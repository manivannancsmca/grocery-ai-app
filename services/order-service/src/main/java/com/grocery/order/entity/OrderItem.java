package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of the order: a snapshot of the checkout preview line, never changed after creation. */
@Embeddable
public class OrderItem {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ItemType type;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(name = "unit_label", length = 40, updatable = false)
    private String unitLabel;

    @Column(name = "image_url", length = 500, updatable = false)
    private String imageUrl;

    @Column(name = "food_type", length = 20, updatable = false)
    private String foodType;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(precision = 12, scale = 2, updatable = false)
    private BigDecimal mrp;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    public OrderItem(UUID itemId, ItemType type, String name, String unitLabel, String imageUrl, String foodType,
                     int quantity, BigDecimal unitPrice, BigDecimal mrp, BigDecimal lineTotal) {
        this.itemId = itemId;
        this.type = type;
        this.name = name;
        this.unitLabel = unitLabel;
        this.imageUrl = imageUrl;
        this.foodType = foodType;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.mrp = mrp;
        this.lineTotal = lineTotal;
    }

    public UUID getItemId() {
        return itemId;
    }

    public ItemType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getFoodType() {
        return foodType;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
