package com.grocery.pricing.entity;

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
 * A promotion code (Phase 12 §2). Its terms are replaced as a whole ({@link #update}); pause / resume flip
 * {@code active}. Concurrent edits are caught by {@code @Version} (409 CONCURRENT_MODIFICATION). Redemptions lock the
 * row ({@code SELECT … FOR UPDATE}) while they count the uses, see {@code CouponRedemptionService}.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CouponScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_type", nullable = false, length = 12)
    private CouponStoreType storeType;

    @Column(name = "store_id")
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 15)
    private DiscountType discountType;

    @Column(name = "discount_value", precision = 12, scale = 2)
    private BigDecimal value;

    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "min_order_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderSubtotal;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "total_usage_limit")
    private Integer totalUsageLimit;

    @Column(name = "per_customer_limit", nullable = false)
    private int perCustomerLimit;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "public_offer", nullable = false)
    private boolean publicOffer;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Coupon() {
    }

    public static Coupon create(UUID id, CouponTerms terms, UUID by, Instant now) {
        Coupon coupon = new Coupon();
        coupon.id = id;
        coupon.createdBy = by;
        coupon.createdAt = now;
        coupon.update(terms, by, now);
        return coupon;
    }

    /** Replaces every term (the caller validated them). */
    public void update(CouponTerms terms, UUID by, Instant now) {
        code = terms.code();
        name = terms.name();
        description = terms.description();
        scope = terms.scope();
        storeType = terms.storeType();
        storeId = terms.storeId();
        discountType = terms.discountType();
        value = terms.value();
        maxDiscount = terms.maxDiscount();
        minOrderSubtotal = terms.minOrderSubtotal();
        validFrom = terms.validFrom();
        validUntil = terms.validUntil();
        totalUsageLimit = terms.totalUsageLimit();
        perCustomerLimit = terms.perCustomerLimit();
        active = terms.active();
        publicOffer = terms.publicOffer();
        updatedBy = by;
        updatedAt = now;
    }

    /** Pause ({@code false}) or resume ({@code true}); false when it already was. */
    public boolean setActive(boolean newActive, UUID by, Instant now) {
        if (active == newActive) {
            return false;
        }
        active = newActive;
        updatedBy = by;
        updatedAt = now;
        return true;
    }

    public CouponTerms terms() {
        return new CouponTerms(code, name, description, scope, storeType, storeId, discountType, value, maxDiscount,
                minOrderSubtotal, validFrom, validUntil, totalUsageLimit, perCustomerLimit, active, publicOffer);
    }

    /** A STORE coupon of exactly this store. */
    public boolean belongsToStore(UUID store) {
        return scope == CouponScope.STORE && storeId != null && storeId.equals(store);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public CouponScope getScope() {
        return scope;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
