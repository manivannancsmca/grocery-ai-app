package com.grocery.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Cart service (Phase 5, order events from Phase 6): one cart per customer in Redis (one store per cart), priced
 * live from the owning services (pricing, inventory, catalog-query, vendor, restaurant) every time it is shown, and
 * the checkout preview (delivery fee, totals, blocking issues) for one of the customer's addresses. No database and
 * nothing published; it consumes {@code order.events → OrderCreated} to empty the cart an order was placed from
 * (only if it has not changed since, see {@link com.grocery.cart.event.OrderEventsListener}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CartServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
