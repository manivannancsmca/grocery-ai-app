package com.grocery.cart.model;

import java.util.UUID;

/**
 * The store of a cart: a vendor (GROCERY, id = vendorId) or a restaurant (RESTAURANT, id = restaurantId).
 * {@code name} is the name seen when an item was last added (live data replaces it when shown).
 */
public record Store(UUID id, StoreType type, String name) {

    public boolean sameAs(Store other) {
        return other != null && id.equals(other.id()) && type == other.type();
    }
}
