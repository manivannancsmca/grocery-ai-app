package com.grocery.catalogquery.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * {@code restaurants} index document (id = restaurantId). Since Phase 12 it has two independently versioned parts,
 * each written only by its own event and only when that event's version is newer ({@code SearchDocumentWriter.merge}):
 * <ul>
 *   <li>the listing ({@link #LISTING_FIELDS}, version {@code listingVersion}) — restaurant.events
 *       {@code RestaurantListingUpdated};</li>
 *   <li>the rating ({@link #RATING_FIELDS}, version {@code ratingVersion} = summaryVersion) — review.events
 *       {@code RatingSummaryUpdated}.</li>
 * </ul>
 *
 * @param locality            address line 2
 * @param location            geo_point; absent when the restaurant has no coordinates
 * @param openingHours        searchable weekly hours (nested), overnight ranges split at midnight
 * @param openingHoursDisplay the weekly hours as published ("HH:mm"), for the restaurant page
 * @param pureVeg             the menu has at least one item and every item is VEG
 * @param visible             status APPROVED (listing part); false on a placeholder created by a rating event before
 *                            the listing arrived
 * @param menuItems           every menu item, searchable (nested, for dish search with inner hits)
 * @param menu                the menu as displayed (categories → items), not indexed
 * @param rating              average of the published ratings (2 decimals); absent while there is none
 * @param ratingCount         number of published ratings
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantDocument(
        String restaurantId,
        String name,
        String description,
        List<String> cuisines,
        String cuisinesText,
        String city,
        String locality,
        GeoPoint location,
        Address address,
        List<OpeningRange> openingHours,
        List<OpeningHoursDisplay> openingHoursDisplay,
        Boolean acceptingOrders,
        Boolean pureVeg,
        String status,
        Boolean visible,
        List<SearchableMenuItem> menuItems,
        List<MenuCategory> menu,
        Long listingVersion,
        Instant updatedAt,
        BigDecimal rating,
        Integer ratingCount,
        Long ratingVersion) {

    public static final String LISTING_VERSION = "listingVersion";
    public static final String RATING_VERSION = "ratingVersion";

    /** Fields owned by review.events (RatingSummaryUpdated). */
    public static final List<String> RATING_FIELDS = List.of("rating", "ratingCount", RATING_VERSION);
    /** Everything else: owned by the listing snapshot. */
    public static final List<String> LISTING_FIELDS = Arrays.stream(RestaurantDocument.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !RATING_FIELDS.contains(name))
            .toList();

    public record GeoPoint(Double lat, Double lon) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Address(String line1, String line2, String city, String state, String postalCode,
                          String country, Double latitude, Double longitude) {
    }

    /** One opening range within a single day, in minutes after midnight: opensMinute ≤ t < closesMinute. */
    public record OpeningRange(String dayOfWeek, Integer opensMinute, Integer closesMinute) {
    }

    public record OpeningHoursDisplay(String dayOfWeek, String opensAt, String closesAt) {
    }

    public record SearchableMenuItem(String itemId, String name, String categoryName, BigDecimal price,
                                     String foodType, Boolean available) {
    }

    public record MenuCategory(String id, String name, String description, Integer sortOrder,
                               List<MenuItem> items) {
    }

    public record MenuItem(String id, String name, String description, BigDecimal price, String foodType,
                           String imageUrl, Boolean available, Integer sortOrder) {
    }

    /**
     * The rating part of the document, as written by a {@code RatingSummaryUpdated} event; {@code rating} is null
     * (absent) while the restaurant has no published rating, so it sorts last and never matches {@code minRating}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RatingPart(String restaurantId, BigDecimal rating, Integer ratingCount, Long ratingVersion) {
    }
}
