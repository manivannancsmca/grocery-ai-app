package com.grocery.payment.config;

import com.grocery.payment.gateway.ProviderRejectedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class PaymentConfig {

    /**
     * UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored.
     * The pollers and the payment window read time only from this clock (tests replace it).
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /**
     * The Razorpay circuit breaker. 4xx answers ({@link ProviderRejectedException}) are answers, not outages: they
     * never count towards opening it. No time limiter: the HTTP timeouts bound every call, which stays on the
     * caller's thread.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> paymentCircuitBreakerDefaults() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(ProviderRejectedException.class)
                .build();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(config)
                .timeLimiterConfig(TimeLimiterConfig.ofDefaults())
                .build());
    }

    /**
     * The payment window and refund retry pollers and (Phase 10) reconciliation
     * ({@code grocery.payment.pollers-enabled=false} in tests).
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.payment", name = "pollers-enabled", havingValue = "true",
            matchIfMissing = true)
    static class Scheduling {
    }
}
