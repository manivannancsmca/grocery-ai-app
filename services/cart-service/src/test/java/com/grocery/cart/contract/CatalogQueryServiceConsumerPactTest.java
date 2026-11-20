package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.CatalogClient;
import com.grocery.cart.client.DownstreamDtos.ProductLookup;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.config.CartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static com.grocery.cart.contract.ConsumerPacts.BEARER;
import static com.grocery.cart.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.CALL;
import static com.grocery.cart.contract.ConsumerPacts.CONSUMER;
import static com.grocery.cart.contract.ConsumerPacts.JSON;
import static com.grocery.cart.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.MISSING_PRODUCT_ID;
import static com.grocery.cart.contract.ConsumerPacts.PRODUCT_ID;
import static com.grocery.cart.contract.ConsumerPacts.VENDOR_ID;
import static com.grocery.cart.contract.ConsumerPacts.body;
import static com.grocery.cart.contract.ConsumerPacts.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart-service → catalog-query-service: {@code POST /internal/catalog/products/lookup} ({@link CatalogClient}).
 * Writes {@code contracts/pacts/cart-service-catalog-query-service.json}. Only visible products come back as cards;
 * every other requested id is listed in {@code missing}. Errors have no special meaning for the cart (→ Failed).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "catalog-query-service",
        mockServerImplementation = MockServerImplementation.JavaHttpServer)
class CatalogQueryServiceConsumerPactTest {

    @Pact(consumer = CONSUMER)
    V4Pact visibleAndMissingProducts(PactDslWithProvider builder) {
        return builder
                .given("one product is visible and another is not",
                        Map.of("productId", PRODUCT_ID.toString(), "vendorId", VENDOR_ID.toString(),
                                "missingProductId", MISSING_PRODUCT_ID.toString()))
                .uponReceiving("a lookup of a visible and a not visible product")
                .method("POST")
                .path("/internal/catalog/products/lookup")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(ids("productIds", PRODUCT_ID, MISSING_PRODUCT_ID))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> {
                    o.eachLike("items", item -> item
                            .uuid("id", PRODUCT_ID)
                            .stringType("name", "Amul Taaza Toned Milk 1 L")
                            .stringType("unitLabel", "1 L")
                            .stringType("foodType", "VEG")
                            .stringType("primaryImageUrl", "https://cdn.grocery.local/products/amul-taaza-1l.jpg")
                            .object("vendor", vendor -> vendor
                                    .uuid("id", VENDOR_ID)
                                    .stringType("name", "FreshMart Indiranagar")));
                    o.array("missing", missing -> missing.uuid(MISSING_PRODUCT_ID.toString()));
                }))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "visibleAndMissingProducts")
    void readsTheCardsOfVisibleProductsAndTheMissingIds(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        CatalogClient client = new CatalogClient(ConsumerPacts.downstream(properties), properties);

        Outcome<ProductLookup> outcome = client.lookup(CALL, List.of(PRODUCT_ID, MISSING_PRODUCT_ID));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        ProductLookup lookup = ((Outcome.Ok<ProductLookup>) outcome).value();
        assertThat(lookup.byId()).containsOnlyKeys(PRODUCT_ID);
        assertThat(lookup.byId().get(PRODUCT_ID)).satisfies(card -> {
            assertThat(card.name()).isEqualTo("Amul Taaza Toned Milk 1 L");
            assertThat(card.unitLabel()).isEqualTo("1 L");
            assertThat(card.foodType()).isEqualTo("VEG");
            assertThat(card.vendor().id()).isEqualTo(VENDOR_ID);
        });
        assertThat(lookup.missing()).containsExactly(MISSING_PRODUCT_ID);
    }
}
