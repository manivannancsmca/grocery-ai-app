package com.grocery.order.client;

import com.grocery.order.config.OrderProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * pricing-service: redeems the cart's coupon for a new order ({@code POST /internal/coupons/redeem},
 * docs/api/phase-12-post-mvp-features.md §2), with the customer's own token (zero trust; the gateway never routes
 * {@code /internal/**}). The redemption is idempotent per {@code orderId} and stays RESERVED until pricing-service sees
 * {@code OrderCreated} (CONFIRMED) — or releases it after 30 minutes when the order was never saved.
 *
 * <p>Answers: 200 {@code { redemptionId, discount, deliveryFeeDiscount, couponName, fundedBy }} → {@link Redeemed};
 * 409 (also 422) with a {@code reason} ({@code COUPON_EXPIRED}, {@code COUPON_USAGE_LIMIT}, …, a {@code COUPON_*}
 * {@code errorCode} when the property is missing) and maybe {@code minOrderSubtotal} → {@link Refused}; anything else
 * (unreachable, timeout, 5xx, another 4xx, open breaker) → {@link Outcome.Failed}.
 */
@Component
public class PricingClient {

    static final String REDEEM_PATH = "/internal/coupons/redeem";
    static final String COUPON_PREFIX = "COUPON_";
    static final int CONFLICT = 409;
    static final int UNPROCESSABLE = 422;

    /** The redeem request ({@code subtotal} = items, {@code deliveryFee} = before any discount). */
    public record RedeemRequest(String code, UUID orderId, UUID customerId, UUID storeId, String orderType,
                                BigDecimal subtotal, BigDecimal deliveryFee) {
    }

    /** What pricing-service answered. */
    public sealed interface Redemption permits Redeemed, Refused {
    }

    /**
     * Reserved for the order: the discount off the items and off the delivery fee (null read as 0), the coupon's name
     * and who funds it ({@code fundedBy} PLATFORM or STORE). The last two are only kept for the order's events (the
     * reports); a pricing-service that does not send them yet gives null.
     */
    public record Redeemed(UUID redemptionId, BigDecimal discount, BigDecimal deliveryFeeDiscount, String couponName,
                           String fundedBy) implements Redemption {
    }

    /** The coupon does not apply: {@code reason} (never null), {@code minOrderSubtotal} for COUPON_MIN_ORDER. */
    public record Refused(String reason, BigDecimal minOrderSubtotal) implements Redemption {
    }

    private final Downstream downstream;
    private final RestClient client;

    public PricingClient(Downstream downstream, OrderProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getPricing());
    }

    public Outcome<Redemption> redeem(CallContext context, RedeemRequest request) {
        return downstream.call(Dependency.PRICING, null, () -> {
            try {
                return client.post()
                        .uri(REDEEM_PATH)
                        .headers(context::applyTo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Redeemed.class);
            } catch (HttpClientErrorException e) {
                Refused refused = refusal(e);
                if (refused == null) {
                    throw e;
                }
                return refused;
            }
        });
    }

    /** A 409 / 422 that names why the coupon does not apply; null for any other client error. */
    private Refused refusal(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        if (status != CONFLICT && status != UNPROCESSABLE) {
            return null;
        }
        JsonNode problem = downstream.problem(e);
        if (problem == null) {
            return null;
        }
        String reason = Downstream.text(problem, "reason");
        if (reason == null || reason.isBlank()) {
            String errorCode = Downstream.text(problem, "errorCode");
            if (errorCode == null || !errorCode.startsWith(COUPON_PREFIX)) {
                return null;
            }
            reason = errorCode;
        }
        JsonNode minimum = problem.get("minOrderSubtotal");
        BigDecimal minOrderSubtotal = minimum != null && minimum.isNumber() ? minimum.decimalValue() : null;
        return new Refused(reason.strip(), minOrderSubtotal);
    }
}
