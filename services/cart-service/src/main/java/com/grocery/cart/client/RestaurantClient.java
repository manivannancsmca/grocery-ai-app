package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.MenuItemIds;
import com.grocery.cart.client.DownstreamDtos.MenuQuote;
import com.grocery.cart.config.CartProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * restaurant-service: {@code POST /internal/restaurants/{restaurantId}/menu-quote}, status, open now, accepting
 * orders, location and the live price and availability of the requested menu items.
 */
@Component
public class RestaurantClient {

    static final String RESTAURANT_NOT_FOUND = "RESTAURANT_NOT_FOUND";

    private final Downstream downstream;
    private final RestClient client;

    public RestaurantClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getRestaurant());
    }

    public Outcome<MenuQuote> menuQuote(CallContext context, UUID restaurantId, Collection<UUID> itemIds) {
        return downstream.call(Dependency.RESTAURANT, RESTAURANT_NOT_FOUND, () -> client.post()
                .uri("/internal/restaurants/{restaurantId}/menu-quote", restaurantId)
                .headers(context::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MenuItemIds(List.copyOf(itemIds)))
                .retrieve()
                .body(MenuQuote.class));
    }
}
