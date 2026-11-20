package com.grocery.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Delivery service: delivery partner onboarding and approval lifecycle (Phase 2, package
 * {@code partner}); deliveries, offers, assignment, live location and tracking (Phase 9, package
 * {@code dispatch}).
 */
@SpringBootApplication
public class DeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }
}
