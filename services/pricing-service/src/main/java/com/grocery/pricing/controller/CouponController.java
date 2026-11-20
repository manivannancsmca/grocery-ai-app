package com.grocery.pricing.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.dto.CouponDtos.CouponOffer;
import com.grocery.pricing.dto.CouponDtos.EvaluateRequest;
import com.grocery.pricing.dto.CouponDtos.EvaluateResponse;
import com.grocery.pricing.dto.CouponDtos.RedeemRequest;
import com.grocery.pricing.dto.CouponDtos.RedeemResponse;
import com.grocery.pricing.service.CouponRedemptionService;
import com.grocery.pricing.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Coupons for customers (Phase 12 §2): the offers of a store ({@code /api/coupons}, role CUSTOMER) and the internal
 * {@code evaluate} (cart-service) and {@code redeem} (order-service) — {@code /internal/**} is never routed by the
 * gateway; the forwarded customer token is still validated here (zero trust) and is the customer.
 */
@RestController
public class CouponController {

    private final CouponService coupons;
    private final CouponRedemptionService redemptions;

    public CouponController(CouponService coupons, CouponRedemptionService redemptions) {
        this.coupons = coupons;
        this.redemptions = redemptions;
    }

    /** Active, public coupons the caller can use at the store ({@code storeType} optional: GROCERY | RESTAURANT). */
    @GetMapping("/api/coupons")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<CouponOffer> offers(CurrentUser user, @RequestParam(required = false) UUID storeId,
                                    @RequestParam(required = false) String storeType) {
        return coupons.offers(user.userId(), storeId, storeType);
    }

    @PostMapping("/internal/coupons/evaluate")
    public EvaluateResponse evaluate(CurrentUser user, @Valid @RequestBody EvaluateRequest request) {
        return redemptions.evaluate(request, user);
    }

    @PostMapping("/internal/coupons/redeem")
    public RedeemResponse redeem(CurrentUser user, @Valid @RequestBody RedeemRequest request) {
        return redemptions.redeem(request, user);
    }
}
