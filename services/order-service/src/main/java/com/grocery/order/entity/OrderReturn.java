package com.grocery.order.entity;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A return request of a delivered order (docs/api/phase-12-post-mvp-features.md §3): the lines and quantities, the
 * refund the server computed for them (never the client), and the decision. The order's number, type, customer and
 * store are snapshotted at creation.
 *
 * <p>Every transition checks the current status and throws {@link IllegalStateException} in the wrong one, so callers
 * check first (and answer 409 RETURN_NOT_PENDING). Callers hold the order's row lock, then this row's
 * ({@code service/ReturnService}); the escalation poller holds only this row's ({@code FOR UPDATE SKIP LOCKED}).
 */
@Entity
@Table(name = "order_return")
public class OrderReturn {

    public static final int NOTE_MAX = 500;
    public static final int COMMENT_MAX = 300;

    @Id
    private UUID id;

    @Column(name = "return_number", nullable = false, length = 12, updatable = false)
    private String returnNumber;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 12, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10, updatable = false)
    private OrderType orderType;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "store_name", length = 150, updatable = false)
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReturnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReturnReason reason;

    @Column(length = NOTE_MAX, updatable = false)
    private String note;

    @ElementCollection
    @CollectionTable(name = "order_return_item", joinColumns = @JoinColumn(name = "return_id"))
    @OrderColumn(name = "line_no")
    private List<ReturnItem> items = new ArrayList<>();

    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal refundAmount;

    @Column(name = "delivery_fee_refund", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deliveryFeeRefund;

    private Boolean restock;

    @Column(name = "decide_by", nullable = false, updatable = false)
    private Instant decideBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by_role", length = 10)
    private DeciderRole decidedByRole;

    @Column(length = COMMENT_MAX)
    private String comment;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected OrderReturn() {
    }

    /**
     * A new REQUESTED return of {@code order}.
     *
     * @param deliveryFeeRefund the paid delivery fee when the request returns every remaining unit, else 0.00
     */
    public static OrderReturn request(UUID id, String returnNumber, CustomerOrder order, List<ReturnItem> items,
                                      BigDecimal deliveryFeeRefund, ReturnReason reason, String note,
                                      Instant decideBy, Instant now) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a return needs at least one line");
        }
        OrderReturn request = new OrderReturn();
        request.id = id;
        request.returnNumber = returnNumber;
        request.orderId = order.getId();
        request.orderNumber = order.getOrderNumber();
        request.orderType = order.getType();
        request.customerId = order.getCustomerId();
        request.storeId = order.getStoreId();
        request.storeName = order.getStoreName();
        request.status = ReturnStatus.REQUESTED;
        request.reason = reason;
        request.note = note;
        request.items = new ArrayList<>(items);
        request.deliveryFeeRefund = deliveryFeeRefund;
        request.refundAmount = items.stream().map(ReturnItem::getRefundAmount)
                .reduce(deliveryFeeRefund, BigDecimal::add);
        request.decideBy = decideBy;
        request.requestedAt = now;
        request.updatedAt = now;
        return request;
    }

    // ---- transitions ------------------------------------------------------------------------------------------

    /** REQUESTED → ESCALATED: the store did not decide by {@code decideBy}; admins decide it now. */
    public void escalate(Instant now) {
        require(ReturnStatus.REQUESTED);
        status = ReturnStatus.ESCALATED;
        escalatedAt = now;
        updatedAt = now;
    }

    /**
     * REQUESTED or ESCALATED → APPROVED ({@code restock}: the goods came back sellable; the caller forces false for a
     * restaurant order). payment-service refunds {@code refundAmount}.
     */
    public void approve(boolean restock, UUID decidedBy, DeciderRole role, Instant now) {
        requireOpen();
        this.restock = restock;
        decide(ReturnStatus.APPROVED, decidedBy, role, null, now);
    }

    /** REQUESTED or ESCALATED → REJECTED with the decider's comment (shown to the customer). */
    public void reject(String comment, UUID decidedBy, DeciderRole role, Instant now) {
        requireOpen();
        decide(ReturnStatus.REJECTED, decidedBy, role, comment, now);
    }

    /** APPROVED → REFUNDED: payment-service refunded it ({@code RefundCompleted} with this return's id). */
    public void refunded(UUID refundId, Instant now) {
        require(ReturnStatus.APPROVED);
        this.refundId = refundId;
        this.refundedAt = now;
        status = ReturnStatus.REFUNDED;
        updatedAt = now;
    }

    private void decide(ReturnStatus target, UUID decidedBy, DeciderRole role, String comment, Instant now) {
        this.status = target;
        this.decidedBy = decidedBy;
        this.decidedByRole = role;
        this.comment = comment;
        this.decidedAt = now;
        this.updatedAt = now;
    }

    private void require(ReturnStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Return " + returnNumber + " is " + status + ", not " + expected);
        }
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw new IllegalStateException("Return " + returnNumber + " is " + status + ", not REQUESTED or ESCALATED");
        }
    }

    // ---- getters ----------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getReturnNumber() {
        return returnNumber;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getStoreName() {
        return storeName;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public List<ReturnItem> getItems() {
        return items;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public BigDecimal getDeliveryFeeRefund() {
        return deliveryFeeRefund;
    }

    public Boolean getRestock() {
        return restock;
    }

    public Instant getDecideBy() {
        return decideBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public DeciderRole getDecidedByRole() {
        return decidedByRole;
    }

    public String getComment() {
        return comment;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
