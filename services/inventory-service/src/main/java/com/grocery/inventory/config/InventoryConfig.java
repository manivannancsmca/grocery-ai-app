package com.grocery.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryConfig {

    /** UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /** Runs the reservation expiry poller; {@code grocery.inventory.expiry.enabled=false} switches it off. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.inventory.expiry", name = "enabled", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
