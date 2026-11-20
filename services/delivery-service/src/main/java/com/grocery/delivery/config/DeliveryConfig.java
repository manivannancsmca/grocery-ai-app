package com.grocery.delivery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class DeliveryConfig {

    /**
     * UTC, ticking in microseconds = the precision of the DATETIME(6) columns, so a timestamp put into an event or a
     * response is exactly the value stored. Tests replace it with a clock they can pin.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
