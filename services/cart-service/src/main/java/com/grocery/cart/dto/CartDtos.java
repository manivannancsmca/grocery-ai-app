package com.grocery.cart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.grocery.cart.model.ItemType;
import com.grocery.cart.model.StoreType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cart API bodies (docs/api/phase-5-cart-and-checkout-preview.md §2, §3). Money is a JSON number with 2 decimals
 * (INR); nulls are written out (e.g. {@code "store": null} for an empty cart).
 */
public final class CartDtos {

    public static final String CURRENCY = "INR";

    private CartDtos() {
    }

    /**
     * POST /api/cart/items: exactly one kind of item. PRODUCT needs {@code productId}; MENU_ITEM needs
     * {@code restaurantId} and {@code menuItemId}. A quantity above the line limit is 422 QUANTITY_LIMIT (not 400).
     */
    public record AddItemRequest(@NotNull ItemType type, UUID productId, UUID restaurantId, UUID menuItemId,
                                 @NotNull @Min(1) Integer quantity, Boolean replaceCart) {

        public boolean replace() {
            return Boolean.TRUE.equals(replaceCart);
        }
    }

    /** PUT /api/cart/items/{itemId}: 0 removes the line; above the line limit → 422 QUANTITY_LIMIT. */
    public record QuantityRequest(@NotNull @Min(0) Integer quantity) {
    }

    /**
     * The cart's store. {@code city}, {@code latitude} and {@code longitude} (Phase 9, additive) come from the vendor
     * store / restaurant menu quote the cart already reads; null when unknown (not set, or the owner could not be
     * reached).
     */
    public record StoreView(UUID id, StoreType type, String name, String city, Double latitude, Double longitude) {
    }

    /**
     * @param unitPrice         live selling price; null when it cannot be priced now (unavailable item, pricing down)
     * @param mrp               grocery only
     * @param availableQuantity grocery only: live available stock (null when unknown)
     * @param issue             the line's issue code, if any
     */
    public record CartLine(UUID itemId, ItemType type, String name, String unitLabel, String imageUrl,
                           String foodType, int quantity, BigDecimal unitPrice, BigDecimal mrp,
                           BigDecimal lineTotal, BigDecimal priceWhenAdded, Integer availableQuantity,
                           String issue) {
    }

    /** {@code itemId} null for issues of the whole cart (store, address, delivery). */
    public record Issue(String code, UUID itemId, boolean blocking, String message) {
    }

    /**
     * Phase 12: {@code coupon} = the applied coupon checked against this cart (null when none). {@code CartView} has no
     * delivery fee, so a FREE_DELIVERY coupon shows {@code deliveryFeeDiscount: 0.00} here; the preview has the real
     * amount.
     */
    public record CartView(StoreView store, List<CartLine> lines, int itemCount, BigDecimal subtotal,
                           BigDecimal mrpTotal, BigDecimal savings, String currency, List<Issue> issues,
                           Instant updatedAt, CouponView coupon) {

        /** This view with the coupon and, when the coupon cannot be used, its blocking issue. */
        public CartView withCoupon(CouponView newCoupon, Issue couponIssue) {
            List<Issue> allIssues = issues;
            if (couponIssue != null) {
                allIssues = new java.util.ArrayList<>(issues);
                allIssues.add(couponIssue);
                allIssues = List.copyOf(allIssues);
            }
            return new CartView(store, lines, itemCount, subtotal, mrpTotal, savings, currency, allIssues, updatedAt,
                    newCoupon);
        }
    }

    /** PUT /api/cart/coupon (Phase 12 §2): the code, any case (stored upper-case). */
    public record CouponRequest(@NotBlank @Size(max = 40) String code) {
    }

    /**
     * The cart's coupon (Phase 12 §2). Valid: {@code discount} off the subtotal and {@code deliveryFeeDiscount} off the
     * delivery fee. Not valid (any more): both 0.00, {@code reason} (pricing-service's reason, {@code COUPON_UNAVAILABLE}
     * when it could not be checked, {@code EMPTY_CART}), {@code message}, {@code minOrderSubtotal} with
     * COUPON_MIN_ORDER — and the blocking issue COUPON_NOT_APPLICABLE.
     */
    public record CouponView(String code, boolean valid, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String message,
                             @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal minOrderSubtotal) {
    }

    public record AddressView(UUID id, String label, String recipientName, String phone, String line1, String line2,
                              String landmark, String city, String state, String postalCode, Double latitude,
                              Double longitude) {
    }

    /** {@code freeAbove} / {@code minimumOrder} null when the store type has none. */
    public record DeliveryView(BigDecimal distanceKm, BigDecimal fee, BigDecimal freeAbove,
                               BigDecimal maxDistanceKm, BigDecimal minimumOrder) {
    }

    /**
     * GET /api/cart/preview: everything in {@link CartView} plus address, delivery and totals. Phase 12: {@code coupon}
     * (null when none), {@code discount} and {@code deliveryFeeDiscount} (0.00 without a usable coupon), and
     * {@code total = subtotal + delivery fee − discount − deliveryFeeDiscount}.
     */
    public record CheckoutPreview(StoreView store, List<CartLine> lines, int itemCount, BigDecimal subtotal,
                                  BigDecimal mrpTotal, BigDecimal savings, String currency, List<Issue> issues,
                                  Instant updatedAt, AddressView address, DeliveryView delivery, CouponView coupon,
                                  BigDecimal discount, BigDecimal deliveryFeeDiscount, BigDecimal total,
                                  boolean canCheckout, Instant pricedAt) {
    }
}
