package com.grocery.pricing.service;

import com.grocery.common.web.ApiException;
import com.grocery.pricing.dto.CouponDtos;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The coupon errors (contract §2). Unlike grocery-common's fixed exceptions they carry problem properties
 * ({@code reason}, {@code minOrderSubtotal}, {@code errors}), which grocery-common's GlobalExceptionHandler adds to the
 * ProblemDetail body.
 */
public class CouponException extends ApiException {

    public static final String COUPON_NOT_APPLICABLE = "COUPON_NOT_APPLICABLE";

    public CouponException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown coupon, or another store's (store owners never learn that it exists). */
    public static CouponException notFound() {
        return new CouponException(HttpStatus.NOT_FOUND, CouponDtos.COUPON_NOT_FOUND, "Coupon not found", Map.of());
    }

    public static CouponException codeTaken(String code) {
        return new CouponException(HttpStatus.CONFLICT, "COUPON_CODE_TAKEN",
                "The coupon code " + code + " is already taken", Map.of("code", code));
    }

    /** A store owner asked for a PLATFORM coupon, or for another store. */
    public static CouponException scopeNotAllowed() {
        return new CouponException(HttpStatus.FORBIDDEN, "COUPON_SCOPE_NOT_ALLOWED",
                "Store owners can only create coupons of their own store (scope STORE)", Map.of());
    }

    /** 400 VALIDATION_FAILED with per-field problems, like Bean Validation failures. */
    public static CouponException invalid(List<Map<String, String>> errors) {
        return new CouponException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.copyOf(errors)));
    }

    /** {@code customerId} of an internal call is not the caller (the forwarded customer token). */
    public static CouponException customerMismatch() {
        return new CouponException(HttpStatus.FORBIDDEN, "CUSTOMER_MISMATCH",
                "customerId must be the calling customer", Map.of());
    }

    /** redeem: 409 COUPON_NOT_APPLICABLE with {@code reason} (and {@code minOrderSubtotal} for COUPON_MIN_ORDER). */
    public static CouponException notApplicable(String reason, String code, BigDecimal minOrderSubtotal) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", reason);
        if (minOrderSubtotal != null) {
            properties.put("minOrderSubtotal", minOrderSubtotal);
        }
        return new CouponException(HttpStatus.CONFLICT, COUPON_NOT_APPLICABLE,
                CouponRules.message(reason, code, minOrderSubtotal), properties);
    }

    /** redeem: the order already has another redemption (another coupon or customer, or one already released). */
    public static CouponException redemptionConflict(String message) {
        return new CouponException(HttpStatus.CONFLICT, "COUPON_REDEMPTION_CONFLICT", message, Map.of());
    }
}
