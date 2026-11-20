package com.grocery.order.client;

import com.grocery.order.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/**
 * Shared plumbing of the downstream clients: one HTTP client with short timeouts ({@code grocery.order.http.*})
 * and one circuit breaker per {@link Dependency}.
 *
 * <p>{@link #call} turns every call into an {@link Outcome}: the body, a definite "not found" (a 404 carrying the
 * owner's expected error code — any other 404, e.g. an endpoint that is not deployed, is a failure), or a failure.
 * Server errors, timeouts and connection failures are recorded by the breaker; client errors are not.
 */
@Component
public class Downstream {

    static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(Downstream.class);

    private final CircuitBreakerFactory<?, ?> breakers;
    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactory requestFactory;
    private final JsonMapper jsonMapper;

    public Downstream(CircuitBreakerFactory<?, ?> breakers,
                      RestClient.Builder restClientBuilder,
                      OrderProperties properties, JsonMapper jsonMapper) {
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
            return new Outcome.Failed<>(reportedDependency(dependency, failure));
        });
    }

    /**
     * The called service, or — when it answered 503 {@code DEPENDENCY_UNAVAILABLE} with a {@code dependency}
     * property (e.g. cart-service could not reach customer-service) — the service it could not reach.
     */
    private String reportedDependency(Dependency dependency, Throwable failure) {
        if (failure instanceof HttpServerErrorException e
                && e.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            JsonNode problem = problem(e);
            if (problem != null && DEPENDENCY_UNAVAILABLE.equals(text(problem, "errorCode"))) {
                String inner = text(problem, "dependency");
                if (inner != null && !inner.isBlank()) {
                    return inner;
                }
            }
        }
        return dependency.id();
    }

    private String errorCode(RestClientResponseException e) {
        JsonNode problem = problem(e);
        return problem == null ? null : text(problem, "errorCode");
    }

    /** The ProblemDetail body of an error answer, or null when it is not a JSON object. */
    JsonNode problem(RestClientResponseException e) {
        try {
            JsonNode problem = jsonMapper.readTree(e.getResponseBodyAsString());
            return problem != null && problem.isObject() ? problem : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
