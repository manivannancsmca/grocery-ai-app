package com.grocery.vendor.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The vendor's view of one of its GROCERY orders (contract §2): the snapshot taken from {@code OrderConfirmed} (lines,
 * totals, the customer's name and area, the note — never changed afterwards), the store status with its history, the
 * acceptance deadline and the rejection / cancellation details.
 *
 * <p>The transitions check the current status and throw {@link IllegalStateException} when called in the wrong one:
 * callers check the state guard first and answer the API's 409s (see {@code service/StoreOrderService}). Every
 * transition appends a history entry and moves {@code updatedAt}. Callers hold the row lock.
 */
@Entity
@Table(name = "vendor_order")
public class VendorOrder {

    public static final String CURRENCY = "INR";

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 20, updatable = false)
    private String orderNumber;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreOrderStatus status;

    @ElementCollection
    @CollectionTable(name = "vendor_order_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<StoreOrderItem> items = new ArrayList<>();

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "customer_name", length = 100, updatable = false)
    private String customerName;

    @Column(name = "customer_area", length = 300, updatable = false)
    private String customerArea;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(name = "confirmed_at", updatable = false)
    private Instant confirmedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "accept_by", nullable = false, updatable = false)
    private Instant acceptBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "ready_in_minutes")
    private Integer readyInMinutes;

    @Column(name = "ready_by")
    private Instant readyBy;

    @Column(name = "preparing_at")
    private Instant preparingAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 20)
    private RejectionReason rejectionReason;

    @Column(name = "rejection_note", length = 300)
    private String rejectionNote;

    @Column(name = "rejection_auto")
    private Boolean rejectionAuto;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "cancellation_reason", length = 40)
    private String cancellationReason;

    @Column(name = "cancelled_by", length = 20)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ElementCollection
    @CollectionTable(name = "vendor_order_status_history", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "seq")
    private List<StoreOrderStatusChange> history = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected VendorOrder() {
    }

    /**
     * A paid order that reached the vendor: status NEW, waiting for acceptance until {@code receivedAt + timeout}.
     * {@code itemCount} is the number of units.
     */
    public static VendorOrder received(UUID orderId, String orderNumber, UUID vendorId, UUID customerId,
                                       List<StoreOrderItem> items, BigDecimal subtotal, BigDecimal total,
                                       String customerName, String customerArea, String note, Instant confirmedAt,
                                       Instant receivedAt, Duration acceptanceTimeout) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A store order needs at least one line");
        }
        VendorOrder order = new VendorOrder();
        order.orderId = Objects.requireNonNull(orderId, "orderId");
        order.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        order.vendorId = Objects.requireNonNull(vendorId, "vendorId");
        order.customerId = Objects.requireNonNull(customerId, "customerId");
        order.status = StoreOrderStatus.NEW;
        order.items.addAll(items);
        order.itemCount = items.stream().mapToInt(StoreOrderItem::getQuantity).sum();
        order.subtotal = Objects.requireNonNull(subtotal, "subtotal");
        order.total = Objects.requireNonNull(total, "total");
        order.currency = CURRENCY;
        order.customerName = customerName;
        order.customerArea = customerArea;
        order.note = note;
        order.confirmedAt = confirmedAt;
        order.receivedAt = receivedAt;
        order.acceptBy = receivedAt.plus(acceptanceTimeout);
        order.updatedAt = receivedAt;
        order.history.add(new StoreOrderStatusChange(StoreOrderStatus.NEW, null, null, receivedAt));
        return order;
    }

    /** The acceptance deadline has passed: {@code acceptBy < now} (the poller's rule too). */
    public boolean isAcceptanceExpired(Instant now) {
        return acceptBy.isBefore(now);
    }

    /** NEW → ACCEPTED; ready {@code readyInMinutes} after now. */
    public void accept(int readyInMinutes, UUID actorUserId, Instant now) {
        requireStatus(EnumSet.of(StoreOrderStatus.NEW), StoreOrderStatus.ACCEPTED);
        this.acceptedAt = now;
        this.readyInMinutes = readyInMinutes;
        this.readyBy = now.plus(Duration.ofMinutes(readyInMinutes));
        moveTo(StoreOrderStatus.ACCEPTED, null, actorUserId, now);
    }

    /** NEW → REJECTED, by a store user ({@code auto} false) or at the deadline ({@code auto} true, no actor). */
    public void reject(RejectionReason reason, String note, boolean auto, UUID actorUserId, Instant now) {
        requireStatus(EnumSet.of(StoreOrderStatus.NEW), StoreOrderStatus.REJECTED);
        this.rejectionReason = Objects.requireNonNull(reason, "reason");
        this.rejectionNote = note;
        this.rejectionAuto = auto;
        this.rejectedAt = now;
        this.rejectedBy = actorUserId;
        moveTo(StoreOrderStatus.REJECTED, reason.name(), actorUserId, now);
    }

    /** ACCEPTED → PREPARING (packing). */
    public void startPreparing(UUID actorUserId, Instant now) {
        requireStatus(EnumSet.of(StoreOrderStatus.ACCEPTED), StoreOrderStatus.PREPARING);
        this.preparingAt = now;
        moveTo(StoreOrderStatus.PREPARING, null, actorUserId, now);
    }

    /** ACCEPTED or PREPARING → READY_FOR_PICKUP (a small order may skip PREPARING). */
    public void markReady(UUID actorUserId, Instant now) {
        requireStatus(EnumSet.of(StoreOrderStatus.ACCEPTED, StoreOrderStatus.PREPARING), StoreOrderStatus.READY_FOR_PICKUP);
        this.readyAt = now;
        moveTo(StoreOrderStatus.READY_FOR_PICKUP, null, actorUserId, now);
    }

    /**
     * The delivery partner took the order (Phase 9, {@code OrderOutForDelivery}): ACCEPTED, PREPARING or
     * READY_FOR_PICKUP → PICKED_UP, the terminal success. The timeline entry has the pickup time and no actor.
     */
    public void pickedUp(Instant at, Instant now) {
        requireStatus(StoreOrderStatus.PICKUP_PENDING, StoreOrderStatus.PICKED_UP);
        this.status = StoreOrderStatus.PICKED_UP;
        this.updatedAt = now;
        history.add(new StoreOrderStatusChange(StoreOrderStatus.PICKED_UP, null, null, at));
    }

    /** Any non-terminal status → CANCELLED (the customer or an admin cancelled the order). */
    public void cancel(String reason, String cancelledBy, Instant at, Instant now) {
        requireStatus(StoreOrderStatus.ACTIVE, StoreOrderStatus.CANCELLED);
        this.cancellationReason = reason;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = at;
        this.status = StoreOrderStatus.CANCELLED;
        this.updatedAt = now;
        history.add(new StoreOrderStatusChange(StoreOrderStatus.CANCELLED, reason, null, at));
    }

    private void moveTo(StoreOrderStatus target, String reason, UUID actorUserId, Instant now) {
        status = target;
        updatedAt = now;
        history.add(new StoreOrderStatusChange(target, reason, actorUserId, now));
    }

    private void requireStatus(Set<StoreOrderStatus> allowed, StoreOrderStatus target) {
        if (!allowed.contains(status)) {
            throw new IllegalStateException("Store order " + orderId + " cannot move from " + status + " to " + target);
        }
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public StoreOrderStatus getStatus() {
        return status;
    }

    public List<StoreOrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int getItemCount() {
        return itemCount;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerArea() {
        return customerArea;
    }

    public String getNote() {
        return note;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getAcceptBy() {
        return acceptBy;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Integer getReadyInMinutes() {
        return readyInMinutes;
    }

    public Instant getReadyBy() {
        return readyBy;
    }

    public Instant getPreparingAt() {
        return preparingAt;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public String getRejectionNote() {
        return rejectionNote;
    }

    public boolean isRejectionAuto() {
        return Boolean.TRUE.equals(rejectionAuto);
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public UUID getRejectedBy() {
        return rejectedBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<StoreOrderStatusChange> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
