package com.grocery.reporting.facts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What one event knows about an order: every field is optional ({@code null} = unknown to this event, the stored
 * value is kept). {@link FactsRepository#upsertOrder} merges it so that any arrival order gives the same row:
 * timestamps (and the reasons that go with them) keep the first value, descriptive fields take the latest non-null
 * value (identical in every event of an order).
 */
public final class OrderUpdate {

    final UUID orderId;
    String orderNumber;
    String orderType;
    UUID customerId;
    UUID storeId;
    String storeName;
    String currency;
    BigDecimal subtotal;
    BigDecimal deliveryFee;
    BigDecimal discount;
    BigDecimal deliveryFeeDiscount;
    BigDecimal total;
    String couponCode;
    String couponName;
    String couponFundedBy;
    Instant createdAt;
    Instant confirmedAt;
    Instant cancelledAt;
    String cancelReason;
    String cancelledBy;
    String cancelledFromStatus;
    Instant rejectedAt;
    String rejectReason;
    Instant deliveredAt;
    Instant deliveryFailedAt;
    String deliveryFailureReason;
    UUID partnerId;
    BigDecimal capturedAmount;
    Instant capturedAt;
    Boolean captureLate;

    public OrderUpdate(UUID orderId) {
        this.orderId = orderId;
    }

    public OrderUpdate header(String orderNumber, String orderType, UUID customerId, UUID storeId) {
        this.orderNumber = orderNumber;
        this.orderType = orderType;
        this.customerId = customerId;
        this.storeId = storeId;
        return this;
    }

    public OrderUpdate storeName(String storeName) {
        this.storeName = storeName;
        return this;
    }

    public OrderUpdate amounts(String currency, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total) {
        this.currency = currency;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.total = total;
        return this;
    }

    /**
     * The coupon as the order events carry it. {@code name} and {@code fundedBy} (PLATFORM / STORE) came with schemas
     * 1.7.0: events of older orders have neither, so those orders stay without them.
     */
    public OrderUpdate coupon(String couponCode, BigDecimal discount, BigDecimal deliveryFeeDiscount, String name,
                              String fundedBy) {
        this.couponCode = couponCode == null || couponCode.isBlank() ? null : couponCode.strip().toUpperCase();
        this.discount = discount;
        this.deliveryFeeDiscount = deliveryFeeDiscount;
        this.couponName = name == null || name.isBlank() ? null : name.strip();
        this.couponFundedBy = fundedBy == null || fundedBy.isBlank() ? null : fundedBy.strip().toUpperCase();
        return this;
    }

    public OrderUpdate created(Instant at) {
        this.createdAt = at;
        return this;
    }

    public OrderUpdate confirmed(Instant at) {
        this.confirmedAt = at;
        return this;
    }

    public OrderUpdate cancelled(Instant at, String reason, String by, String fromStatus) {
        this.cancelledAt = at;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.cancelledFromStatus = fromStatus;
        return this;
    }

    public OrderUpdate rejected(Instant at, String reason) {
        this.rejectedAt = at;
        this.rejectReason = reason;
        return this;
    }

    public OrderUpdate delivered(Instant at, UUID partnerId) {
        this.deliveredAt = at;
        this.partnerId = partnerId;
        return this;
    }

    public OrderUpdate deliveryFailed(Instant at, String reason, UUID partnerId) {
        this.deliveryFailedAt = at;
        this.deliveryFailureReason = reason;
        this.partnerId = partnerId;
        return this;
    }

    public OrderUpdate captured(BigDecimal amount, Instant at, Boolean late) {
        this.capturedAmount = amount;
        this.capturedAt = at;
        this.captureLate = late;
        return this;
    }

    public UUID orderId() {
        return orderId;
    }
}
