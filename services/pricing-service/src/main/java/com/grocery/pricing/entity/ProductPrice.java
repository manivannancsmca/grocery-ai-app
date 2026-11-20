package com.grocery.pricing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The live price of a product. {@code priceVersion} is 1 for the first price and grows by exactly one with
 * every change; it is only ever incremented while the product's {@code product_ref} row is locked.
 */
@Entity
@Table(name = "product_price")
public class ProductPrice {

    public static final String INR = "INR";

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "selling_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "price_version", nullable = false)
    private long priceVersion;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductPrice() {
    }

    public static ProductPrice first(UUID productId, UUID vendorId, BigDecimal mrp, BigDecimal sellingPrice,
                                     UUID changedBy, Instant now) {
        ProductPrice price = new ProductPrice();
        price.productId = productId;
        price.vendorId = vendorId;
        price.currency = INR;
        price.priceVersion = 0;
        price.change(mrp, sellingPrice, changedBy, now);
        return price;
    }

    /** Next live price and version; call only while holding the product lock. */
    public void change(BigDecimal newMrp, BigDecimal newSellingPrice, UUID changedBy, Instant now) {
        mrp = newMrp;
        sellingPrice = newSellingPrice;
        updatedBy = changedBy;
        updatedAt = now;
        priceVersion++;
    }

    public boolean isSameAs(BigDecimal otherMrp, BigDecimal otherSellingPrice) {
        return mrp.compareTo(otherMrp) == 0 && sellingPrice.compareTo(otherSellingPrice) == 0;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public long getPriceVersion() {
        return priceVersion;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
