package com.grocery.pricing.entity;

/** The kind of store a coupon is usable at: {@code ANY} only for PLATFORM coupons. */
public enum CouponStoreType {
    GROCERY,
    RESTAURANT,
    ANY;

    public boolean accepts(OrderType orderType) {
        return this == ANY || name().equals(orderType.name());
    }

    public static CouponStoreType of(OrderType orderType) {
        return orderType == OrderType.GROCERY ? GROCERY : RESTAURANT;
    }
}
