package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.DownstreamDtos.VendorStore;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.client.VendorClient;
import com.grocery.cart.config.CartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static com.grocery.cart.contract.ConsumerPacts.BEARER;
import static com.grocery.cart.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.CALL;
import static com.grocery.cart.contract.ConsumerPacts.CONSUMER;
import static com.grocery.cart.contract.ConsumerPacts.JSON;
import static com.grocery.cart.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.UNKNOWN_VENDOR_ID;
import static com.grocery.cart.contract.ConsumerPacts.VENDOR_ID;
import static com.grocery.cart.contract.ConsumerPacts.body;
import static com.grocery.cart.contract.ConsumerPacts.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart-service → vendor-service: {@code GET /internal/vendors/{vendorId}/store} ({@link VendorClient}). Writes
 * {@code contracts/pacts/cart-service-vendor-service.json}. The store is answered in any status ({@code active} says
 * whether it may sell); 404 VENDOR_NOT_FOUND means "does not exist" (→ NotFound), any other error → Failed.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "vendor-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class VendorServiceConsumerPactTest {

    @Pact(consumer = CONSUMER)
    V4Pact approvedStore(PactDslWithProvider builder) {
        return builder
                .given("an approved vendor with a store location", Map.of("vendorId", VENDOR_ID.toString()))
                .uponReceiving("a request for an approved vendor's store")
                .method("GET")
                .path("/internal/vendors/" + VENDOR_ID + "/store")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> o
                        .uuid("vendorId", VENDOR_ID)
                        .stringType("name", "FreshMart Indiranagar")
                        .stringValue("status", "APPROVED")
                        .booleanValue("active", true)
                        .stringType("city", "Bengaluru")
                        .numberType("latitude", 12.9719)
                        .numberType("longitude", 77.6412)))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownVendor(PactDslWithProvider builder) {
        return builder
                .given("no vendor exists with the id", Map.of("vendorId", UNKNOWN_VENDOR_ID.toString()))
                .uponReceiving("a request for an unknown vendor's store")
                .method("GET")
                .path("/internal/vendors/" + UNKNOWN_VENDOR_ID + "/store")
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(problem("VENDOR_NOT_FOUND"))
                .toPact(V4Pact.class);
    }

    private static VendorClient client(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        return new VendorClient(ConsumerPacts.downstream(properties), properties);
    }

    @Test
    @PactTestFor(pactMethod = "approvedStore")
    void readsTheStore(MockServer server) {
        Outcome<VendorStore> outcome = client(server).store(CALL, VENDOR_ID);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        VendorStore store = ((Outcome.Ok<VendorStore>) outcome).value();
        assertThat(store.vendorId()).isEqualTo(VENDOR_ID);
        assertThat(store.name()).isEqualTo("FreshMart Indiranagar");
        assertThat(store.isActive()).isTrue();
        assertThat(store.city()).isEqualTo("Bengaluru");
        assertThat(store.latitude()).isEqualTo(12.9719);
        assertThat(store.longitude()).isEqualTo(77.6412);
    }

    @Test
    @PactTestFor(pactMethod = "unknownVendor")
    void mapsVendorNotFoundToNotFound(MockServer server) {
        assertThat(client(server).store(CALL, UNKNOWN_VENDOR_ID)).isInstanceOf(Outcome.NotFound.class);
    }
}
