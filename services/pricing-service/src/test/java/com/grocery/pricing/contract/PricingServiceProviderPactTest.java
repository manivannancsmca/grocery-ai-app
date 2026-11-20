package com.grocery.pricing.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.AbstractIntegrationTest;
import com.grocery.pricing.dto.CouponDtos;
import com.grocery.pricing.dto.CouponDtos.EvaluateRequest;
import com.grocery.pricing.dto.CouponDtos.EvaluateResponse;
import com.grocery.pricing.dto.CouponDtos.RedeemRequest;
import com.grocery.pricing.dto.CouponDtos.RedeemResponse;
import com.grocery.pricing.dto.PriceDtos.QuoteItem;
import com.grocery.pricing.dto.PriceDtos.QuoteResponse;
import com.grocery.pricing.entity.OrderType;
import com.grocery.pricing.service.CouponException;
import com.grocery.pricing.service.CouponRedemptionService;
import com.grocery.pricing.service.PriceQuoteService;
import com.grocery.pricing.settings.PlatformSettingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

/**
 * pricing-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is pricing-service —
 * cart-service's {@code POST /internal/prices/quote}, {@code POST /internal/coupons/evaluate} and {@code GET
 * /internal/delivery-fees} (admin platform settings, the real service and database), order-service's
 * {@code POST /internal/coupons/redeem} — against this service's Spring context through MockMvc. The real security
 * chain, request validation, controllers, Jackson and grocery-common's ProblemDetail handler run; the provider states
 * stub {@link PriceQuoteService} (its Redis cache and MySQL fallback are not part of the contract) and
 * {@link CouponRedemptionService}, whose refusals are thrown with the production {@link CouponException} factories
 * (so the 409 bodies are the real ones).
 *
 * <p>Auth: the pacts carry an opaque example bearer token. The {@link JwtDecoder} is a Mockito bean that turns any
 * bearer token into a CUSTOMER JWT whose subject is the provider state's {@code customerId}; the coupon stubs answer
 * only for that caller and for the request the consumer described, so the contract also checks the request binding.
 * No real signed token is needed; token validation itself is covered by the security tests.
 */
@Provider("pricing-service")
@PactFolder("../../contracts/pacts")
class PricingServiceProviderPactTest extends AbstractIntegrationTest {

    @MockitoBean
    PriceQuoteService quotes;
    @MockitoBean
    CouponRedemptionService redemptions;
    @MockitoBean
    JwtDecoder jwtDecoder;
    @Autowired
    PlatformSettingStore settings;

    /** The subject of every bearer token: the customer of the current provider state. */
    private UUID customerId = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new Spring7MockMvcTestTarget(mvc));
        given(jwtDecoder.decode(anyString())).willAnswer(call -> customerJwt(call.getArgument(0), customerId));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpring7Provider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ------------------------------------------------------------------------------------------------ states: quote

    @State("a product has a live price and another product has none")
    void pricedAndUnpricedProduct(Map<String, Object> params) {
        UUID productId = uuid(params, "productId");
        UUID missingProductId = uuid(params, "missingProductId");
        given(quotes.quote(List.of(productId, missingProductId))).willReturn(new QuoteResponse(
                List.of(new QuoteItem(productId, uuid(params, "vendorId"), money("100.00"), money("90.00"), "INR",
                        3L)),
                List.of(missingProductId)));
    }

    // ------------------------------------------------------------------------------------------------ states: coupons

    @State("coupon SAVE10 gives 10% off at the store")
    void usableCoupon(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        UUID storeId = uuid(params, "storeId");
        given(redemptions.evaluate(argThat((EvaluateRequest r) -> isEvaluation(r, "SAVE10", storeId)), caller()))
                .willReturn(new EvaluateResponse(true, money("36.00"), money("0.00"), null, null, "SAVE10", "PLATFORM",
                        null, "PERCENT"));
    }

    /**
     * Shared by both consumers: evaluate (cart-service) answers {@code valid: false}; redeem (order-service, whose
     * state also names the {@code orderId}) refuses with 409 COUPON_NOT_APPLICABLE.
     */
    @State("coupon BIG100 needs a minimum order of 499.00")
    void couponBelowMinimumOrder(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        UUID storeId = uuid(params, "storeId");
        given(redemptions.evaluate(argThat((EvaluateRequest r) -> isEvaluation(r, "BIG100", storeId)), caller()))
                .willReturn(new EvaluateResponse(false, money("0.00"), money("0.00"), CouponDtos.COUPON_MIN_ORDER,
                        money("499.00"), "BIG100", "PLATFORM", null, "FLAT"));
        if (params.containsKey("orderId")) {
            given(redemptions.redeem(argThat((RedeemRequest r) -> isRedemption(r, "BIG100", params)), caller()))
                    .willThrow(CouponException.notApplicable(CouponDtos.COUPON_MIN_ORDER, "BIG100",
                            money("499.00")));
        }
    }

    @State("coupon SAVE10 can be redeemed for the order")
    void redeemableCoupon(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(redemptions.redeem(argThat((RedeemRequest r) -> isRedemption(r, "SAVE10", params)), caller()))
                .willReturn(new RedeemResponse(UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6e1"),
                        money("36.00"), money("0.00"), "Save 10%", "PLATFORM"));
    }

    @State("the order already redeemed another coupon")
    void orderAlreadyRedeemed(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(redemptions.redeem(argThat((RedeemRequest r) -> isRedemption(r, "SAVE10", params)), caller()))
                .willThrow(CouponException.redemptionConflict(
                        "Order " + params.get("orderId") + " already redeemed coupon WELCOME50"));
    }

    // ------------------------------------------------------------------------------------------------ states: settings

    /** No admin override: the delivery fees of grocery.pricing.delivery-fees.*. */
    @State("the delivery fees are the configured ones")
    void configuredDeliveryFees() {
        JdbcClient.create(dataSource).sql("DELETE FROM platform_setting WHERE setting_key LIKE 'delivery-fees.%'")
                .update();
        settings.invalidate();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private boolean isEvaluation(EvaluateRequest request, String code, UUID storeId) {
        return request != null && code.equals(request.code()) && customerId.equals(request.customerId())
                && storeId.equals(request.storeId()) && request.orderType() == OrderType.GROCERY
                && request.subtotal() != null && request.deliveryFee() != null;
    }

    private boolean isRedemption(RedeemRequest request, String code, Map<String, Object> params) {
        return request != null && code.equals(request.code()) && customerId.equals(request.customerId())
                && uuid(params, "orderId").equals(request.orderId())
                && uuid(params, "storeId").equals(request.storeId()) && request.orderType() == OrderType.GROCERY
                && request.subtotal() != null && request.deliveryFee() != null;
    }

    /** The caller resolved from the bearer token: the state's customer. */
    private CurrentUser caller() {
        return argThat(user -> user != null && customerId.equals(user.userId()));
    }

    private static Jwt customerJwt(String token, UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("email", "contract-test@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static UUID uuid(Map<String, Object> params, String name) {
        return UUID.fromString(String.valueOf(params.get(name)));
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
