package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.CustomerClient;
import com.grocery.cart.client.DownstreamDtos.CustomerAddress;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.config.CartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike;
import static com.grocery.cart.contract.ConsumerPacts.ADDRESS_ID;
import static com.grocery.cart.contract.ConsumerPacts.BEARER;
import static com.grocery.cart.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.CALL;
import static com.grocery.cart.contract.ConsumerPacts.CONSUMER;
import static com.grocery.cart.contract.ConsumerPacts.CUSTOMER_ID;
import static com.grocery.cart.contract.ConsumerPacts.JSON;
import static com.grocery.cart.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.UNKNOWN_ADDRESS_ID;
import static com.grocery.cart.contract.ConsumerPacts.body;
import static com.grocery.cart.contract.ConsumerPacts.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart-service → customer-service: the caller's own addresses, {@code GET /api/customers/me/addresses/{id}} and
 * {@code GET /api/customers/me/addresses} ({@link CustomerClient}), with the forwarded customer token ("me" is its
 * subject). Writes {@code contracts/pacts/cart-service-customer-service.json}. 404 ADDRESS_NOT_FOUND (unknown or
 * another customer's address) means "not found" (→ NotFound), any other error → Failed.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "customer-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class CustomerServiceConsumerPactTest {

    private static final String ADDRESSES = "/api/customers/me/addresses";

    // ------------------------------------------------------------------------------------------------ pacts

    @Pact(consumer = CONSUMER)
    V4Pact oneAddress(PactDslWithProvider builder) {
        return builder
                .given("the customer has a default address with a location",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString()))
                .uponReceiving("a request for one of the customer's addresses")
                .method("GET")
                .path(ADDRESSES + "/" + ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(CustomerServiceConsumerPactTest::address))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownAddress(PactDslWithProvider builder) {
        return builder
                .given("the address is not one of the customer's",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", UNKNOWN_ADDRESS_ID.toString()))
                .uponReceiving("a request for an address the customer does not have")
                .method("GET")
                .path(ADDRESSES + "/" + UNKNOWN_ADDRESS_ID)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(problem("ADDRESS_NOT_FOUND"))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact addressBook(PactDslWithProvider builder) {
        return builder
                .given("the customer has a default address with a location",
                        Map.of("customerId", CUSTOMER_ID.toString(), "addressId", ADDRESS_ID.toString()))
                .uponReceiving("a request for all the customer's addresses")
                .method("GET")
                .path(ADDRESSES)
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(newJsonArrayMinLike(1, addresses -> addresses.object(CustomerServiceConsumerPactTest::address))
                        .build())
                .toPact(V4Pact.class);
    }

    /** Phase 2 Address: the fields the cart shows and uses for the delivery fee ({@code isDefault} picks one). */
    private static void address(LambdaDslObject o) {
        o.uuid("id", ADDRESS_ID)
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
                .numberType("longitude", 77.6408)
                .booleanType("isDefault", true);
    }

    // ------------------------------------------------------------------------------------------------ tests

    private static CustomerClient client(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        return new CustomerClient(ConsumerPacts.downstream(properties), properties);
    }

    @Test
    @PactTestFor(pactMethod = "oneAddress")
    void readsOneAddress(MockServer server) {
        Outcome<CustomerAddress> outcome = client(server).address(CALL, ADDRESS_ID);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        CustomerAddress address = ((Outcome.Ok<CustomerAddress>) outcome).value();
        assertThat(address.id()).isEqualTo(ADDRESS_ID);
        assertThat(address.recipientName()).isEqualTo("Asha Rao");
        assertThat(address.postalCode()).isEqualTo("560038");
        assertThat(address.hasLocation()).isTrue();
        assertThat(address.isDefault()).isTrue();
    }

    @Test
    @PactTestFor(pactMethod = "unknownAddress")
    void mapsAddressNotFoundToNotFound(MockServer server) {
        assertThat(client(server).address(CALL, UNKNOWN_ADDRESS_ID)).isInstanceOf(Outcome.NotFound.class);
    }

    @Test
    @PactTestFor(pactMethod = "addressBook")
    void readsTheAddressBook(MockServer server) {
        Outcome<List<CustomerAddress>> outcome = client(server).addresses(CALL);

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<List<CustomerAddress>>) outcome).value()).singleElement().satisfies(address -> {
            assertThat(address.id()).isEqualTo(ADDRESS_ID);
            assertThat(address.isDefault()).isTrue();
            assertThat(address.hasLocation()).isTrue();
        });
    }
}
