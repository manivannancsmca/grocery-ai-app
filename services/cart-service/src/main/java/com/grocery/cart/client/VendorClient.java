package com.grocery.cart.client;

import com.grocery.cart.client.DownstreamDtos.VendorStore;
import com.grocery.cart.config.CartProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/** vendor-service: {@code GET /internal/vendors/{vendorId}/store}, active flag, name and location. */
@Component
public class VendorClient {

    static final String VENDOR_NOT_FOUND = "VENDOR_NOT_FOUND";

    private final Downstream downstream;
    private final RestClient client;

    public VendorClient(Downstream downstream, CartProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getVendor());
    }

    public Outcome<VendorStore> store(CallContext context, UUID vendorId) {
        return downstream.call(Dependency.VENDOR, VENDOR_NOT_FOUND, () -> client.get()
                .uri("/internal/vendors/{vendorId}/store", vendorId)
                .headers(context::applyTo)
                .retrieve()
                .body(VendorStore.class));
    }
}
