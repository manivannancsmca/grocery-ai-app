package com.grocery.cart.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bodies of the endpoints the cart reads (contract §4, Phase 3 ProductCard, Phase 4 quote, Phase 2 addresses).
 * Only the fields the cart uses; unknown fields are ignored and wrapper types tolerate missing values.
 */
public final class DownstreamDtos {

    private DownstreamDtos() {
    }

    /** Body of the three batch endpoints (lookup, quote, availability): 1–100 ids. */
    public record ProductIds(List<UUID> productIds) {
    }

    // ---- catalog-query: POST /internal/catalog/products/lookup ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(UUID id, String name) {
    }

    /** Phase 3 ProductCard (only visible products are returned). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductCard(UUID id, String name, String unitLabel, String foodType, String primaryImageUrl,
                              Ref vendor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductLookup(List<ProductCard> items, List<UUID> missing) {

        public Map<UUID, ProductCard> byId() {
            return index(items, ProductCard::id);
        }
    }

    // ---- pricing: POST /internal/prices/quote ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuoteItem(UUID productId, UUID vendorId, BigDecimal mrp, BigDecimal sellingPrice, String currency,
                            Long priceVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceQuote(List<QuoteItem> items, List<UUID> missing) {

        public Map<UUID, QuoteItem> byId() {
            return index(items, QuoteItem::productId);
        }
    }

    // ---- pricing: POST /internal/coupons/evaluate (Phase 12 §2) ----

    /** {@code customerId} = the caller; {@code orderType} GROCERY | RESTAURANT. */
    public record CouponEvaluationRequest(String code, UUID customerId, UUID storeId, String orderType,
                                          BigDecimal subtotal, BigDecimal deliveryFee) {
    }

    /**
     * {@code { valid, discount, deliveryFeeDiscount, reason?, minOrderSubtotal? }} plus, when the coupon exists, its
     * normalised {@code code}, {@code scope}, {@code storeId} (STORE coupons) and {@code discountType}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CouponEvaluation(Boolean valid, BigDecimal discount, BigDecimal deliveryFeeDiscount, String reason,
                                   BigDecimal minOrderSubtotal, String code, String scope, UUID storeId,
                                   String discountType) {

        public boolean isValid() {
            return Boolean.TRUE.equals(valid);
        }
    }

    // ---- pricing: GET /internal/delivery-fees (admin platform settings, area delivery-fees) ----

    /** The delivery fees in force, INR and km; a missing field keeps this service's configured value. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryFees(BigDecimal groceryBaseFee, BigDecimal groceryIncludedKm, BigDecimal groceryPerExtraKm,
                               BigDecimal groceryFreeAbove, BigDecimal groceryMinimumOrder,
                               BigDecimal restaurantBaseFee, BigDecimal restaurantIncludedKm,
                               BigDecimal restaurantPerExtraKm) {
    }

    // ---- inventory: POST /internal/inventory/availability ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AvailabilityItem(UUID productId, UUID vendorId, Integer available, Boolean inStock) {

        public int availableQuantity() {
            return available == null ? 0 : Math.max(0, available);
        }

        /** In stock = the inventory says so and at least one unit is available. */
        public boolean orderable() {
            return Boolean.TRUE.equals(inStock) && availableQuantity() > 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Availability(List<AvailabilityItem> items, List<UUID> missing) {

        public Map<UUID, AvailabilityItem> byId() {
            return index(items, AvailabilityItem::productId);
        }
    }

    // ---- vendor: GET /internal/vendors/{vendorId}/store ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VendorStore(UUID vendorId, String name, String status, Boolean active, String city,
                              Double latitude, Double longitude) {

        public boolean isActive() {
            return Boolean.TRUE.equals(active);
        }
    }

    // ---- restaurant: POST /internal/restaurants/{restaurantId}/menu-quote ----

    public record MenuItemIds(List<UUID> itemIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MenuQuoteItem(UUID itemId, String name, BigDecimal price, String foodType, Boolean available,
                                String imageUrl) {

        /** On the menu, available and priced. */
        public boolean orderable() {
            return Boolean.TRUE.equals(available) && price != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MenuQuote(UUID restaurantId, String name, String status, Boolean acceptingOrders, Boolean openNow,
                            String city, Double latitude, Double longitude, List<MenuQuoteItem> items,
                            List<UUID> missing) {

        public static final String APPROVED = "APPROVED";

        public boolean approved() {
            return APPROVED.equals(status);
        }

        public boolean isOpenNow() {
            return Boolean.TRUE.equals(openNow);
        }

        public boolean isAcceptingOrders() {
            return Boolean.TRUE.equals(acceptingOrders);
        }

        public Map<UUID, MenuQuoteItem> byId() {
            return index(items, MenuQuoteItem::itemId);
        }
    }

    // ---- customer: GET /api/customers/me/addresses[/{id}] ----

    /** Phase 2 Address. {@code latitude}/{@code longitude} may be null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerAddress(UUID id, String label, String recipientName, String phone, String line1,
                                  String line2, String landmark, String city, String state, String postalCode,
                                  Double latitude, Double longitude,
                                  @JsonProperty("isDefault") @JsonAlias("default") Boolean isDefault) {

        public boolean hasLocation() {
            return latitude != null && longitude != null;
        }
    }

    private static <T> Map<UUID, T> index(Collection<T> items, Function<T, UUID> id) {
        if (items == null) {
            return Map.of();
        }
        return items.stream()
                .filter(item -> item != null && id.apply(item) != null)
                .collect(Collectors.toMap(id, Function.identity(), (first, second) -> first));
    }
}
