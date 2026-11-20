package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.CustomerAddress;
import com.grocery.cart.config.CartProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * customer-service: the caller's own addresses ({@code /api/customers/me/addresses}). "me" is the subject of the
 * forwarded token, so a customer can never read another customer's address.
 */
@Component
public class CustomerClient {

    static final String ADDRESS_NOT_FOUND = "ADDRESS_NOT_FOUND";
    private static final ParameterizedTypeReference<List<CustomerAddress>> ADDRESS_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final Downstream downstream;
    private final RestClient client;

    public CustomerClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getCustomer());
    }

    /** One of the caller's addresses; NotFound when it does not exist or belongs to someone else. */
    public Outcome<CustomerAddress> address(CallContext context, UUID addressId) {
        return downstream.call(Dependency.CUSTOMER, ADDRESS_NOT_FOUND, () -> client.get()
                .uri("/api/customers/me/addresses/{id}", addressId)
                .headers(context::applyTo)
                .retrieve()
                .body(CustomerAddress.class));
    }

    /** All the caller's addresses (default first). */
    public Outcome<List<CustomerAddress>> addresses(CallContext context) {
        return downstream.call(Dependency.CUSTOMER, null, () -> client.get()
                .uri("/api/customers/me/addresses")
                .headers(context::applyTo)
                .retrieve()
                .body(ADDRESS_LIST));
    }
}
