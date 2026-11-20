package com.grocery.catalogquery.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public catalog responses (docs/api/phase-3-catalog-and-search.md §2 and §6; price and stock:
 * docs/api/phase-4-inventory-and-pricing.md §6; ratings: docs/api/phase-12-post-mvp-features.md §1) and the internal
 * product lookup (docs/api/phase-5-cart-and-checkout-preview.md §4).
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record Ref(String id, String name) {
    }

    public record NameValue(String name, String value) {
    }

    // ------------------------------------------------------------------ products

    /** PageResponse&lt;ProductCard&gt; + facets. */
    public record ProductSearchResponse(List<ProductCard> content, int page, int size, long totalElements,
                                        int totalPages, ProductFacets facets) {
    }

    public record ProductFacets(List<FacetEntry> categories, List<FacetEntry> brands, List<FacetValue> foodTypes) {

        public static ProductFacets empty() {
            return new ProductFacets(List.of(), List.of(), List.of());
        }
    }

    public record FacetEntry(String id, String name, long count) {
    }

    public record FacetValue(String value, long count) {
    }

    /** The live grocery price (Phase 4); money as JSON numbers with 2 decimals. */
    public record Price(BigDecimal mrp, BigDecimal sellingPrice, String currency, int discountPercent) {
    }

    /**
     * @param price       null while the vendor has not priced the product
     * @param inStock     false until stock is added (and whenever it runs out)
     * @param rating      average of the published ratings, 2 decimals (Phase 12); null while unrated
     * @param ratingCount number of published ratings (0 while unrated)
     * @param shortDescription the description, at most 300 characters (longer ones are cut at a word and end in "…";
     *                    the full text is on the product detail); null when the product has none (2026-11-20: the
     *                    shopping assistant read each product's detail to know what a product is)
     */
    public record ProductCard(String id, String name, String unitLabel, String foodType, String primaryImageUrl,
                              Ref brand, Ref category, Ref vendor, Price price, boolean inStock,
                              BigDecimal rating, int ratingCount, String shortDescription) {
    }

    /** ProductCard + the detail fields. */
    public record ProductDetail(String id, String name, String unitLabel, String foodType, String primaryImageUrl,
                                Ref brand, Ref category, Ref vendor, Price price, boolean inStock,
                                BigDecimal rating, int ratingCount,
                                String description, BigDecimal unitValue,
                                String unitOfMeasure, String sku, String barcode, List<String> tags,
                                List<NameValue> attributes, List<String> imageUrls, List<Ref> categoryPath,
                                Instant publishedAt, Instant updatedAt) {
    }

    public record SuggestResponse(List<ProductSuggestion> products, List<CategorySuggestion> categories) {
    }

    public record ProductSuggestion(String id, String name, String brandName, String unitLabel,
                                    String primaryImageUrl) {
    }

    public record CategorySuggestion(String id, String name, List<String> path) {
    }

    /** Active category with its visible product count (the whole subtree), children sorted by sortOrder, name. */
    public record CategoryNode(String id, String name, String description, String imageUrl, long productCount,
                               List<CategoryNode> children) {
    }

    // ------------------------------------------------------------------ restaurants

    /** PageResponse&lt;RestaurantCard&gt; + facets. */
    public record RestaurantSearchResponse(List<RestaurantCard> content, int page, int size, long totalElements,
                                           int totalPages, RestaurantFacets facets) {
    }

    public record RestaurantFacets(List<FacetValue> cuisines) {
    }

    /** @param rating average of the published ratings, 2 decimals (Phase 12); null while unrated (ratingCount 0) */
    public record RestaurantCard(String id, String name, String description, List<String> cuisines, String city,
                                 String locality, Double distanceKm, boolean openNow, boolean acceptingOrders,
                                 boolean pureVeg, List<MatchingItem> matchingItems, BigDecimal rating,
                                 int ratingCount) {
    }

    public record MatchingItem(String id, String name, BigDecimal price, String foodType) {
    }

    public record RestaurantDetail(String id, String name, String description, List<String> cuisines,
                                   boolean openNow, boolean acceptingOrders, boolean pureVeg, Address address,
                                   List<OpeningHoursView> openingHours, List<MenuCategoryView> menu,
                                   BigDecimal rating, int ratingCount) {
    }

    public record Address(String line1, String line2, String city, String state, String postalCode,
                          Double latitude, Double longitude) {
    }

    public record OpeningHoursView(String dayOfWeek, String opensAt, String closesAt) {
    }

    public record MenuCategoryView(String id, String name, String description, List<MenuItemView> items) {
    }

    public record MenuItemView(String id, String name, String description, BigDecimal price, String foodType,
                               boolean available, String imageUrl) {
    }

    // ------------------------------------------------------------------ internal (service-to-service)

    /** {@code POST /internal/catalog/products/lookup} (docs/api/phase-5-cart-and-checkout-preview.md §4). */
    public record ProductLookupRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> productIds) {
    }

    /**
     * @param items   cards of the visible products, in request order (duplicates removed)
     * @param missing the requested ids that are not visible: unknown, hidden (unpublished, archived, vendor or
     *                category inactive) or only a price/stock placeholder — in request order
     */
    public record ProductLookupResponse(List<ProductCard> items, List<String> missing) {
    }

    // ------------------------------------------------------------------ admin

    public record IndexStatus(String alias, String index, long documents, String health) {
    }
}
