package com.grocery.catalogquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CatalogQueryConfig {

    /** UTC system clock; tests replace it (a {@code @Primary} fixed clock) to check "open now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
