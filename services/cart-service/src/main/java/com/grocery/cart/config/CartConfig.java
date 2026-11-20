package com.grocery.cart.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CartConfig {

    /** UTC, millisecond precision (timestamps in the cart and its responses). */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }

    /**
     * The same breaker settings for every dependency ({@code grocery.cart.circuit-breaker.*}). Client errors (4xx)
     * are answers, not outages: they never count towards opening a breaker. No time limiter: the HTTP read timeout
     * already bounds every call, and calls stay on the request's thread.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> cartCircuitBreakerDefaults(CartProperties properties) {
        CartProperties.CircuitBreaker settings = properties.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedCallsInHalfOpenState())
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(config)
                .timeLimiterConfig(TimeLimiterConfig.ofDefaults())
                .build());
    }
}
