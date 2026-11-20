package com.grocery.vendor.controller;

import com.grocery.vendor.dto.VendorDtos.StoreView;
import com.grocery.vendor.service.VendorStoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoints (docs/api/phase-5-cart-and-checkout-preview.md §1, §4). {@code /internal/**} is never
 * routed by the gateway, but the caller's JWT is still validated here (zero trust). Any authenticated caller —
 * cart-service forwards the customer's own token, so no role is required.
 */
@RestController
@RequestMapping("/internal/vendors")
public class InternalVendorController {

    private final VendorStoreService stores;

    public InternalVendorController(VendorStoreService stores) {
        this.stores = stores;
    }

    /** The vendor's store: name, status, {@code active} (APPROVED), city and coordinates · 404 VENDOR_NOT_FOUND. */
    @GetMapping("/{vendorId}/store")
    public StoreView store(@PathVariable UUID vendorId) {
        return stores.store(vendorId);
    }
}
