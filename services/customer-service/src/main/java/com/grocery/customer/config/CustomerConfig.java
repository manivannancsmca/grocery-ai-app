package com.grocery.customer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CustomerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
