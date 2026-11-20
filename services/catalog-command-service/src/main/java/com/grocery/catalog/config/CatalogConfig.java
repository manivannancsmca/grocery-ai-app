package com.grocery.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfig {

    /**
     * UTC, truncated to microseconds = the precision of DATETIME(6). A timestamp in a snapshot is then
     * exactly what the database stores, so a republished snapshot is identical to the original one.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
