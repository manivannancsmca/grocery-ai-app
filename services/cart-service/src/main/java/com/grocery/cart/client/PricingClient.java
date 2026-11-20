package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.CouponEvaluation;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluationRequest;
import com.grocery.cart.client.DownstreamDtos.DeliveryFees;
import com.grocery.cart.client.DownstreamDtos.PriceQuote;
import com.grocery.cart.client.DownstreamDtos.ProductIds;
import com.grocery.cart.config.CartProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * pricing-service: {@code POST /internal/prices/quote}, the authoritative live price (MRP + selling price),
 * (Phase 12) {@code POST /internal/coupons/evaluate} and {@code GET /internal/delivery-fees} (the admin platform
 * settings' delivery fees).
 */
@Component
public class PricingClient {

    private final Downstream downstream;
    private final RestClient client;

    public PricingClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getPricing());
    }

    public Outcome<PriceQuote> quote(CallContext context, Collection<UUID> productIds) {
        return downstream.call(Dependency.PRICING, null, () -> client.post()
                .uri("/internal/prices/quote")
                .headers(context::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProductIds(List.copyOf(productIds)))
                .retrieve()
                .body(PriceQuote.class));
    }

    /**
     * Phase 12 §2: {@code POST /internal/coupons/evaluate}. Same timeouts and circuit breaker ({@code pricing}) as the
     * quote; a coupon problem is an answer ({@code valid: false}), never an error.
     */
    public Outcome<CouponEvaluation> evaluate(CallContext context, CouponEvaluationRequest request) {
        return downstream.call(Dependency.PRICING, null, () -> client.post()
                .uri("/internal/coupons/evaluate")
                .headers(context::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CouponEvaluation.class));
    }

    /**
     * {@code GET /internal/delivery-fees}: the delivery fees in force. Its own circuit breaker
     * ({@code pricing-fees}), so a failing fee lookup never opens the quote's; the caller falls back to known fees.
     */
    public Outcome<DeliveryFees> deliveryFees(CallContext context) {
        return downstream.call(Dependency.PRICING_FEES, null, () -> client.get()
                .uri("/internal/delivery-fees")
                .headers(context::applyTo)
                .retrieve()
                .body(DeliveryFees.class));
    }
}
