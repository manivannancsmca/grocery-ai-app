package com.grocery.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OrderConfig {

    /** UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /**
     * Breaker settings for every dependency ({@code grocery.order.circuit-breaker.*}). Client errors (4xx) are
     * answers, not outages: they never count towards opening a breaker. No time limiter: the HTTP read timeout
     * already bounds every call, and calls stay on the request's thread.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> orderCircuitBreakerDefaults(OrderProperties properties) {
        OrderProperties.CircuitBreaker settings = properties.getCircuitBreaker();
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

    /**
     * Runs the stuck-order watchdog (Phase 10) and the return escalation poller (Phase 12), both safe in every
     * replica. Scheduling is on while either is enabled; each scheduled method also checks its own switch
     * ({@code grocery.order.watchdog.enabled}, {@code grocery.order.returns.escalation-enabled}), so one can be off
     * while the other runs. Tests switch both off and run them themselves.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnExpression("${grocery.order.watchdog.enabled:true} or ${grocery.order.returns.escalation-enabled:true}")
    static class Scheduling {
    }
}
