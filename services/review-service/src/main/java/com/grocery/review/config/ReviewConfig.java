package com.grocery.review.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ReviewConfig {

    /**
     * UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored.
     * The review and edit windows read time only from this clock (tests replace it).
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
