package com.grocery.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Review service (Phase 12 §1): reviews and ratings of the products, dishes, stores and delivery partners of delivered
 * orders. What a customer may review is projected from {@code order.events} ({@code OrderConfirmed},
 * {@code OrderDelivered}); reviews are moderated automatically and by admins, stores reply, and every change of a
 * target's published ratings is summarised. Every fact leaves on {@code review.events} through the outbox.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewServiceApplication.class, args);
    }
}
