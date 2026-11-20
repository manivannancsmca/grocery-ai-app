package com.grocery.cart.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * The coupon applied to a cart (Phase 12 §2), as pricing-service described it when it was applied. Only the code is
 * authoritative: the coupon is evaluated live every time the cart is shown.
 *
 * @param code      upper-case
 * @param scope     PLATFORM or STORE (null = unknown, treated as PLATFORM)
 * @param storeId   the store of a STORE coupon, else null
 * @param appliedAt when it was applied
 */
public record StoredCoupon(String code, String scope, UUID storeId, Instant appliedAt) {

    public static final String STORE_SCOPE = "STORE";

    /** A STORE coupon of a store other than {@code store}: it must go when the cart moves to that store. */
    @JsonIgnore
    public boolean isStoreCouponOtherThan(Store store) {
        return STORE_SCOPE.equals(scope) && storeId != null && (store == null || !storeId.equals(store.id()));
    }
}
