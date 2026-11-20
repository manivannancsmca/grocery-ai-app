package com.grocery.cart.service;

import com.grocery.cart.client.DownstreamDtos.Availability;
import com.grocery.cart.client.DownstreamDtos.MenuQuote;
import com.grocery.cart.client.DownstreamDtos.PriceQuote;
import com.grocery.cart.client.DownstreamDtos.ProductLookup;
import com.grocery.cart.client.DownstreamDtos.VendorStore;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.model.StoredCart;

import java.util.Set;
import java.util.UUID;

/**
 * Live data of a cart's items, fetched with one batch call per dependency. Reused for the response of a write when
 * it covers every item of the written cart, so a request calls each dependency once.
 */
public sealed interface LiveData permits LiveData.None, LiveData.Grocery, LiveData.Restaurant {

    LiveData NONE = new None();

    /** True when this data can price {@code cart} as it is now. */
    boolean covers(StoredCart cart);

    /** An empty cart needs no live data. */
    record None() implements LiveData {

        @Override
        public boolean covers(StoredCart cart) {
            return cart.isEmpty();
        }
    }

    /**
     * @param productIds the products asked for
     * @param vendorId   the vendor whose store was asked for, null when it was not ({@code store} null too)
     */
    record Grocery(Set<UUID> productIds, Outcome<ProductLookup> catalog, Outcome<PriceQuote> quotes,
                   Outcome<Availability> stock, UUID vendorId, Outcome<VendorStore> store) implements LiveData {

        public Grocery withStore(UUID storeVendorId, Outcome<VendorStore> vendorStore) {
            return new Grocery(productIds, catalog, quotes, stock, storeVendorId, vendorStore);
        }

        /** Covers the cart's products; the store may still have to be fetched. */
        public boolean coversItems(StoredCart cart) {
            return !cart.isEmpty() && cart.store().type() == StoreType.GROCERY
                    && productIds.containsAll(cart.itemIds());
        }

        @Override
        public boolean covers(StoredCart cart) {
            return coversItems(cart) && store != null && cart.store().id().equals(vendorId);
        }
    }

    record Restaurant(UUID restaurantId, Set<UUID> itemIds, Outcome<MenuQuote> menu) implements LiveData {

        @Override
        public boolean covers(StoredCart cart) {
            return cart.belongsTo(StoreType.RESTAURANT, restaurantId) && itemIds.containsAll(cart.itemIds());
        }
    }
}
