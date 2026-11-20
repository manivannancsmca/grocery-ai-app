package com.grocery.pricing.entity;

import com.grocery.common.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A vendor's price change above the approval threshold, waiting for (or decided by) an admin. The generated
 * {@code pending_key} column (at most one PENDING request per product) is not mapped.
 */
@Entity
@Table(name = "price_change_request")
public class PriceChangeRequest {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "current_mrp", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal currentMrp;

    @Column(name = "current_selling_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal currentSellingPrice;

    @Column(name = "requested_mrp", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal requestedMrp;

    @Column(name = "requested_selling_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal requestedSellingPrice;

    @Column(name = "change_percent", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal changePercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PriceChangeStatus status;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 300)
    private String decisionReason;

    @Version
    private Long version;

    protected PriceChangeRequest() {
    }

    public static PriceChangeRequest pending(UUID id, ProductRef product, ProductPrice current, BigDecimal requestedMrp,
                                             BigDecimal requestedSellingPrice, BigDecimal changePercent, String note,
                                             UUID requestedBy, Instant now) {
        PriceChangeRequest request = new PriceChangeRequest();
        request.id = id;
        request.productId = product.getProductId();
        request.vendorId = product.getVendorId();
        request.currentMrp = current.getMrp();
        request.currentSellingPrice = current.getSellingPrice();
        request.requestedMrp = requestedMrp;
        request.requestedSellingPrice = requestedSellingPrice;
        request.changePercent = changePercent;
        request.status = PriceChangeStatus.PENDING;
        request.note = note;
        request.requestedBy = requestedBy;
        request.requestedAt = now;
        return request;
    }

    public void approve(UUID admin, String note, Instant now) {
        decide(PriceChangeStatus.APPROVED, admin, note, now);
    }

    public void reject(UUID admin, String reason, Instant now) {
        decide(PriceChangeStatus.REJECTED, admin, reason, now);
    }

    /** {@code by} = null for an automatic cancellation (the product was archived). */
    public void cancel(UUID by, String reason, Instant now) {
        decide(PriceChangeStatus.CANCELLED, by, reason, now);
    }

    public boolean isPending() {
        return status == PriceChangeStatus.PENDING;
    }

    public boolean isOwnedBy(UUID vendor) {
        return vendorId.equals(vendor);
    }

    private void decide(PriceChangeStatus target, UUID by, String reason, Instant now) {
        if (!isPending()) {
            throw new ConflictException("PRICE_CHANGE_NOT_PENDING",
                    "The price-change request is already " + status + "; it can no longer be " + verb(target));
        }
        status = target;
        decidedBy = by;
        decidedAt = now;
        decisionReason = reason;
    }

    private static String verb(PriceChangeStatus target) {
        return switch (target) {
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case CANCELLED -> "cancelled";
            case PENDING -> "changed";
        };
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

    public BigDecimal getCurrentMrp() {
        return currentMrp;
    }

    public BigDecimal getCurrentSellingPrice() {
        return currentSellingPrice;
    }

    public BigDecimal getRequestedMrp() {
        return requestedMrp;
    }

    public BigDecimal getRequestedSellingPrice() {
        return requestedSellingPrice;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public PriceChangeStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
