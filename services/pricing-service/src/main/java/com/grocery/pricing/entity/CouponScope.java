package com.grocery.pricing.entity;

/** Who a coupon belongs to and who funds it. */
public enum CouponScope {
    /** Created by an admin, funded by the platform; usable at any store (optionally one store type). */
    PLATFORM,
    /** Created by a vendor or restaurant owner (or an admin for them), funded by that store; only at that store. */
    STORE
}
