package com.grocery.catalogquery.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Our own (tolerant-reader) view of the Phase 3 snapshot events — only the fields we index. Field names follow
 * docs/api/phase-3-catalog-and-search.md §8. Wrapper types everywhere: a missing value is null, never an error.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    public record Ref(String id, String name) {
    }

    public record Attribute(String name, String value) {
    }

    /** catalog.events → ProductUpserted: the complete product on every change. */
    public record ProductUpserted(String productId, String vendorId, String vendorName, Boolean vendorActive,
                                  String name, String description, Ref brand, Ref category, List<Ref> categoryPath,
                                  Boolean categoryActive, BigDecimal unitValue, String unitOfMeasure,
                                  String unitLabel, String sku, String barcode, String foodType, List<String> tags,
                                  List<Attribute> attributes, List<String> imageUrls, String status,
                                  Instant publishedAt, Instant updatedAt, Long productVersion) {
    }

    /** catalog.events → CategoryChanged: the complete category (or its deletion). */
    public record CategoryChanged(String categoryId, String parentId, String name, String description,
                                  String imageUrl, Integer sortOrder, Integer depth, List<Ref> path, Boolean active,
                                  Boolean effectiveActive, Boolean deleted, Long categoryVersion) {
    }

    /** pricing.events → PriceChanged: the product's live price (docs/api/phase-4-inventory-and-pricing.md §8). */
    public record PriceChanged(String productId, String vendorId, BigDecimal mrp, BigDecimal sellingPrice,
                               String currency, Long priceVersion) {
    }

    /** inventory.events → ProductAvailabilityChanged: the product went in or out of stock. */
    public record ProductAvailabilityChanged(String productId, String vendorId, Boolean inStock, Long stockVersion) {
    }

    /** restaurant.events → RestaurantListingUpdated: the complete public listing including the menu. */
    public record RestaurantListingUpdated(String restaurantId, String status, String name, String description,
                                           List<String> cuisines, Address address,
                                           List<OpeningHoursEntry> openingHours, Boolean acceptingOrders,
                                           List<MenuCategory> menu, Long listingVersion, Instant updatedAt) {
    }

    public record Address(String line1, String line2, String city, String state, String postalCode,
                          String country, Double latitude, Double longitude) {
    }

    /** "HH:mm" times; closesAt before opensAt = open past midnight. */
    public record OpeningHoursEntry(String dayOfWeek, String opensAt, String closesAt) {
    }

    public record MenuCategory(String categoryId, String name, String description, Integer sortOrder,
                               List<MenuItem> items) {
    }

    public record MenuItem(String itemId, String name, String description, BigDecimal price, String foodType,
                           String imageUrl, Boolean available, Integer sortOrder) {
    }

    /**
     * review.events → RatingSummaryUpdated: the published-rating summary of one target
     * (docs/api/phase-12-post-mvp-features.md §1); only PRODUCT and RESTAURANT targets are indexed here.
     */
    public record RatingSummaryUpdated(String targetType, String targetId, BigDecimal averageRating,
                                       Integer ratingCount, Long summaryVersion) {
    }
}
