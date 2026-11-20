package com.grocery.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Order service (Phase 6): places orders from the live checkout preview of cart-service (idempotent per
 * {@code Idempotency-Key}), stores a price snapshot, owns the order status and its history, cancellation rules and
 * the admin stats. Moves on only through events (choreography): {@code inventory.events} and {@code payment.events}
 * in, {@code order.events} out through the outbox. Every status change follows the locking rule documented on
 * {@link com.grocery.order.service.OrderLocks}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
