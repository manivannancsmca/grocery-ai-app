package com.grocery.order.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An order: the price snapshot taken at checkout (lines, totals, fee, delivery address — never changed afterwards),
 * the status with its history, order-service's view of the payment, and the cancellation / rejection details.
 *
 * <p>The status rules live here: every transition checks the current status and throws
 * {@link IllegalStateException} when called in the wrong one, so callers check the state guard first (see
 * {@code service/OrderEventService}). Every transition appends a history entry and moves {@code updatedAt}.
 * Callers hold the row lock ({@code service/OrderLocks}).
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    public static final String CURRENCY = "INR";
    public static final int CANCELLATION_NOTE_MAX = 320;
    public static final int PARTNER_NAME_MAX = 150;
    public static final int DELIVERY_FAILURE_NOTE_MAX = 300;
    /** pricing-service allows 80 characters too; cut anyway, so a longer name can never fail a placement. */
    private static final int COUPON_NAME_MAX = 80;
    private static final int HISTORY_REASON_MAX = 30;

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, length = 12, updatable = false)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private OrderType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "store_name", length = 150, updatable = false)
    private String storeName;

    /** The store's pickup location from the checkout preview (Phase 9); null when unknown. */
    @Column(name = "store_city", length = 100, updatable = false)
    private String storeCity;

    @Column(name = "store_latitude", updatable = false)
    private Double storeLatitude;

    @Column(name = "store_longitude", updatable = false)
    private Double storeLongitude;

    @ElementCollection
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "first_item_name", length = 200, updatable = false)
    private String firstItemName;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "mrp_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal mrpTotal;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal savings;

    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deliveryFee;

    /**
     * The coupon redeemed at placement (Phase 12 §2): its code and pricing-service's redemption id (null without a
     * coupon), the discount off the item subtotal and off the delivery fee (0.00 without a coupon). {@code total} is
     * already discounted: {@code subtotal + deliveryFee - discount - deliveryFeeDiscount}.
     */
    @Column(name = "coupon_code", length = 40, updatable = false)
    private String couponCode;

    @Column(name = "coupon_redemption_id", updatable = false)
    private UUID couponRedemptionId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discount;

    @Column(name = "delivery_fee_discount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deliveryFeeDiscount;

    /**
     * The coupon's name and who funds it (PLATFORM or STORE), as pricing-service answered the redemption (V11). Only
     * published in the order's events, for the reports; null without a coupon and for orders placed before V11.
     */
    @Column(name = "coupon_name", length = 80, updatable = false)
    private String couponName;

    @Column(name = "coupon_funded_by", length = 10, updatable = false)
    private String couponFundedBy;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Embedded
    private DeliveryAddress deliveryAddress;

    @Column(name = "delivery_distance_km", precision = 6, scale = 1, updatable = false)
    private BigDecimal deliveryDistanceKm;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(name = "cart_updated_at", updatable = false)
    private Instant cartUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "payment_due_at", nullable = false, updatable = false)
    private Instant paymentDueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "last_failure_code", length = 100)
    private String lastFailureCode;

    @Column(name = "last_failure_description", length = 500)
    private String lastFailureDescription;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 30)
    private CancellationReason cancellationReason;

    /** ≤ 300 from the customer / admin; a store rejection is "&lt;reason&gt;: &lt;note&gt;" (≤ 300 + the reason). */
    @Column(name = "cancellation_note", length = CANCELLATION_NOTE_MAX)
    private String cancellationNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 10)
    private Actor cancelledBy;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 30)
    private RejectionReason rejectionReason;

    @ElementCollection
    @CollectionTable(name = "order_rejected_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<RejectedItem> rejectedItems = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "order_status_history", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "seq")
    private List<StatusChange> history = new ArrayList<>();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** The store's acceptance (Phase 8): when, its estimate in minutes and the resulting ready-by time. */
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

    /**
     * The delivery (Phase 9, from delivery.events): the partner assigned to it (cleared when an admin takes the
     * delivery away before pickup), the pickup, and the delivery or its failure.
     */
    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "partner_name", length = PARTNER_NAME_MAX)
    private String partnerName;

    @Column(name = "partner_assigned_at")
    private Instant partnerAssignedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivery_failed_at")
    private Instant deliveryFailedAt;

    @Column(name = "delivery_failure_reason", length = 30)
    private String deliveryFailureReason;

    @Column(name = "delivery_failure_note", length = DELIVERY_FAILURE_NOTE_MAX)
    private String deliveryFailureNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected CustomerOrder() {
    }

    /**
     * Everything an order is created from (the checkout preview, validated by the caller). {@code storeCity},
     * {@code storeLatitude}, {@code storeLongitude}: the preview's store location (Phase 9), null when unknown.
     * {@code coupon}: the redeemed coupon (Phase 12), {@link Coupon#NONE} without one; {@code total} is the discounted
     * total.
     */
    public record Draft(UUID id, String orderNumber, UUID customerId, OrderType type, UUID storeId, String storeName,
                        String storeCity, Double storeLatitude, Double storeLongitude,
                        List<OrderItem> items, int itemCount, BigDecimal subtotal, BigDecimal mrpTotal,
                        BigDecimal savings, BigDecimal deliveryFee, BigDecimal total, DeliveryAddress deliveryAddress,
                        BigDecimal deliveryDistanceKm, String note, Instant cartUpdatedAt, Instant paymentDueAt,
                        Coupon coupon) {

        /** An order without a coupon. */
        public Draft(UUID id, String orderNumber, UUID customerId, OrderType type, UUID storeId, String storeName,
                     String storeCity, Double storeLatitude, Double storeLongitude,
                     List<OrderItem> items, int itemCount, BigDecimal subtotal, BigDecimal mrpTotal,
                     BigDecimal savings, BigDecimal deliveryFee, BigDecimal total, DeliveryAddress deliveryAddress,
                     BigDecimal deliveryDistanceKm, String note, Instant cartUpdatedAt, Instant paymentDueAt) {
            this(id, orderNumber, customerId, type, storeId, storeName, storeCity, storeLatitude, storeLongitude, items,
                    itemCount, subtotal, mrpTotal, savings, deliveryFee, total, deliveryAddress, deliveryDistanceKm,
                    note, cartUpdatedAt, paymentDueAt, Coupon.NONE);
        }
    }

    /**
     * The coupon redeemed for the order (Phase 12 §2): {@code code} and {@code redemptionId} null and both discounts
     * 0.00 without one. {@code name} and {@code fundedBy} (PLATFORM or STORE) as pricing-service named them; null when
     * it did not.
     */
    public record Coupon(String code, UUID redemptionId, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                         String name, String fundedBy) {

        public static final Coupon NONE = new Coupon(null, null, new BigDecimal("0.00"), new BigDecimal("0.00"), null,
                null);
    }

    /** A new order in {@code CREATED}, payment {@code NOT_STARTED}, with its first history entry. */
    public static CustomerOrder create(Draft draft, Instant now) {
        if (draft.items().isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        CustomerOrder order = new CustomerOrder();
        order.id = draft.id();
        order.orderNumber = draft.orderNumber();
        order.customerId = draft.customerId();
        order.type = draft.type();
        order.storeId = draft.storeId();
        order.storeName = draft.storeName();
        order.storeCity = draft.storeCity();
        order.storeLatitude = draft.storeLatitude();
        order.storeLongitude = draft.storeLongitude();
        order.items = new ArrayList<>(draft.items());
        order.itemCount = draft.itemCount();
        order.firstItemName = draft.items().getFirst().getName();
        order.subtotal = draft.subtotal();
        order.mrpTotal = draft.mrpTotal();
        order.savings = draft.savings();
        order.deliveryFee = draft.deliveryFee();
        Coupon coupon = draft.coupon() == null ? Coupon.NONE : draft.coupon();
        order.couponCode = coupon.code();
        order.couponRedemptionId = coupon.redemptionId();
        order.discount = coupon.discount();
        order.deliveryFeeDiscount = coupon.deliveryFeeDiscount();
        order.couponName = cut(coupon.name(), COUPON_NAME_MAX);
        order.couponFundedBy = coupon.fundedBy();
        order.total = draft.total();
        order.currency = CURRENCY;
        order.deliveryAddress = draft.deliveryAddress();
        order.deliveryDistanceKm = draft.deliveryDistanceKm();
        order.note = draft.note();
        order.cartUpdatedAt = draft.cartUpdatedAt();
        order.paymentDueAt = draft.paymentDueAt();
        order.paymentStatus = PaymentStatus.NOT_STARTED;
        order.refundedAmount = new BigDecimal("0.00");
        order.status = OrderStatus.CREATED;
        order.createdAt = now;
        order.updatedAt = now;
        order.history.add(new StatusChange(OrderStatus.CREATED, null, now));
        return order;
    }

    // ---- transitions ------------------------------------------------------------------------------------------

    /** CREATED → AWAITING_PAYMENT (stock reserved, or a restaurant order at once); payment PENDING. */
    public void awaitPayment(Instant now) {
        require(OrderStatus.CREATED);
        paymentStatus = PaymentStatus.PENDING;
        moveTo(OrderStatus.AWAITING_PAYMENT, null, now);
    }

    /** CREATED → REJECTED (OUT_OF_STOCK, with the unavailable lines); nothing is paid, so payment NOT_REQUIRED. */
    public void rejectOutOfStock(List<RejectedItem> unavailable, Instant now) {
        require(OrderStatus.CREATED);
        rejectionReason = RejectionReason.OUT_OF_STOCK;
        rejectedItems.clear();
        rejectedItems.addAll(unavailable);
        paymentStatus = PaymentStatus.NOT_REQUIRED;
        moveTo(OrderStatus.REJECTED, RejectionReason.OUT_OF_STOCK.name(), now);
    }

    /** AWAITING_PAYMENT → CONFIRMED; payment PAID. */
    public void confirm(UUID paymentId, Instant capturedAt, Instant now) {
        require(OrderStatus.AWAITING_PAYMENT);
        if (paymentId != null) {
            this.paymentId = paymentId;
        }
        paymentStatus = PaymentStatus.PAID;
        paidAt = capturedAt != null ? capturedAt : now;
        confirmedAt = now;
        clearLastFailure();
        moveTo(OrderStatus.CONFIRMED, null, now);
    }

    /**
     * The store accepted: CONFIRMED → ACCEPTED, with its estimate ({@code readyInMinutes}) and the time it expects the
     * order to be ready.
     */
    public void accept(int readyInMinutes, Instant readyBy, Instant acceptedAt, Instant now) {
        require(OrderStatus.CONFIRMED);
        this.readyInMinutes = readyInMinutes;
        this.readyBy = readyBy;
        this.acceptedAt = acceptedAt;
        moveTo(OrderStatus.ACCEPTED, null, now);
    }

    /** The store started preparing (restaurant) or packing (grocery): ACCEPTED → PREPARING. */
    public void startPreparing(Instant preparingAt, Instant now) {
        require(OrderStatus.ACCEPTED);
        this.preparingAt = preparingAt;
        moveTo(OrderStatus.PREPARING, null, now);
    }

    /** ACCEPTED or PREPARING → READY_FOR_PICKUP (a small grocery order may skip PREPARING). */
    public void readyForPickup(Instant readyAt, Instant now) {
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.PREPARING) {
            throw new IllegalStateException("Order " + id + " is " + status + ", not ACCEPTED or PREPARING");
        }
        this.readyAt = readyAt;
        moveTo(OrderStatus.READY_FOR_PICKUP, null, now);
    }

    // ---- delivery (Phase 9) -----------------------------------------------------------------------------------

    /** The statuses in which a partner can be assigned or taken away: after acceptance, before pickup. */
    public boolean awaitsPickup() {
        return status == OrderStatus.ACCEPTED || status == OrderStatus.PREPARING
                || status == OrderStatus.READY_FOR_PICKUP;
    }

    /**
     * DeliveryPartnerAssigned (ACCEPTED, PREPARING, READY_FOR_PICKUP): records the partner; no status change, no
     * history entry. {@code partnerName} may be null (not set), a longer name is cut to the column.
     */
    public void assignPartner(UUID partnerId, String partnerName, Instant assignedAt, Instant now) {
        requirePickupPending();
        this.partnerId = partnerId;
        this.partnerName = cut(partnerName, PARTNER_NAME_MAX);
        this.partnerAssignedAt = assignedAt;
        updatedAt = now;
    }

    /**
     * DeliveryPartnerUnassigned (same statuses): clears the partner — only when it is still the one recorded, so an
     * unassignment can never remove a partner assigned after it. Returns false (unchanged) otherwise.
     */
    public boolean unassignPartner(UUID partnerId, Instant now) {
        requirePickupPending();
        if (this.partnerId == null || !this.partnerId.equals(partnerId)) {
            return false;
        }
        this.partnerId = null;
        this.partnerName = null;
        this.partnerAssignedAt = null;
        updatedAt = now;
        return true;
    }

    /**
     * The partner picked the order up: READY_FOR_PICKUP → OUT_FOR_DELIVERY. The partner of the pickup is the
     * delivery's partner from now on (it is the recorded one unless an assignment was missed; then its name is
     * unknown).
     */
    public void pickUp(UUID partnerId, Instant pickedUpAt, Instant now) {
        require(OrderStatus.READY_FOR_PICKUP);
        adoptPartner(partnerId);
        this.pickedUpAt = pickedUpAt;
        moveTo(OrderStatus.OUT_FOR_DELIVERY, null, now);
    }

    /** OUT_FOR_DELIVERY → DELIVERED (terminal). The payment stays PAID. */
    public void deliver(UUID partnerId, Instant deliveredAt, Instant now) {
        require(OrderStatus.OUT_FOR_DELIVERY);
        adoptPartner(partnerId);
        this.deliveredAt = deliveredAt;
        moveTo(OrderStatus.DELIVERED, null, now);
    }

    /**
     * OUT_FOR_DELIVERY → DELIVERY_FAILED (terminal), with the partner's reason (also the history entry's reason) and
     * note (≤ 300). The payment stays PAID: an admin decides on a refund (payment-service), the stock is not returned
     * automatically.
     */
    public void failDelivery(UUID partnerId, String reason, String note, Instant failedAt, Instant now) {
        require(OrderStatus.OUT_FOR_DELIVERY);
        adoptPartner(partnerId);
        this.deliveryFailedAt = failedAt;
        this.deliveryFailureReason = cut(reason, HISTORY_REASON_MAX);
        this.deliveryFailureNote = cut(note == null || note.isBlank() ? null : note.strip(), DELIVERY_FAILURE_NOTE_MAX);
        moveTo(OrderStatus.DELIVERY_FAILED, deliveryFailureReason, now);
    }

    private void adoptPartner(UUID partnerId) {
        if (partnerId != null && !partnerId.equals(this.partnerId)) {
            this.partnerId = partnerId;
            this.partnerName = null;
        }
    }

    private void requirePickupPending() {
        if (!awaitsPickup()) {
            throw new IllegalStateException("Order " + id + " is " + status
                    + ", not ACCEPTED, PREPARING or READY_FOR_PICKUP");
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Any non-terminal status up to READY_FOR_PICKUP → CANCELLED, never after pickup (the widest rule, an admin's;
     * the customer's narrower rule and the event guards are checked by the callers). Money already captured is
     * refunded by payment-service (payment REFUND_PENDING); an unpaid order needs no payment any more
     * (NOT_REQUIRED).
     *
     * @return the status before the cancellation
     */
    public OrderStatus cancel(CancellationReason reason, Actor by, UUID actorUserId, String note, Instant now) {
        if (!status.isAdminCancellable()) {
            throw new IllegalStateException("Order " + id + " is " + status + " and cannot be cancelled");
        }
        if (note != null && note.length() > CANCELLATION_NOTE_MAX) {
            note = note.substring(0, CANCELLATION_NOTE_MAX);
        }
        OrderStatus previous = status;
        cancellationReason = reason;
        cancelledBy = by;
        cancelledByUserId = actorUserId;
        cancellationNote = note;
        cancelledAt = now;
        if (paymentStatus.holdsMoney()) {
            paymentStatus = PaymentStatus.REFUND_PENDING;
        } else if (paymentStatus.isUnpaid()) {
            paymentStatus = PaymentStatus.NOT_REQUIRED;
        }
        clearLastFailure();
        moveTo(OrderStatus.CANCELLED, reason.name(), now);
        return previous;
    }

    /** PaymentExpired: AWAITING_PAYMENT → CANCELLED (PAYMENT_TIMEOUT, by the system); payment EXPIRED. */
    public OrderStatus expirePayment(Instant now) {
        require(OrderStatus.AWAITING_PAYMENT);
        OrderStatus previous = cancel(CancellationReason.PAYMENT_TIMEOUT, Actor.SYSTEM, null, null, now);
        paymentStatus = PaymentStatus.EXPIRED;
        return previous;
    }

    // ---- payment projection (no status change) ----------------------------------------------------------------

    /** PaymentInitiated: payment PENDING while nothing is paid yet; false (unchanged) otherwise. */
    public boolean paymentInitiated(UUID paymentId, Instant now) {
        if (!paymentStatus.isUnpaid()) {
            return false;
        }
        if (paymentId != null) {
            this.paymentId = paymentId;
        }
        paymentStatus = PaymentStatus.PENDING;
        updatedAt = now;
        return true;
    }

    /** PaymentFailed while AWAITING_PAYMENT: the declined attempt becomes {@code lastFailure}. */
    public void paymentFailed(String code, String description, Instant at) {
        require(OrderStatus.AWAITING_PAYMENT);
        lastFailureCode = code;
        lastFailureDescription = description;
        lastFailureAt = at;
        updatedAt = at;
    }

    /**
     * PaymentCaptured after the order was cancelled ("late capture"): payment-service refunds it, so the payment
     * becomes REFUND_PENDING (unless it is already fully refunded).
     */
    public boolean lateCapture(UUID paymentId, Instant now) {
        require(OrderStatus.CANCELLED);
        if (paymentStatus == PaymentStatus.REFUNDED) {
            return false;
        }
        if (paymentId != null) {
            this.paymentId = paymentId;
        }
        paymentStatus = PaymentStatus.REFUND_PENDING;
        updatedAt = now;
        return true;
    }

    /**
     * RefundCompleted: {@code refundedAmount = totalRefunded}; REFUNDED when fully refunded, else PARTIALLY_REFUNDED.
     * A total lower than the one already recorded is stale and ignored (false).
     */
    public boolean refundCompleted(BigDecimal totalRefunded, boolean fullyRefunded, Instant now) {
        if (totalRefunded.compareTo(refundedAmount) < 0) {
            return false;
        }
        refundedAmount = totalRefunded.setScale(2, RoundingMode.HALF_UP);
        paymentStatus = fullyRefunded ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        updatedAt = now;
        return true;
    }

    private void moveTo(OrderStatus target, String reason, Instant now) {
        status = target;
        updatedAt = now;
        history.add(new StatusChange(target, reason, now));
    }

    private void require(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Order " + id + " is " + status + ", not " + expected);
        }
    }

    private void clearLastFailure() {
        lastFailureCode = null;
        lastFailureDescription = null;
        lastFailureAt = null;
    }

    // ---- getters ----------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public OrderType getType() {
        return type;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getStoreName() {
        return storeName;
    }

    public String getStoreCity() {
        return storeCity;
    }

    public Double getStoreLatitude() {
        return storeLatitude;
    }

    public Double getStoreLongitude() {
        return storeLongitude;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public int getItemCount() {
        return itemCount;
    }

    public String getFirstItemName() {
        return firstItemName;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getMrpTotal() {
        return mrpTotal;
    }

    public BigDecimal getSavings() {
        return savings;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public UUID getCouponRedemptionId() {
        return couponRedemptionId;
    }

    /** Off the item subtotal (0.00 without a coupon). */
    public BigDecimal getDiscount() {
        return discount;
    }

    /** Off the delivery fee (FREE_DELIVERY coupons; 0.00 otherwise). */
    public BigDecimal getDeliveryFeeDiscount() {
        return deliveryFeeDiscount;
    }

    /** The coupon's name when the order was placed; null without a coupon or when pricing-service did not name it. */
    public String getCouponName() {
        return couponName;
    }

    /** Who funds the coupon's discounts: PLATFORM or STORE; null without a coupon or when not known. */
    public String getCouponFundedBy() {
        return couponFundedBy;
    }

    /** A coupon was applied to the order. */
    public boolean hasCoupon() {
        return couponCode != null;
    }

    public String getCurrency() {
        return currency;
    }

    public DeliveryAddress getDeliveryAddress() {
        return deliveryAddress;
    }

    public BigDecimal getDeliveryDistanceKm() {
        return deliveryDistanceKm;
    }

    public String getNote() {
        return note;
    }

    public Instant getCartUpdatedAt() {
        return cartUpdatedAt;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getPaymentDueAt() {
        return paymentDueAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public String getLastFailureDescription() {
        return lastFailureDescription;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public CancellationReason getCancellationReason() {
        return cancellationReason;
    }

    public String getCancellationNote() {
        return cancellationNote;
    }

    public Actor getCancelledBy() {
        return cancelledBy;
    }

    public UUID getCancelledByUserId() {
        return cancelledByUserId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public List<RejectedItem> getRejectedItems() {
        return rejectedItems;
    }

    public List<StatusChange> getHistory() {
        return history;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
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

    public UUID getPartnerId() {
        return partnerId;
    }

    public String getPartnerName() {
        return partnerName;
    }

    public Instant getPartnerAssignedAt() {
        return partnerAssignedAt;
    }

    public Instant getPickedUpAt() {
        return pickedUpAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getDeliveryFailedAt() {
        return deliveryFailedAt;
    }

    public String getDeliveryFailureReason() {
        return deliveryFailureReason;
    }

    public String getDeliveryFailureNote() {
        return deliveryFailureNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
