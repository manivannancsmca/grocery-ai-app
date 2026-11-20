package com.grocery.pricing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One change of a live price (append-only). The old values and the change are null for the first price. */
@Entity
@Table(name = "price_history")
public class PriceHistory {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "old_mrp", precision = 12, scale = 2, updatable = false)
    private BigDecimal oldMrp;

    @Column(name = "old_selling_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal oldSellingPrice;

    @Column(name = "new_mrp", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal newMrp;

    @Column(name = "new_selling_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal newSellingPrice;

    @Column(name = "change_percent", precision = 10, scale = 2, updatable = false)
    private BigDecimal changePercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PriceSource source;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected PriceHistory() {
    }

    public static PriceHistory of(UUID id, UUID productId, UUID vendorId, BigDecimal oldMrp, BigDecimal oldSellingPrice,
                                  BigDecimal newMrp, BigDecimal newSellingPrice, BigDecimal changePercent,
                                  PriceSource source, UUID requestId, UUID changedBy, Instant changedAt) {
        PriceHistory entry = new PriceHistory();
        entry.id = id;
        entry.productId = productId;
        entry.vendorId = vendorId;
        entry.oldMrp = oldMrp;
        entry.oldSellingPrice = oldSellingPrice;
        entry.newMrp = newMrp;
        entry.newSellingPrice = newSellingPrice;
        entry.changePercent = changePercent;
        entry.source = source;
        entry.requestId = requestId;
        entry.changedBy = changedBy;
        entry.changedAt = changedAt;
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public BigDecimal getOldMrp() {
        return oldMrp;
    }

    public BigDecimal getOldSellingPrice() {
        return oldSellingPrice;
    }

    public BigDecimal getNewMrp() {
        return newMrp;
    }

    public BigDecimal getNewSellingPrice() {
        return newSellingPrice;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public PriceSource getSource() {
        return source;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
