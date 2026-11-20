package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.Availability;
import com.grocery.cart.client.DownstreamDtos.ProductIds;
import com.grocery.cart.config.CartProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** inventory-service: {@code POST /internal/inventory/availability}, live available stock. */
@Component
public class InventoryClient {

    private final Downstream downstream;
    private final RestClient client;

    public InventoryClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getInventory());
    }

    public Outcome<Availability> availability(CallContext context, Collection<UUID> productIds) {
        return downstream.call(Dependency.INVENTORY, null, () -> client.post()
                .uri("/internal/inventory/availability")
                .headers(context::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProductIds(List.copyOf(productIds)))
                .retrieve()
                .body(Availability.class));
    }
}
