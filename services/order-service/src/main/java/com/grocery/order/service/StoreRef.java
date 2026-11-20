package com.grocery.order.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.order.entity.OrderType;

import java.util.UUID;

/**
 * The caller's store for the store endpoints ({@code /api/store/returns}), from the validated JWT like every other
 * store endpoint: a VENDOR's {@code vendor_id} claim (GROCERY orders), a RESTAURANT_OWNER's {@code restaurant_id}
 * claim (RESTAURANT orders). Without the claim (not approved yet) → 403 VENDOR_NOT_ACTIVE / RESTAURANT_NOT_ACTIVE.
 */
public record StoreRef(OrderType type, UUID storeId) {

    public static StoreRef of(CurrentUser user) {
        if (user.hasRole(Roles.VENDOR)) {
            return new StoreRef(OrderType.GROCERY, user.requireVendorId());
        }
        if (user.hasRole(Roles.RESTAURANT_OWNER)) {
            return new StoreRef(OrderType.RESTAURANT, user.requireRestaurantId());
        }
        throw new ForbiddenOperationException("STORE_REQUIRED", "A vendor or restaurant account is required");
    }
}
