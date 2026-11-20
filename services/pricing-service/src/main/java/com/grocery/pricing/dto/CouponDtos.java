package com.grocery.pricing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.entity.CouponScope;
import com.grocery.pricing.entity.CouponStoreType;
import com.grocery.pricing.entity.DiscountType;
import com.grocery.pricing.entity.OrderType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Coupon API bodies (docs/api/phase-12-post-mvp-features.md §2). Money is a JSON number with 2 decimals (INR);
 * times are ISO-8601 UTC.
 */
public final class CouponDtos {

    /** The coupon reasons of the contract (evaluate {@code reason}, redeem 409 {@code reason}). */
    public static final String COUPON_NOT_FOUND = "COUPON_NOT_FOUND";
    public static final String COUPON_PAUSED = "COUPON_PAUSED";
    public static final String COUPON_NOT_STARTED = "COUPON_NOT_STARTED";
    public static final String COUPON_EXPIRED = "COUPON_EXPIRED";
    public static final String COUPON_WRONG_STORE = "COUPON_WRONG_STORE";
    public static final String COUPON_MIN_ORDER = "COUPON_MIN_ORDER";
    public static final String COUPON_USAGE_LIMIT = "COUPON_USAGE_LIMIT";
    public static final String COUPON_CUSTOMER_LIMIT = "COUPON_CUSTOMER_LIMIT";

    private CouponDtos() {
    }

    /**
     * POST (create) and PUT (replace) of a coupon. {@code code} is stored upper-case. Admins choose {@code scope}
     * (default PLATFORM; STORE needs {@code storeId}; its {@code storeType} GROCERY / RESTAURANT is inferred when left
     * out: a vendor known from the catalog projection is GROCERY, anything else RESTAURANT); under
     * {@code /api/vendor/coupons} and {@code /api/restaurant/coupons} the scope is forced to STORE with the caller's
     * store ({@code scope}, {@code storeId}, {@code storeType} may be left out; anything else → 403
     * COUPON_SCOPE_NOT_ALLOWED). Fields that do not apply to the discount type are ignored. On PUT, fields with a
     * default ({@code scope}, {@code storeType}, {@code storeId}, {@code validFrom}, {@code minOrderSubtotal},
     * {@code perCustomerLimit}, {@code active}, {@code public}) keep their current value when left out.
     */
    public record CouponRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9-]{4,20}$", message = "must be 4-20 letters, digits or '-'")
            String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            CouponScope scope,
            CouponStoreType storeType,
            UUID storeId,
            @NotNull DiscountType discountType,
            @DecimalMin("0.01") @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal value,
            @DecimalMin("0.01") @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal maxDiscount,
            @DecimalMin("0.00") @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal minOrderSubtotal,
            Instant validFrom,
            @NotNull Instant validUntil,
            @Min(1) @Max(10_000_000) Integer totalUsageLimit,
            @Min(1) @Max(1000) Integer perCustomerLimit,
            Boolean active,
            @JsonProperty("public") Boolean publicOffer) {
    }

    /**
     * A coupon as admins and store owners see it.
     *
     * @param storeName     a STORE coupon's store name when known (vendors, from the catalog projection); else null
     * @param fundedBy      PLATFORM (platform coupons) or STORE (store coupons)
     * @param status        ACTIVE, PAUSED, SCHEDULED (not started) or EXPIRED, at the time of the response
     * @param usedCount     RESERVED + CONFIRMED redemptions (what the usage limits count)
     * @param discountGiven Σ (discount + deliveryFeeDiscount) of CONFIRMED redemptions
     */
    public record CouponView(UUID id, String code, String name, String description, String scope, String storeType,
                             UUID storeId, String storeName, String fundedBy, String discountType, BigDecimal value,
                             BigDecimal maxDiscount, BigDecimal minOrderSubtotal, Instant validFrom,
                             Instant validUntil, Integer totalUsageLimit, int perCustomerLimit, boolean active,
                             @JsonProperty("public") boolean publicOffer, String status, long usedCount,
                             BigDecimal discountGiven, UUID createdBy, Instant createdAt, UUID updatedBy,
                             Instant updatedAt) {
    }

    /** GET /api/coupons?storeId= (customers): an offer usable at that store. */
    public record CouponOffer(String code, String name, String description, String discountType, BigDecimal value,
                              BigDecimal maxDiscount, BigDecimal minOrderSubtotal, Instant validUntil) {
    }

    /**
     * One redemption in the usage view. {@code orderNumber} is learnt from the order events (null until one came);
     * {@code createdAt} = when it was reserved (= {@code reservedAt}).
     */
    public record RedemptionView(UUID id, UUID orderId, String orderNumber, UUID customerId, UUID storeId,
                                 String orderType, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal discount,
                                 BigDecimal deliveryFeeDiscount, String status, Instant createdAt, Instant reservedAt,
                                 Instant confirmedAt, Instant releasedAt, String releaseReason) {
    }

    /**
     * GET …/coupons/{id}/usage: the counts per status, the amounts given (CONFIRMED) and one page of the
     * redemptions (an array), newest first ({@code ?status=&page=&size=}), with the page's {@code page},
     * {@code size}, {@code totalElements} and {@code totalPages}.
     */
    public record CouponUsage(UUID couponId, String code, long usedCount, long reservedCount, long confirmedCount,
                              long releasedCount, BigDecimal discountGiven, BigDecimal deliveryFeeDiscountGiven,
                              List<RedemptionView> redemptions, int page, int size, long totalElements,
                              int totalPages) {

        public static CouponUsage of(UUID couponId, String code, long usedCount, long reservedCount,
                                     long confirmedCount, long releasedCount, BigDecimal discountGiven,
                                     BigDecimal deliveryFeeDiscountGiven, PageResponse<RedemptionView> page) {
            return new CouponUsage(couponId, code, usedCount, reservedCount, confirmedCount, releasedCount,
                    discountGiven, deliveryFeeDiscountGiven, page.content(), page.page(), page.size(),
                    page.totalElements(), page.totalPages());
        }
    }

    /**
     * POST /internal/coupons/evaluate (cart-service). {@code customerId} defaults to the caller (the JWT subject) and
     * must be the caller when given; {@code deliveryFee} defaults to 0.
     */
    public record EvaluateRequest(
            @NotBlank @Size(max = 40) String code,
            UUID customerId,
            @NotNull UUID storeId,
            @NotNull OrderType orderType,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal subtotal,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal deliveryFee) {
    }

    /**
     * The evaluation. Invalid: {@code discount} and {@code deliveryFeeDiscount} 0.00, {@code reason} set
     * ({@code minOrderSubtotal} with COUPON_MIN_ORDER). {@code code} (normalised), {@code scope}, {@code storeId}
     * (STORE coupons) and {@code discountType} describe the coupon when it exists (additive, for the cart).
     */
    public record EvaluateResponse(boolean valid, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal minOrderSubtotal,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String code,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String scope,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) UUID storeId,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String discountType) {
    }

    /**
     * POST /internal/coupons/redeem (order-service, before it saves the order {@code orderId}). Same defaults as
     * {@link EvaluateRequest}.
     */
    public record RedeemRequest(
            @NotBlank @Size(max = 40) String code,
            @NotNull UUID orderId,
            UUID customerId,
            @NotNull UUID storeId,
            @NotNull OrderType orderType,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal subtotal,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal deliveryFee) {
    }

    /**
     * The reserved use. {@code couponName} and {@code fundedBy} (PLATFORM | STORE, from the coupon's scope) describe
     * the coupon as it is now: order-service keeps them on the order and publishes them, so the reports can name the
     * coupon and tell who paid for the discount without calling this service.
     */
    public record RedeemResponse(UUID redemptionId, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                                 String couponName, String fundedBy) {
    }
}
