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

/**
 * One use of a coupon by one order ({@code order_id} unique). Created RESERVED by {@code redeem} while the coupon row
 * is locked; changed only while its own row is locked (the order-events consumer, the sweeper), so no version column.
 */
@Entity
@Table(name = "coupon_redemption")
public class CouponRedemption {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", length = 20)
    private String orderNumber;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private UUID couponId;

    @Column(name = "coupon_code", nullable = false, length = 20, updatable = false)
    private String couponCode;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 12, updatable = false)
    private OrderType orderType;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discount;

    @Column(name = "delivery_fee_discount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deliveryFeeDiscount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RedemptionStatus status;

    @Column(name = "reserved_at", nullable = false, updatable = false)
    private Instant reservedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 30)
    private ReleaseReason releaseReason;

    protected CouponRedemption() {
    }

    public static CouponRedemption reserve(UUID id, UUID orderId, Coupon coupon, UUID customerId, UUID storeId,
                                           OrderType orderType, BigDecimal subtotal, BigDecimal deliveryFee,
                                           BigDecimal discount, BigDecimal deliveryFeeDiscount, Instant now) {
        CouponRedemption redemption = new CouponRedemption();
        redemption.id = id;
        redemption.orderId = orderId;
        redemption.couponId = coupon.getId();
        redemption.couponCode = coupon.getCode();
        redemption.customerId = customerId;
        redemption.storeId = storeId;
        redemption.orderType = orderType;
        redemption.subtotal = subtotal;
        redemption.deliveryFee = deliveryFee;
        redemption.discount = discount;
        redemption.deliveryFeeDiscount = deliveryFeeDiscount;
        redemption.status = RedemptionStatus.RESERVED;
        redemption.reservedAt = now;
        return redemption;
    }

    /**
     * OrderCreated: RESERVED → CONFIRMED. A redemption the sweeper released (the event came later than the TTL) is
     * confirmed again: the order exists and carries the discount. One released because the order was cancelled or
     * rejected stays released. False when nothing changed.
     */
    public boolean confirm(Instant now) {
        boolean revivable = status == RedemptionStatus.RELEASED && releaseReason == ReleaseReason.RESERVATION_EXPIRED;
        if (status != RedemptionStatus.RESERVED && !revivable) {
            return false;
        }
        status = RedemptionStatus.CONFIRMED;
        confirmedAt = now;
        releasedAt = null;
        releaseReason = null;
        return true;
    }

    /** The order number from an order event (redeem only knows the order id); the first one known is kept. */
    public void learnOrderNumber(String number) {
        if (orderNumber == null && number != null && !number.isBlank() && number.length() <= 20) {
            orderNumber = number;
        }
    }

    /** RESERVED / CONFIRMED → RELEASED; false when already released. */
    public boolean release(ReleaseReason reason, Instant now) {
        if (status == RedemptionStatus.RELEASED) {
            return false;
        }
        status = RedemptionStatus.RELEASED;
        releasedAt = now;
        releaseReason = reason;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getCouponId() {
        return couponId;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getDeliveryFeeDiscount() {
        return deliveryFeeDiscount;
    }

    public RedemptionStatus getStatus() {
        return status;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public ReleaseReason getReleaseReason() {
        return releaseReason;
    }
}
