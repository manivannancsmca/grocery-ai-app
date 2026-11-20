package com.grocery.vendor.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt (Phase 12 §4): what arrived in one {@code POST …/receive}, published as {@code GoodsReceived}.
 * Append-only; created only by {@link PurchaseOrder#receive}.
 */
@Entity
@Table(name = "purchase_receipt")
public class PurchaseReceipt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @ElementCollection
    @CollectionTable(name = "purchase_receipt_line", joinColumns = @JoinColumn(name = "receipt_id"))
    @OrderColumn(name = "line_no")
    private List<ReceiptLine> lines = new ArrayList<>();

    protected PurchaseReceipt() {
    }

    PurchaseReceipt(UUID id, PurchaseOrder purchaseOrder, List<ReceiptLine> lines, String note, UUID receivedBy,
                    Instant receivedAt) {
        this.id = id;
        this.purchaseOrder = purchaseOrder;
        this.lines.addAll(lines);
        this.note = note;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
    }

    /** Units in this receipt. */
    public int units() {
        return lines.stream().mapToInt(ReceiptLine::getQuantity).sum();
    }

    /** Σ quantity × unit cost. */
    public BigDecimal totalCost() {
        return lines.stream().map(ReceiptLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public UUID getId() {
        return id;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public String getNote() {
        return note;
    }

    public UUID getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public List<ReceiptLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
