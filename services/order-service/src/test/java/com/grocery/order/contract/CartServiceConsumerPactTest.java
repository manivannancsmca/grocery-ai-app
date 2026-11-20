package com.grocery.order.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.order.client.CartClient;
import com.grocery.order.client.CartPreview;
import com.grocery.order.client.Outcome;
import com.grocery.order.config.OrderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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
 * order-service → cart-service: {@code GET /api/cart/preview[?addressId=]} with the customer's own token
 * ({@link CartClient}). Writes {@code contracts/pacts/order-service-cart-service.json}; cart-service verifies it.
 * Covers what placement relies on: a checkout-ready preview (with and without a coupon, with and without
 * {@code addressId}), a preview with a blocking issue (→ 409 CHECKOUT_BLOCKED here), 404 ADDRESS_NOT_FOUND
 * (→ NotFound) and 503 DEPENDENCY_UNAVAILABLE naming the service cart could not reach (→ Failed with that name).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "cart-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class CartServiceConsumerPactTest {

    static final UUID CUSTOMER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    static final UUID ADDRESS_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6a1");
    static final UUID UNKNOWN_ADDRESS_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6a9");
    static final UUID VENDOR_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6b1");
    static final UUID PRODUCT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6c1");
    static final String UPDATED_AT = "2026-11-14T09:30:00.123Z";
    /** ISO-8601 instant as Jackson writes it (fraction digits vary). */
    static final String INSTANT_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$";

    private static final String PREVIEW = "/api/cart/preview";

    // ------------------------------------------------------------------------------------------------ pacts

    @Pact(consumer = CONSUMER)
    V4Pact readyWithCoupon(PactDslWithProvider builder) {
        return builder
                .given("a grocery cart ready for checkout, with coupon SAVE10 applied",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString()))
                .uponReceiving("a checkout preview for one of the customer's addresses, with a coupon")
                .method("GET")
                .path(PREVIEW)
                .query("addressId=" + ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(readyPreview(ADDRESS_ID, o -> o
                        .object("coupon", c -> c
                                .stringValue("code", "SAVE10")
                                .booleanValue("valid", true)
                                .numberType("discount", money("36.00"))
                                .numberType("deliveryFeeDiscount", money("0.00")))
                        .numberType("discount", money("36.00"))
                        .numberType("deliveryFeeDiscount", money("0.00"))
                        .numberType("total", money("349.00"))))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact readyAtDefaultAddress(PactDslWithProvider builder) {
        return builder
                .given("a grocery cart ready for checkout at the customer's default address",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString()))
                .uponReceiving("a checkout preview for the default address")
                .method("GET")
                .path(PREVIEW)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(readyPreview(ADDRESS_ID, o -> o.numberType("total", money("385.00"))))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact blocked(PactDslWithProvider builder) {
        return builder
                .given("a grocery cart with an item that went out of stock",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString(),
                                "productId", PRODUCT_ID.toString()))
                .uponReceiving("a checkout preview with a blocking issue")
                .method("GET")
                .path(PREVIEW)
                .query("addressId=" + ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(newJsonBody(o -> o
                        .booleanValue("canCheckout", false)
                        .eachLike("issues", issue -> issue
                                .stringType("code", "OUT_OF_STOCK")
                                .uuid("itemId", PRODUCT_ID)
                                .booleanValue("blocking", true)
                                .stringType("message", "Amul Taaza Toned Milk 1 L is out of stock"))).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownAddress(PactDslWithProvider builder) {
        return builder
                .given("the address is not one of the customer's",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", UNKNOWN_ADDRESS_ID.toString()))
                .uponReceiving("a checkout preview for an address the customer does not have")
                .method("GET")
                .path(PREVIEW)
                .query("addressId=" + UNKNOWN_ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(newJsonBody(o -> o.stringValue("errorCode", "ADDRESS_NOT_FOUND")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact customerServiceDown(PactDslWithProvider builder) {
        return builder
                .given("customer-service is unavailable",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString()))
                .uponReceiving("a checkout preview while cart-service cannot reach customer-service")
                .method("GET")
                .path(PREVIEW)
                .query("addressId=" + ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(503)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(newJsonBody(o -> o
                        .stringValue("errorCode", "DEPENDENCY_UNAVAILABLE")
                        .stringValue("dependency", "customer")).build())
                .toPact(V4Pact.class);
    }

    /** Everything placement stores or checks from a checkout-ready grocery preview, plus the {@code extra} fields. */
    private static DslPart readyPreview(UUID addressId, Consumer<LambdaDslObject> extra) {
        return newJsonBody(o -> {
            o.object("store", s -> s
                    .uuid("id", VENDOR_ID)
                    .stringMatcher("type", "GROCERY|RESTAURANT", "GROCERY")
                    .stringType("name", "FreshMart Indiranagar")
                    .stringType("city", "Bengaluru")
                    .numberType("latitude", 12.9719)
                    .numberType("longitude", 77.6412));
            o.eachLike("lines", line -> line
                    .uuid("itemId", PRODUCT_ID)
                    .stringMatcher("type", "PRODUCT|MENU_ITEM", "PRODUCT")
                    .stringType("name", "Amul Taaza Toned Milk 1 L")
                    .stringType("unitLabel", "1 L")
                    .stringType("imageUrl", "https://cdn.grocery.local/products/amul-taaza-1l.jpg")
                    .stringType("foodType", "VEG")
                    .integerType("quantity", 4)
                    .numberType("unitPrice", money("90.00"))
                    .numberType("mrp", money("100.00"))
                    .numberType("lineTotal", money("360.00")));
            o.integerType("itemCount", 4);
            o.numberType("subtotal", money("360.00"));
            o.numberType("mrpTotal", money("400.00"));
            o.numberType("savings", money("40.00"));
            o.stringType("currency", "INR");
            o.array("issues", issues -> {
            });
            o.stringMatcher("updatedAt", INSTANT_REGEX, UPDATED_AT);
            o.object("address", a -> a
                    .uuid("id", addressId)
                    .stringType("label", "Home")
                    .stringType("recipientName", "Asha Rao")
                    .stringType("phone", "+919800000001")
                    .stringType("line1", "12, 4th Cross, HAL 2nd Stage")
                    .stringType("line2", "Indiranagar")
                    .stringType("landmark", "Near the metro station")
                    .stringType("city", "Bengaluru")
                    .stringType("state", "Karnataka")
                    .stringType("postalCode", "560038")
                    .numberType("latitude", 12.9784)
                    .numberType("longitude", 77.6408));
            o.object("delivery", d -> d
                    .numberType("distanceKm", money("1.20"))
                    .numberType("fee", money("25.00")));
            o.booleanValue("canCheckout", true);
            extra.accept(o);
        }).build();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    // ------------------------------------------------------------------------------------------------ tests

    private static CartClient client(MockServer server) {
        OrderProperties properties = new OrderProperties();
        properties.getServices().setCart(server.getUrl());
        return new CartClient(ConsumerPacts.downstream(properties), properties);
    }

    @Test
    @PactTestFor(pactMethod = "readyWithCoupon")
    void readsACheckoutReadyPreviewWithItsCoupon(MockServer server) {
        Outcome<CartPreview> outcome = client(server).preview(CALL, ADDRESS_ID);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CartPreview preview = ((Outcome.Ok<CartPreview>) outcome).value();
        assertThat(preview.checkoutAllowed()).isTrue();
        assertThat(preview.store().id()).isEqualTo(VENDOR_ID);
        assertThat(preview.store().type()).isEqualTo("GROCERY");
        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.itemId()).isEqualTo(PRODUCT_ID);
            assertThat(line.type()).isEqualTo("PRODUCT");
            assertThat(line.quantity()).isEqualTo(4);
            assertThat(line.unitPrice()).isEqualByComparingTo("90.00");
            assertThat(line.lineTotal()).isEqualByComparingTo("360.00");
        });
        assertThat(preview.address().id()).isEqualTo(ADDRESS_ID);
        assertThat(preview.delivery().fee()).isEqualByComparingTo("25.00");
        assertThat(preview.updatedAt()).isEqualTo(Instant.parse(UPDATED_AT));
        assertThat(preview.hasCoupon()).isTrue();
        assertThat(preview.coupon().code()).isEqualTo("SAVE10");
        assertThat(preview.coupon().valid()).isTrue();
        assertThat(preview.discount()).isEqualByComparingTo("36.00");
        assertThat(preview.total()).isEqualByComparingTo("349.00");
    }

    @Test
    @PactTestFor(pactMethod = "readyAtDefaultAddress")
    void readsThePreviewForTheDefaultAddressWithoutAnAddressId(MockServer server) {
        Outcome<CartPreview> outcome = client(server).preview(CALL, null);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CartPreview preview = ((Outcome.Ok<CartPreview>) outcome).value();
        assertThat(preview.checkoutAllowed()).isTrue();
        assertThat(preview.hasCoupon()).isFalse();
        assertThat(preview.address().id()).isEqualTo(ADDRESS_ID);
        assertThat(preview.total()).isEqualByComparingTo("385.00");
    }

    @Test
    @PactTestFor(pactMethod = "blocked")
    void readsBlockingIssues(MockServer server) {
        Outcome<CartPreview> outcome = client(server).preview(CALL, ADDRESS_ID);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CartPreview preview = ((Outcome.Ok<CartPreview>) outcome).value();
        assertThat(preview.checkoutAllowed()).isFalse();
        assertThat(preview.issuesOrEmpty()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("OUT_OF_STOCK");
            assertThat(issue.itemId()).isEqualTo(PRODUCT_ID);
            assertThat(issue.blocking()).isTrue();
        });
    }

    @Test
    @PactTestFor(pactMethod = "unknownAddress")
    void mapsAddressNotFoundToNotFound(MockServer server) {
        assertThat(client(server).preview(CALL, UNKNOWN_ADDRESS_ID)).isInstanceOf(Outcome.NotFound.class);
    }

    @Test
    @PactTestFor(pactMethod = "customerServiceDown")
    void namesTheServiceCartCouldNotReach(MockServer server) {
        assertThat(client(server).preview(CALL, ADDRESS_ID)).isEqualTo(new Outcome.Failed<CartPreview>("customer"));
    }
}
