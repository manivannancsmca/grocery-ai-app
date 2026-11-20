package com.grocery.catalogquery.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * {@code products} index document (id = productId). Mirrors {@code elasticsearch/products.json}; the mapping is
 * strict, so a field added here must be added there too.
 *
 * <p>The document has four independently versioned parts, each written only by its own event and only when
 * that event's version is newer (see {@code SearchDocumentWriter.merge}):
 * <ul>
 *   <li>the catalog snapshot ({@link #CATALOG_FIELDS}, version {@code productVersion}) — catalog.events;</li>
 *   <li>the price ({@link #PRICE_FIELDS}, version {@code priceVersion}) — pricing.events;</li>
 *   <li>the stock flag ({@link #STOCK_FIELDS}, version {@code stockVersion}) — inventory.events;</li>
 *   <li>the rating ({@link #RATING_FIELDS}, version {@code ratingVersion} = summaryVersion) — review.events
 *       (Phase 12).</li>
 * </ul>
 *
 * @param categoryIds     the leaf category and all its ancestors (a filter on a parent finds the whole subtree)
 * @param categoryFacet   "id|name" of the leaf category (terms aggregation → facet)
 * @param brandFacet      "id|name" of the brand
 * @param visible         PUBLISHED, vendor active and every category on the path active (catalog part); false on a
 *                        placeholder created by a price, stock or rating event before the product arrived
 * @param discountPercent whole percent off the MRP, 0–99 (computed when the price is indexed)
 * @param inStock         at least one unit can be sold; absent = no stock known = not in stock
 * @param rating          average of the published ratings (2 decimals); absent while there is none
 * @param ratingCount     number of published ratings
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDocument(
        String productId,
        String vendorId,
        String vendorName,
        String name,
        String description,
        String brandId,
        String brandName,
        String brandFacet,
        String categoryId,
        List<String> categoryIds,
        List<String> categoryNames,
        String categoryFacet,
        List<IdName> categoryPath,
        BigDecimal unitValue,
        String unitOfMeasure,
        String unitLabel,
        String sku,
        String barcode,
        String foodType,
        List<String> tags,
        List<Attribute> attributes,
        List<String> imageUrls,
        String primaryImageUrl,
        String status,
        Boolean visible,
        Instant publishedAt,
        Instant updatedAt,
        Long productVersion,
        BigDecimal mrp,
        BigDecimal sellingPrice,
        String currency,
        Integer discountPercent,
        Long priceVersion,
        Boolean inStock,
        Long stockVersion,
        BigDecimal rating,
        Integer ratingCount,
        Long ratingVersion) {

    public static final String PRODUCT_VERSION = "productVersion";
    public static final String PRICE_VERSION = "priceVersion";
    public static final String STOCK_VERSION = "stockVersion";
    public static final String RATING_VERSION = "ratingVersion";

    /** Fields owned by pricing.events. */
    public static final List<String> PRICE_FIELDS =
            List.of("mrp", "sellingPrice", "currency", "discountPercent", PRICE_VERSION);
    /** Fields owned by inventory.events. */
    public static final List<String> STOCK_FIELDS = List.of("inStock", STOCK_VERSION);
    /** Fields owned by review.events (RatingSummaryUpdated). */
    public static final List<String> RATING_FIELDS = List.of("rating", "ratingCount", RATING_VERSION);
    /** Everything else: owned by the catalog snapshot. */
    public static final List<String> CATALOG_FIELDS = Arrays.stream(ProductDocument.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !PRICE_FIELDS.contains(name) && !STOCK_FIELDS.contains(name)
                    && !RATING_FIELDS.contains(name))
            .toList();

    public record Attribute(String name, String value) {
    }

    /** The price part of the document, as written by a {@code PriceChanged} event. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PricePart(String productId, BigDecimal mrp, BigDecimal sellingPrice, String currency,
                            Integer discountPercent, Long priceVersion) {
    }

    /** The stock part of the document, as written by a {@code ProductAvailabilityChanged} event. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockPart(String productId, Boolean inStock, Long stockVersion) {
    }

    /**
     * The rating part of the document, as written by a {@code RatingSummaryUpdated} event; {@code rating} is null
     * (absent) while the product has no published rating, so it sorts last and never matches a rating filter.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RatingPart(String productId, BigDecimal rating, Integer ratingCount, Long ratingVersion) {
    }
}
