package com.grocery.cart.client;

import com.grocery.cart.config.CartProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/**
 * Shared plumbing of the downstream clients: one HTTP client with short timeouts
 * ({@code grocery.cart.http.*}) and one circuit breaker per {@link Dependency}.
 *
 * <p>{@link #call} turns every call into an {@link Outcome}: the body, a definite "not found" (a 404 carrying the
 * owner's expected error code — any other 404, e.g. an endpoint that is not deployed, is a failure), or a failure.
 * Server errors, timeouts and connection failures are recorded by the breaker; client errors are not.
 */
@Component
public class Downstream {

    private static final Logger log = LoggerFactory.getLogger(Downstream.class);

    private final CircuitBreakerFactory<?, ?> breakers;
    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactory requestFactory;
    private final JsonMapper jsonMapper;

    public Downstream(CircuitBreakerFactory<?, ?> breakers, RestClient.Builder restClientBuilder,
                      CartProperties properties, JsonMapper jsonMapper) {
        this.breakers = breakers;
        this.restClientBuilder = restClientBuilder;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getHttp().getReadTimeout());
        this.requestFactory = factory;
    }

    /** A RestClient for one dependency (Spring Boot's message converters, the shared timeouts). */
    public RestClient client(String baseUrl) {
        return restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Runs {@code call} through the dependency's circuit breaker.
     *
     * @param notFoundCode the {@code errorCode} of the owner's 404 that means "does not exist" (null: none)
     */
    public <T> Outcome<T> call(Dependency dependency, String notFoundCode, Supplier<T> call) {
        return breakers.create(dependency.id()).run(() -> {
            try {
                T body = call.get();
                if (body == null) {
                    throw new IllegalStateException("empty response body");
                }
                return new Outcome.Ok<>(body);
            } catch (HttpClientErrorException.NotFound e) {
                if (notFoundCode != null && notFoundCode.equals(errorCode(e))) {
                    return new Outcome.NotFound<>();
                }
                throw e;
            }
        }, failure -> {
            log.warn("{} call failed: {}", dependency.id(), failure == null ? "unknown" : failure.toString());
            return new Outcome.Failed<>(dependency);
        });
    }

    private String errorCode(HttpClientErrorException e) {
        try {
            JsonNode problem = jsonMapper.readTree(e.getResponseBodyAsString());
            JsonNode code = problem == null ? null : problem.get("errorCode");
            return code == null || code.isNull() ? null : code.asString();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
