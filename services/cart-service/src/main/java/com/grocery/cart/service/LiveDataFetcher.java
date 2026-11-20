package com.grocery.cart.service;

import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.CatalogClient;
import com.grocery.cart.client.DownstreamDtos.Availability;
import com.grocery.cart.client.DownstreamDtos.PriceQuote;
import com.grocery.cart.client.DownstreamDtos.ProductLookup;
import com.grocery.cart.client.DownstreamDtos.VendorStore;
import com.grocery.cart.client.InventoryClient;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.client.PricingClient;
import com.grocery.cart.client.RestaurantClient;
import com.grocery.cart.client.VendorClient;
import com.grocery.cart.model.StoredCart;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fetches the live data of a cart: for a grocery cart the product lookup, price quote, stock availability and the
 * vendor's store — four independent batch calls made in parallel (virtual threads) — and for a restaurant cart one
 * menu quote. Calls never throw (see {@link Outcome}); the caller's token is captured once in a {@link CallContext}.
 */
@Component
public class LiveDataFetcher {

    private final CatalogClient catalog;
    private final PricingClient pricing;
    private final InventoryClient inventory;
    private final VendorClient vendors;
    private final RestaurantClient restaurants;

    public LiveDataFetcher(CatalogClient catalog, PricingClient pricing, InventoryClient inventory,
                           VendorClient vendors, RestaurantClient restaurants) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.inventory = inventory;
        this.vendors = vendors;
        this.restaurants = restaurants;
    }

    /** Everything needed to show {@code cart}. */
    public LiveData forCart(CallContext context, StoredCart cart) {
        if (cart.isEmpty()) {
            return LiveData.NONE;
        }
        return switch (cart.store().type()) {
            case GROCERY -> grocery(context, cart.itemIds(), cart.store().id());
            case RESTAURANT -> restaurant(context, cart.store().id(), cart.itemIds());
        };
    }

    /** Reuses {@code fetched} when it covers {@code cart} (fetching only a missing vendor store), else refetches. */
    public LiveData completeFor(CallContext context, StoredCart cart, LiveData fetched) {
        if (fetched.covers(cart)) {
            return fetched;
        }
        if (fetched instanceof LiveData.Grocery grocery && grocery.coversItems(cart)) {
            UUID vendorId = cart.store().id();
            return grocery.withStore(vendorId, vendors.store(context, vendorId));
        }
        return forCart(context, cart);
    }

    /**
     * Lookup, quote and availability of {@code productIds} and, when {@code vendorId} is given, the vendor's
     * store — in parallel.
     */
    public LiveData.Grocery grocery(CallContext context, Collection<UUID> productIds, UUID vendorId) {
        Set<UUID> ids = new LinkedHashSet<>(productIds);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Outcome<ProductLookup>> lookup = executor.submit(() -> catalog.lookup(context, ids));
            Future<Outcome<PriceQuote>> quote = executor.submit(() -> pricing.quote(context, ids));
            Future<Outcome<Availability>> stock = executor.submit(() -> inventory.availability(context, ids));
            Future<Outcome<VendorStore>> store = vendorId == null ? null
                    : executor.submit(() -> vendors.store(context, vendorId));
            return new LiveData.Grocery(Set.copyOf(ids), Parallel.join(lookup), Parallel.join(quote),
                    Parallel.join(stock), vendorId, store == null ? null : Parallel.join(store));
        }
    }

    public LiveData.Restaurant restaurant(CallContext context, UUID restaurantId, Collection<UUID> itemIds) {
        Set<UUID> ids = new LinkedHashSet<>(itemIds);
        return new LiveData.Restaurant(restaurantId, Set.copyOf(ids),
                restaurants.menuQuote(context, restaurantId, ids));
    }
}
