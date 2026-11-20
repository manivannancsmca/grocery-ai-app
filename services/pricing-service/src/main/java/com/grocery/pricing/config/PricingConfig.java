package com.grocery.pricing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class PricingConfig {

    /**
     * UTC, truncated to microseconds = the precision of DATETIME(6). A timestamp in an event or response is
     * then exactly what the database stores, so a republished PriceChanged is identical to the original one.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /**
     * Phase 12: runs the coupon reservation sweeper; {@code grocery.pricing.coupons.sweeper-enabled=false} switches it
     * off (tests run it by hand).
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.pricing.coupons", name = "sweeper-enabled", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
