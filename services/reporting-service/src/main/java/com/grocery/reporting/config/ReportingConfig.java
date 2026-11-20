package com.grocery.reporting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

/** The clock, and scheduling for the audit trail's daily purge (AuditService#purge). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReportingConfig {

    /** UTC, truncated to microseconds = the precision of DATETIME(6). Today's India date for the default range. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
