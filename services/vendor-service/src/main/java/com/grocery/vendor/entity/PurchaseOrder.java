package com.grocery.vendor.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A vendor's purchase order to one of its suppliers (Phase 12 §4); status machine in {@link PurchaseOrderStatus}.
 *
 * <p>The transitions check the current status and throw {@link IllegalStateException} when called in the wrong one:
 * callers check first and answer the API's 409s (see {@code service/PurchaseOrderService}). Callers hold the row lock.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    public static final String CURRENCY = "INR";

    /** A received quantity of one product (a receipt line before its unit cost is looked up). */
    public record Receiving(UUID productId, int quantity) {
    }

    @Id
    private UUID id;

    @Column(nullable = false, length = 11, updatable = false)
    private String number;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "expected_at")
    private Instant expectedAt;

    @Column(length = 500)
    private String notes;

    @ElementCollection
    @CollectionTable(name = "purchase_order_line", joinColumns = @JoinColumn(name = "purchase_order_id"))
    @OrderColumn(name = "line_no")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.PERSIST)
    @OrderBy("receivedAt ASC")
    private List<PurchaseReceipt> receipts = new ArrayList<>();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "fully_received_at")
    private Instant fullyReceivedAt;

    @Version
    private Long version;

    protected PurchaseOrder() {
    }

    /** A new DRAFT with at least one line (one line per product; the caller has checked the products). */
    public static PurchaseOrder draft(UUID id, String number, UUID vendorId, Supplier supplier, Instant expectedAt,
                                      String notes, List<PurchaseOrderLine> lines, UUID createdBy, Instant now) {
        PurchaseOrder order = new PurchaseOrder();
        order.id = Objects.requireNonNull(id, "id");
        order.number = Objects.requireNonNull(number, "number");
        order.vendorId = Objects.requireNonNull(vendorId, "vendorId");
        order.status = PurchaseOrderStatus.DRAFT;
        order.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        order.createdAt = now;
        order.replace(supplier, expectedAt, notes, lines, now);
        return order;
    }

    /** DRAFT only: replaces supplier, expected time, notes and every line. */
    public void edit(Supplier supplier, Instant expectedAt, String notes, List<PurchaseOrderLine> lines, Instant now) {
        requireStatus(Set.of(PurchaseOrderStatus.DRAFT), "edit");
        replace(supplier, expectedAt, notes, lines, now);
    }

    /** DRAFT → SENT. */
    public void send(Instant now) {
        requireStatus(PurchaseOrderStatus.SENDABLE, "send");
        status = PurchaseOrderStatus.SENT;
        sentAt = now;
        updatedAt = now;
    }

    /** DRAFT / SENT → CANCELLED. */
    public void cancel(Instant now) {
        requireStatus(PurchaseOrderStatus.CANCELLABLE, "cancel");
        status = PurchaseOrderStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
    }

    /** PARTIALLY_RECEIVED → CLOSED: the rest will not come (short-closed). */
    public void close(Instant now) {
        requireStatus(PurchaseOrderStatus.CLOSABLE, "close");
        status = PurchaseOrderStatus.CLOSED;
        closedAt = now;
        updatedAt = now;
    }

    /**
     * SENT / PARTIALLY_RECEIVED → PARTIALLY_RECEIVED, or RECEIVED once nothing is open any more. Adds the quantities
     * to the lines and appends the receipt (lines in the given order, at the purchase order's unit costs). The caller
     * has checked that every product is on the purchase order, once, with {@code 0 < quantity ≤ open}.
     */
    public PurchaseReceipt receive(UUID receiptId, List<Receiving> received, String note, UUID receivedBy,
                                   Instant now) {
        requireStatus(PurchaseOrderStatus.RECEIVABLE, "receive");
        if (received.isEmpty()) {
            throw new IllegalArgumentException("A receipt needs at least one line");
        }
        List<ReceiptLine> receiptLines = new ArrayList<>(received.size());
        for (Receiving receiving : received) {
            PurchaseOrderLine line = line(receiving.productId()).orElseThrow(() -> new IllegalStateException(
                    "Product " + receiving.productId() + " is not on purchase order " + number));
            line.receive(receiving.quantity());
            receiptLines.add(new ReceiptLine(line.getProductId(), line.getProductName(), receiving.quantity(),
                    line.getUnitCost()));
        }
        PurchaseReceipt receipt = new PurchaseReceipt(receiptId, this, receiptLines, note, receivedBy, now);
        receipts.add(receipt);
        if (openUnits() == 0) {
            status = PurchaseOrderStatus.RECEIVED;
            fullyReceivedAt = now;
        } else {
            status = PurchaseOrderStatus.PARTIALLY_RECEIVED;
        }
        updatedAt = now;
        return receipt;
    }

    public Optional<PurchaseOrderLine> line(UUID productId) {
        return lines.stream().filter(line -> line.getProductId().equals(productId)).findFirst();
    }

    public int orderedUnits() {
        return lines.stream().mapToInt(PurchaseOrderLine::getOrderedQuantity).sum();
    }

    public int receivedUnits() {
        return lines.stream().mapToInt(PurchaseOrderLine::getReceivedQuantity).sum();
    }

    public int openUnits() {
        return lines.stream().mapToInt(PurchaseOrderLine::openQuantity).sum();
    }

    /** Σ ordered × unit cost. */
    public BigDecimal totalCost() {
        return lines.stream().map(PurchaseOrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Σ received × unit cost. */
    public BigDecimal receivedCost() {
        return lines.stream().map(PurchaseOrderLine::receivedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void replace(Supplier supplier, Instant expectedAt, String notes, List<PurchaseOrderLine> newLines,
                         Instant now) {
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order needs at least one line");
        }
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.expectedAt = expectedAt;
        this.notes = notes;
        this.lines.clear();
        this.lines.addAll(newLines);
        this.updatedAt = now;
    }

    private void requireStatus(Set<PurchaseOrderStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new IllegalStateException("Purchase order " + number + " cannot " + action + " in status " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public Instant getExpectedAt() {
        return expectedAt;
    }

    public String getNotes() {
        return notes;
    }

    public List<PurchaseOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<PurchaseReceipt> getReceipts() {
        return Collections.unmodifiableList(receipts);
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getFullyReceivedAt() {
        return fullyReceivedAt;
    }
}
