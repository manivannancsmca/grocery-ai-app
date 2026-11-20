package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluation;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluationRequest;
import com.grocery.cart.client.DownstreamDtos.DeliveryFees;
import com.grocery.cart.client.DownstreamDtos.PriceQuote;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.client.PricingClient;
import com.grocery.cart.config.CartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static com.grocery.cart.contract.ConsumerPacts.BEARER;
import static com.grocery.cart.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.CALL;
import static com.grocery.cart.contract.ConsumerPacts.CONSUMER;
import static com.grocery.cart.contract.ConsumerPacts.CUSTOMER_ID;
import static com.grocery.cart.contract.ConsumerPacts.JSON;
import static com.grocery.cart.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.MISSING_PRODUCT_ID;
import static com.grocery.cart.contract.ConsumerPacts.PRODUCT_ID;
import static com.grocery.cart.contract.ConsumerPacts.VENDOR_ID;
import static com.grocery.cart.contract.ConsumerPacts.body;
import static com.grocery.cart.contract.ConsumerPacts.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart-service → pricing-service ({@link PricingClient}): {@code POST /internal/prices/quote}, (Phase 12)
 * {@code POST /internal/coupons/evaluate} and {@code GET /internal/delivery-fees} (admin platform settings).
 * Writes {@code contracts/pacts/cart-service-pricing-service.json}. A coupon
 * that cannot be used is an answer ({@code valid: false} with {@code reason}, and {@code minOrderSubtotal} for
 * COUPON_MIN_ORDER), never an error; errors have no special meaning for the cart (→ Failed).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "pricing-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class PricingServiceConsumerPactTest {

    // ------------------------------------------------------------------------------------------------ pacts

    @Pact(consumer = CONSUMER)
    V4Pact pricedAndMissingProducts(PactDslWithProvider builder) {
        return builder
                .given("a product has a live price and another product has none",
                        Map.of("productId", PRODUCT_ID.toString(), "vendorId", VENDOR_ID.toString(),
                                "missingProductId", MISSING_PRODUCT_ID.toString()))
                .uponReceiving("a price quote of a priced and an unpriced product")
                .method("POST")
                .path("/internal/prices/quote")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(ids("productIds", PRODUCT_ID, MISSING_PRODUCT_ID))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> {
                    o.eachLike("items", item -> item
                            .uuid("productId", PRODUCT_ID)
                            .uuid("vendorId", VENDOR_ID)
                            .numberType("mrp", money("100.00"))
                            .numberType("sellingPrice", money("90.00"))
                            .stringType("currency", "INR")
                            .integerType("priceVersion", 3));
                    o.array("missing", missing -> missing.uuid(MISSING_PRODUCT_ID.toString()));
                }))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact usableCoupon(PactDslWithProvider builder) {
        return builder
                .given("coupon SAVE10 gives 10% off at the store", state("SAVE10"))
                .uponReceiving("an evaluation of a usable coupon")
                .method("POST")
                .path("/internal/coupons/evaluate")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(evaluateRequest("SAVE10"))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> o
                        .booleanValue("valid", true)
                        .numberType("discount", money("36.00"))
                        .numberType("deliveryFeeDiscount", money("0.00"))
                        .stringValue("code", "SAVE10")
                        .stringType("scope", "PLATFORM")
                        .stringType("discountType", "PERCENT")))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact couponBelowMinimumOrder(PactDslWithProvider builder) {
        return builder
                .given("coupon BIG100 needs a minimum order of 499.00", state("BIG100"))
                .uponReceiving("an evaluation of a coupon the cart is too small for")
                .method("POST")
                .path("/internal/coupons/evaluate")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(evaluateRequest("BIG100"))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> o
                        .booleanValue("valid", false)
                        .numberType("discount", money("0.00"))
                        .numberType("deliveryFeeDiscount", money("0.00"))
                        .stringValue("reason", "COUPON_MIN_ORDER")
                        .numberType("minOrderSubtotal", money("499.00"))))
                .toPact(V4Pact.class);
    }

    /** The delivery fees in force (admin platform settings, area delivery-fees): every field a number. */
    @Pact(consumer = CONSUMER)
    V4Pact deliveryFees(PactDslWithProvider builder) {
        return builder
                .given("the delivery fees are the configured ones")
                .uponReceiving("a request for the delivery fees in force")
                .method("GET")
                .path("/internal/delivery-fees")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> o
                        .numberType("groceryBaseFee", money("25.00"))
                        .numberType("groceryIncludedKm", new BigDecimal("3"))
                        .numberType("groceryPerExtraKm", money("8.00"))
                        .numberType("groceryFreeAbove", money("499.00"))
                        .numberType("groceryMinimumOrder", money("99.00"))
                        .numberType("restaurantBaseFee", money("30.00"))
                        .numberType("restaurantIncludedKm", new BigDecimal("3"))
                        .numberType("restaurantPerExtraKm", money("10.00"))))
                .toPact(V4Pact.class);
    }

    private static Map<String, Object> state(String code) {
        return Map.of("code", code, "customerId", CUSTOMER_ID.toString(), "storeId", VENDOR_ID.toString());
    }

    /** What the cart sends: the coupon, the caller, the store, its type and the amounts the coupon applies to. */
    private static DslPart evaluateRequest(String code) {
        return newJsonBody(o -> o
                .stringValue("code", code)
                .uuid("customerId", CUSTOMER_ID)
                .uuid("storeId", VENDOR_ID)
                .stringMatcher("orderType", "GROCERY|RESTAURANT", "GROCERY")
                .numberType("subtotal", money("360.00"))
                .numberType("deliveryFee", money("25.00"))).build();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    // ------------------------------------------------------------------------------------------------ tests

    private static PricingClient client(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        return new PricingClient(ConsumerPacts.downstream(properties), properties);
    }

    private static CouponEvaluationRequest evaluation(String code) {
        return new CouponEvaluationRequest(code, CUSTOMER_ID, VENDOR_ID, "GROCERY", money("360.00"), money("25.00"));
    }

    @Test
    @PactTestFor(pactMethod = "pricedAndMissingProducts")
    void readsTheLivePrices(MockServer server) {
        Outcome<PriceQuote> outcome = client(server).quote(CALL, List.of(PRODUCT_ID, MISSING_PRODUCT_ID));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        PriceQuote quote = ((Outcome.Ok<PriceQuote>) outcome).value();
        assertThat(quote.byId()).containsOnlyKeys(PRODUCT_ID);
        assertThat(quote.byId().get(PRODUCT_ID)).satisfies(item -> {
            assertThat(item.vendorId()).isEqualTo(VENDOR_ID);
            assertThat(item.mrp()).isEqualByComparingTo("100.00");
            assertThat(item.sellingPrice()).isEqualByComparingTo("90.00");
            assertThat(item.currency()).isEqualTo("INR");
            assertThat(item.priceVersion()).isEqualTo(3L);
        });
        assertThat(quote.missing()).containsExactly(MISSING_PRODUCT_ID);
    }

    @Test
    @PactTestFor(pactMethod = "usableCoupon")
    void readsAUsableCoupon(MockServer server) {
        Outcome<CouponEvaluation> outcome = client(server).evaluate(CALL, evaluation("SAVE10"));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CouponEvaluation evaluation = ((Outcome.Ok<CouponEvaluation>) outcome).value();
        assertThat(evaluation.isValid()).isTrue();
        assertThat(evaluation.discount()).isEqualByComparingTo("36.00");
        assertThat(evaluation.deliveryFeeDiscount()).isEqualByComparingTo("0.00");
        assertThat(evaluation.code()).isEqualTo("SAVE10");
    }

    @Test
    @PactTestFor(pactMethod = "couponBelowMinimumOrder")
    void readsWhyACouponCannotBeUsed(MockServer server) {
        Outcome<CouponEvaluation> outcome = client(server).evaluate(CALL, evaluation("BIG100"));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CouponEvaluation evaluation = ((Outcome.Ok<CouponEvaluation>) outcome).value();
        assertThat(evaluation.isValid()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("COUPON_MIN_ORDER");
        assertThat(evaluation.minOrderSubtotal()).isEqualByComparingTo("499.00");
    }

    @Test
    @PactTestFor(pactMethod = "deliveryFees")
    void readsTheDeliveryFeesInForce(MockServer server) {
        Outcome<DeliveryFees> outcome = client(server).deliveryFees(CALL);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        DeliveryFees fees = ((Outcome.Ok<DeliveryFees>) outcome).value();
        assertThat(fees.groceryBaseFee()).isEqualByComparingTo("25.00");
        assertThat(fees.groceryIncludedKm()).isEqualByComparingTo("3");
        assertThat(fees.groceryPerExtraKm()).isEqualByComparingTo("8.00");
        assertThat(fees.groceryFreeAbove()).isEqualByComparingTo("499.00");
        assertThat(fees.groceryMinimumOrder()).isEqualByComparingTo("99.00");
        assertThat(fees.restaurantBaseFee()).isEqualByComparingTo("30.00");
        assertThat(fees.restaurantIncludedKm()).isEqualByComparingTo("3");
        assertThat(fees.restaurantPerExtraKm()).isEqualByComparingTo("10.00");
    }
}
