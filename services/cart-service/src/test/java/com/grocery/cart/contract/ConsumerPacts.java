package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.LambdaDslJsonBody;
import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.Downstream;
import com.grocery.cart.config.CartProperties;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;

/**
 * Shared pieces of cart-service's Pact consumer tests (docs/api/phase-13-observability-and-testing.md §4): the real
 * clients run against the Pact mock server; a pass-through circuit breaker keeps the fallback path (errors become
 * {@code Outcome.Failed}) without breaker state between tests.
 *
 * <p>The forwarded customer token is an opaque example ({@link #BEARER}) matched by {@link #BEARER_REGEX}: providers
 * never need a real signed token to verify these pacts (each provider test decodes any bearer token into a CUSTOMER
 * JWT whose subject comes from the provider state). Ids are fixed so that the committed pact files only change when
 * the contract does.
 */
final class ConsumerPacts {

    static final String CONSUMER = "cart-service";

    static final String BEARER = "Bearer contract-test-token";
    static final String BEARER_REGEX = "^Bearer \\S+$";
    static final String JSON = "application/json";
    static final String JSON_REGEX = "^application/json.*";
    static final String PROBLEM = "application/problem+json";
    static final String PROBLEM_REGEX = "^application/problem\\+json.*";

    static final UUID CUSTOMER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    static final UUID VENDOR_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6b1");
    static final UUID UNKNOWN_VENDOR_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6b9");
    static final UUID PRODUCT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6c1");
    /** Unknown, hidden or archived: always answered in {@code missing}. */
    static final UUID MISSING_PRODUCT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6c9");
    static final UUID RESTAURANT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6f1");
    static final UUID UNKNOWN_RESTAURANT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6f9");
    static final UUID MENU_ITEM_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e7a1");
    /** Not on the restaurant's menu: answered in {@code missing}. */
    static final UUID MISSING_MENU_ITEM_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e7a9");
    static final UUID ADDRESS_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6a1");
    static final UUID UNKNOWN_ADDRESS_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6a9");

    /** A customer's call: the forwarded token and a correlation ID (not part of the contract). */
    static final CallContext CALL = new CallContext(BEARER, "contract-test-correlation-id");

    private ConsumerPacts() {
    }

    /** Every service the cart reads, at the Pact mock server. */
    static CartProperties properties(MockServer server) {
        CartProperties properties = new CartProperties();
        CartProperties.Services services = properties.getServices();
        services.setPricing(server.getUrl());
        services.setInventory(server.getUrl());
        services.setCatalogQuery(server.getUrl());
        services.setVendor(server.getUrl());
        services.setRestaurant(server.getUrl());
        services.setCustomer(server.getUrl());
        return properties;
    }

    /** cart-service's own client plumbing (timeouts, error mapping), without circuit breaker state. */
    static Downstream downstream(CartProperties properties) {
        return new Downstream(new PassThroughCircuitBreakers(), RestClient.builder(), properties,
                JsonMapper.builder().build());
    }

    /** {@code { "<field>": [ids…] }}, the body of the batch endpoints. */
    static DslPart ids(String field, UUID... ids) {
        return newJsonBody(o -> o.array(field, array -> {
            for (UUID id : ids) {
                array.uuid(id.toString());
            }
        })).build();
    }

    /** A JSON body built with the lambda DSL (shorthand for the response bodies). */
    static DslPart body(Consumer<LambdaDslJsonBody> fields) {
        return newJsonBody(fields).build();
    }

    /** {@code { "errorCode": code }}: the part of a ProblemDetail the cart reads. */
    static DslPart problem(String errorCode) {
        return newJsonBody(o -> o.stringValue("errorCode", errorCode)).build();
    }

    /** Runs every call; a failure goes straight to the fallback (as an open breaker's would). */
    static final class PassThroughCircuitBreakers extends CircuitBreakerFactory<Object, ConfigBuilder<Object>> {

        @Override
        public CircuitBreaker create(String id) {
            return new CircuitBreaker() {
                @Override
                public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
                    try {
                        return toRun.get();
                    } catch (RuntimeException failure) {
                        return fallback.apply(failure);
                    }
                }
            };
        }

        @Override
        protected ConfigBuilder<Object> configBuilder(String id) {
            return Object::new;
        }

        @Override
        public void configureDefault(Function<String, Object> defaultConfiguration) {
        }
    }
}
