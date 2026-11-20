package com.grocery.order.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Our own view of cart-service's {@code CheckoutPreview} (docs/api/phase-5-cart-and-checkout-preview.md §3): only the
 * fields order-service stores or checks (tolerant reader: unknown fields are ignored, cart classes are never shared).
 * Phase 12 (phase-12-post-mvp-features.md §2): {@code coupon}, {@code discount} and {@code deliveryFeeDiscount}, with
 * the discounted {@code total = subtotal + delivery.fee - discount - deliveryFeeDiscount}; all absent (null) without
 * a coupon or from an older cart-service.
 */
public record CartPreview(
        Store store,
        List<Line> lines,
        Integer itemCount,
        BigDecimal subtotal,
        BigDecimal mrpTotal,
        BigDecimal savings,
        String currency,
        List<Issue> issues,
        Instant updatedAt,
        Address address,
        Delivery delivery,
        BigDecimal total,
        Boolean canCheckout,
        Coupon coupon,
        BigDecimal discount,
        BigDecimal deliveryFeeDiscount) {

    /**
     * The coupon applied to the cart: {@code valid} false (with {@code reason}) when it no longer applies — the cart
     * then also reports the blocking issue {@code COUPON_NOT_APPLICABLE}.
     */
    public record Coupon(String code, Boolean valid, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                         String reason, String message) {
    }

    /** A coupon code is applied to the cart (valid or not). */
    public boolean hasCoupon() {
        return coupon != null && coupon.code() != null && !coupon.code().isBlank();
    }

    /**
     * {@code type} GROCERY | RESTAURANT; {@code id} = vendorId or restaurantId. {@code city}, {@code latitude},
     * {@code longitude}: the store's location (Phase 9, additive), null when unknown or from an older cart-service.
     */
    public record Store(UUID id, String type, String name, String city, Double latitude, Double longitude) {
    }

    /** {@code type} PRODUCT | MENU_ITEM; {@code unitPrice} / {@code lineTotal} null when the line cannot be priced. */
    public record Line(UUID itemId, String type, String name, String unitLabel, String imageUrl, String foodType,
                       Integer quantity, BigDecimal unitPrice, BigDecimal mrp, BigDecimal lineTotal) {
    }

    /** Passed on unchanged in 409 CHECKOUT_BLOCKED ({@code itemId} null for issues of the whole cart). */
    public record Issue(String code, UUID itemId, boolean blocking, String message) {
    }

    public record Address(UUID id, String label, String recipientName, String phone, String line1, String line2,
                          String landmark, String city, String state, String postalCode, Double latitude,
                          Double longitude) {
    }

    public record Delivery(BigDecimal distanceKm, BigDecimal fee) {
    }

    public List<Issue> issuesOrEmpty() {
        return issues == null ? List.of() : issues;
    }

    /** Checkout is possible: the preview says so and no issue blocks it. */
    public boolean checkoutAllowed() {
        return Boolean.TRUE.equals(canCheckout) && issuesOrEmpty().stream().noneMatch(Issue::blocking);
    }
}
