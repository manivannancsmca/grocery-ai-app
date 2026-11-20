package com.grocery.order.contract;

import com.grocery.order.client.CallContext;
import com.grocery.order.client.Downstream;
import com.grocery.order.config.OrderProperties;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared pieces of order-service's Pact consumer tests (docs/api/phase-13-observability-and-testing.md §4). The real
 * clients run against the Pact mock server; a pass-through circuit breaker keeps the fallback path (errors become
 * {@code Outcome.Failed}) without breaker state between tests.
 *
 * <p>The forwarded customer token is an opaque example ({@link #BEARER}) matched by {@link #BEARER_REGEX}: providers
 * never need a real signed token to verify these pacts (each provider test decodes any bearer token into a CUSTOMER
 * JWT whose subject comes from the provider state).
 */
final class ConsumerPacts {

    static final String CONSUMER = "order-service";

    static final String BEARER = "Bearer contract-test-token";
    static final String BEARER_REGEX = "^Bearer \\S+$";
    static final String JSON = "application/json";
    static final String JSON_REGEX = "^application/json.*";
    static final String PROBLEM = "application/problem+json";
    static final String PROBLEM_REGEX = "^application/problem\\+json.*";

    /** A customer's call: the forwarded token and a correlation ID (not part of the contract). */
    static final CallContext CALL = new CallContext(BEARER, "contract-test-correlation-id");

    private ConsumerPacts() {
    }

    /** order-service's own client plumbing (timeouts, error mapping), without circuit breaker state. */
    static Downstream downstream(OrderProperties properties) {
        return new Downstream(new PassThroughCircuitBreakers(), RestClient.builder(), properties,
                JsonMapper.builder().build());
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
