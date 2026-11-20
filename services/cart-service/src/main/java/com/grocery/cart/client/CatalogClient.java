package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.ProductIds;
import com.grocery.cart.client.DownstreamDtos.ProductLookup;
import com.grocery.cart.config.CartProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** catalog-query-service: {@code POST /internal/catalog/products/lookup}, only visible products (Phase 3 rule). */
@Component
public class CatalogClient {

    private final Downstream downstream;
    private final RestClient client;

    public CatalogClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getCatalogQuery());
    }

    public Outcome<ProductLookup> lookup(CallContext context, Collection<UUID> productIds) {
        return downstream.call(Dependency.CATALOG, null, () -> client.post()
                .uri("/internal/catalog/products/lookup")
                .headers(context::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProductIds(List.copyOf(productIds)))
                .retrieve()
                .body(ProductLookup.class));
    }
}
