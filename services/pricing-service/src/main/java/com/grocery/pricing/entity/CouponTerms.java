package com.grocery.pricing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything a coupon says, validated and normalised (code upper-case, amounts with 2 decimals, fields that do not
 * apply to the discount type null). What {@link Coupon} stores and what the redemption rules read.
 *
 * @param storeType       GROCERY / RESTAURANT / ANY (ANY only for PLATFORM)
 * @param storeId         STORE only (vendor id or restaurant id), else null
 * @param value           PERCENT: 1-90; FLAT: the amount; FREE_DELIVERY: null
 * @param maxDiscount     PERCENT only, optional
 * @param validUntil      exclusive
 * @param totalUsageLimit null = unlimited
 */
public record CouponTerms(String code, String name, String description, CouponScope scope, CouponStoreType storeType,
                          UUID storeId, DiscountType discountType, BigDecimal value, BigDecimal maxDiscount,
                          BigDecimal minOrderSubtotal, Instant validFrom, Instant validUntil, Integer totalUsageLimit,
                          int perCustomerLimit, boolean active, boolean publicOffer) {
}
