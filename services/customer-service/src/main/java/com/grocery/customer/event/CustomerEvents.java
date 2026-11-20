package com.grocery.customer.event;

import java.time.Instant;
import java.util.UUID;

/** Payloads PUBLISHED by customer-service on {@code customer.events} (v1). Consumers keep their own copies. */
public final class CustomerEvents {

    public static final String TOPIC = "customer.events";
    public static final String AGGREGATE = "Customer";

    private CustomerEvents() {
    }

    /**
     * The name or phone of a profile changed (customerId = userId). identity-service puts them on the account (header,
     * JWT name claim); notification-service on its email address book.
     */
    public record CustomerProfileUpdated(UUID customerId, String fullName, String phone, Instant updatedAt) {
    }
}
