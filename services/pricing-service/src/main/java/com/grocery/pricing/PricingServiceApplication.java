package com.grocery.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pricing service (Phase 4): the live grocery price of every product (MRP + selling price), its history,
 * price-change requests above the approval threshold and their admin approval, and the Redis-cached price
 * quote used by cart and order. Products are known from {@code catalog.events}; every price event leaves
 * through the outbox ({@code pricing.events}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PricingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
