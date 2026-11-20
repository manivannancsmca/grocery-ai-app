package com.grocery.order.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.order.client.Outcome;
import com.grocery.order.client.PricingClient;
import com.grocery.order.client.PricingClient.RedeemRequest;
import com.grocery.order.client.PricingClient.Redeemed;
import com.grocery.order.client.PricingClient.Redemption;
import com.grocery.order.client.PricingClient.Refused;
import com.grocery.order.config.OrderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static com.grocery.order.contract.ConsumerPacts.BEARER;
import static com.grocery.order.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.order.contract.ConsumerPacts.CALL;
import static com.grocery.order.contract.ConsumerPacts.CONSUMER;
import static com.grocery.order.contract.ConsumerPacts.JSON;
import static com.grocery.order.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.order.contract.ConsumerPacts.PROBLEM;
import static com.grocery.order.contract.ConsumerPacts.PROBLEM_REGEX;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * order-service → pricing-service: {@code POST /internal/coupons/redeem} with the customer's own token
 * ({@link PricingClient}). Writes {@code contracts/pacts/order-service-pricing-service.json}; pricing-service verifies
 * it. Covers the three answers placement handles: 200 (reserved, with the coupon's {@code couponName} and
 * {@code fundedBy}), 409 COUPON_NOT_APPLICABLE with {@code reason} and
 * {@code minOrderSubtotal} (→ Refused), and 409 COUPON_REDEMPTION_CONFLICT without {@code reason} (→ Refused named
 * by the {@code COUPON_*} errorCode).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "pricing-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class PricingServiceConsumerPactTest {

    static final UUID CUSTOMER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    static final UUID VENDOR_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6b1");
    static final UUID ORDER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6d1");
    static final UUID REDEMPTION_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6e1");

    private static final String REDEEM = "/internal/coupons/redeem";

    // ------------------------------------------------------------------------------------------------ pacts

    @Pact(consumer = CONSUMER)
    V4Pact redeemed(PactDslWithProvider builder) {
        return builder
                .given("coupon SAVE10 can be redeemed for the order", state("SAVE10"))
                .uponReceiving("a redemption of a usable coupon")
                .method("POST")
                .path(REDEEM)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(redeemRequest("SAVE10"))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(newJsonBody(o -> o
                        .uuid("redemptionId", REDEMPTION_ID)
                        .numberType("discount", money("36.00"))
                        .numberType("deliveryFeeDiscount", money("0.00"))
                        // schemas 1.7.0: published in OrderCreated / OrderConfirmed for the reports
                        .stringType("couponName", "Save 10%")
                        .stringMatcher("fundedBy", "PLATFORM|STORE", "PLATFORM")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact belowMinimumOrder(PactDslWithProvider builder) {
        return builder
                .given("coupon BIG100 needs a minimum order of 499.00", state("BIG100"))
                .uponReceiving("a redemption of a coupon the order is too small for")
                .method("POST")
                .path(REDEEM)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(redeemRequest("BIG100"))
                .willRespondWith()
                .status(409)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(newJsonBody(o -> o
                        .stringValue("errorCode", "COUPON_NOT_APPLICABLE")
                        .stringValue("reason", "COUPON_MIN_ORDER")
                        .numberType("minOrderSubtotal", money("499.00"))).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact alreadyRedeemedWithAnotherCoupon(PactDslWithProvider builder) {
        return builder
                .given("the order already redeemed another coupon", state("SAVE10"))
                .uponReceiving("a redemption for an order that already redeemed another coupon")
                .method("POST")
                .path(REDEEM)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(redeemRequest("SAVE10"))
                .willRespondWith()
                .status(409)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(newJsonBody(o -> o.stringValue("errorCode", "COUPON_REDEMPTION_CONFLICT")).build())
                .toPact(V4Pact.class);
    }

    private static Map<String, Object> state(String code) {
        return Map.of("code", code, "orderId", ORDER_ID.toString(), "customerId", CUSTOMER_ID.toString(),
                "storeId", VENDOR_ID.toString());
    }

    /** What placement sends: the cart's coupon, the new order's id, the caller, the store and the amounts. */
    private static DslPart redeemRequest(String code) {
        return newJsonBody(o -> o
                .stringValue("code", code)
                .uuid("orderId", ORDER_ID)
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

    private static Outcome<Redemption> redeem(MockServer server, String code) {
        OrderProperties properties = new OrderProperties();
        properties.getServices().setPricing(server.getUrl());
        PricingClient client = new PricingClient(ConsumerPacts.downstream(properties), properties);
        return client.redeem(CALL, new RedeemRequest(code, ORDER_ID, CUSTOMER_ID, VENDOR_ID, "GROCERY",
                money("360.00"), money("25.00")));
    }

    @Test
    @PactTestFor(pactMethod = "redeemed")
    void readsTheRedemption(MockServer server) {
        Outcome<Redemption> outcome = redeem(server, "SAVE10");

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<Redemption>) outcome).value()).isInstanceOfSatisfying(Redeemed.class, redeemed -> {
            assertThat(redeemed.redemptionId()).isEqualTo(REDEMPTION_ID);
            assertThat(redeemed.discount()).isEqualByComparingTo("36.00");
            assertThat(redeemed.deliveryFeeDiscount()).isEqualByComparingTo("0.00");
            assertThat(redeemed.couponName()).isEqualTo("Save 10%");
            assertThat(redeemed.fundedBy()).isEqualTo("PLATFORM");
        });
    }

    @Test
    @PactTestFor(pactMethod = "belowMinimumOrder")
    void mapsANotApplicableCouponToARefusalWithItsReason(MockServer server) {
        Outcome<Redemption> outcome = redeem(server, "BIG100");

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<Redemption>) outcome).value()).isInstanceOfSatisfying(Refused.class, refused -> {
            assertThat(refused.reason()).isEqualTo("COUPON_MIN_ORDER");
            assertThat(refused.minOrderSubtotal()).isEqualByComparingTo("499.00");
        });
    }

    @Test
    @PactTestFor(pactMethod = "alreadyRedeemedWithAnotherCoupon")
    void mapsARedemptionConflictToARefusalNamedByItsErrorCode(MockServer server) {
        Outcome<Redemption> outcome = redeem(server, "SAVE10");

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<Redemption>) outcome).value())
                .isEqualTo(new Refused("COUPON_REDEMPTION_CONFLICT", null));
    }
}
