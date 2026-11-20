package com.grocery.restaurant.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code RestaurantListingUpdated} v1 on {@code restaurant.events} (key = restaurantId): the complete public
 * listing of a restaurant that was ever approved. Field names are the contract with catalog-query's
 * restaurant indexer (docs/api/phase-3-catalog-and-search.md §8) — do not rename.
 *
 * <p>{@code listingVersion} increases with every change and is used as the Elasticsearch external version;
 * a republish re-sends the current snapshot with its current (unchanged) version. Categories and items are
 * sorted by sortOrder, then name; unavailable items are included with {@code available = false}.
 *
 * @param status    APPROVED or SUSPENDED (search shows only APPROVED)
 * @param updatedAt when the listing last changed
 */
public record RestaurantListingUpdated(UUID restaurantId, UUID ownerUserId, String status, String name,
                                       String description, List<String> cuisines, Address address,
                                       List<Hours> openingHours, boolean acceptingOrders, List<Category> menu,
                                       long listingVersion, Instant updatedAt) {

    public record Address(String line1, String line2, String city, String state, String postalCode,
                          String country, Double latitude, Double longitude) {
    }

    /** Times are "HH:mm"; closesAt before opensAt = closes after midnight. */
    public record Hours(String dayOfWeek, String opensAt, String closesAt) {
    }

    public record Category(UUID categoryId, String name, String description, int sortOrder, List<Item> items) {
    }

    /** {@code price}: INR, 2 decimals. */
    public record Item(UUID itemId, String name, String description, BigDecimal price, String foodType,
                       String imageUrl, boolean available, int sortOrder) {
    }
}
