package com.grocery.pricing.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.entity.Coupon;
import com.grocery.pricing.entity.CouponStoreType;

import java.util.UUID;

/**
 * Who manages coupons in a request: an admin (every coupon, chooses the scope) or a store owner (only STORE coupons of
 * their own store). The store comes from the token's {@code vendor_id} / {@code restaurant_id} claim only — 403
 * VENDOR_NOT_ACTIVE / RESTAURANT_NOT_ACTIVE without it.
 *
 * @param storeId   null for an admin
 * @param storeType GROCERY (vendor) or RESTAURANT (restaurant owner); null for an admin
 */
public record CouponOwner(UUID storeId, CouponStoreType storeType) {

    public static final CouponOwner ADMIN = new CouponOwner(null, null);

    public static CouponOwner vendor(CurrentUser user) {
        return new CouponOwner(user.requireVendorId(), CouponStoreType.GROCERY);
    }

    public static CouponOwner restaurant(CurrentUser user) {
        return new CouponOwner(user.requireRestaurantId(), CouponStoreType.RESTAURANT);
    }

    public boolean isAdmin() {
        return storeId == null;
    }

    /** Admins see every coupon; a store owner only the STORE coupons of their store. */
    public boolean canManage(Coupon coupon) {
        return isAdmin() || coupon.belongsToStore(storeId);
    }
}
