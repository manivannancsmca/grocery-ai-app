package com.grocery.vendor.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FulfillmentProperties.class, StoreDirectoryProperties.class})
public class VendorConfig {

    /**
     * UTC, ticking in microseconds = the precision of the DATETIME(6) columns, so a timestamp put into an event or a
     * response is exactly the value stored. Tests replace it with a clock they can pin.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /** Runs the auto-reject poller; {@code grocery.fulfillment.auto-reject-enabled=false} switches it off. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.fulfillment", name = "auto-reject-enabled", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
