package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.DownstreamDtos.Availability;
import com.grocery.cart.client.InventoryClient;
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
 * cart-service → inventory-service: {@code POST /internal/inventory/availability} ({@link InventoryClient}). Writes
 * {@code contracts/pacts/cart-service-inventory-service.json}. Unknown and archived products are listed in
 * {@code missing}; errors have no special meaning for the cart (→ Failed).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "inventory-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class InventoryServiceConsumerPactTest {

    @Pact(consumer = CONSUMER)
    V4Pact stockedAndMissingProducts(PactDslWithProvider builder) {
        return builder
                .given("a product has 12 units available and another product is unknown",
                        Map.of("productId", PRODUCT_ID.toString(), "vendorId", VENDOR_ID.toString(),
                                "missingProductId", MISSING_PRODUCT_ID.toString()))
                .uponReceiving("an availability check of a stocked and an unknown product")
                .method("POST")
                .path("/internal/inventory/availability")
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
                            .integerType("available", 12)
                            .booleanType("inStock", true));
                    o.array("missing", missing -> missing.uuid(MISSING_PRODUCT_ID.toString()));
                }))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "stockedAndMissingProducts")
    void readsTheAvailableStock(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        InventoryClient client = new InventoryClient(ConsumerPacts.downstream(properties), properties);

        Outcome<Availability> outcome = client.availability(CALL, List.of(PRODUCT_ID, MISSING_PRODUCT_ID));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        Availability availability = ((Outcome.Ok<Availability>) outcome).value();
        assertThat(availability.byId()).containsOnlyKeys(PRODUCT_ID);
        assertThat(availability.byId().get(PRODUCT_ID)).satisfies(item -> {
            assertThat(item.vendorId()).isEqualTo(VENDOR_ID);
            assertThat(item.availableQuantity()).isEqualTo(12);
            assertThat(item.orderable()).isTrue();
        });
        assertThat(availability.missing()).containsExactly(MISSING_PRODUCT_ID);
    }
}
