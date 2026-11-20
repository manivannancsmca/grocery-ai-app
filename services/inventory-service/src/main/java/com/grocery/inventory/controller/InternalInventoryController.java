package com.grocery.inventory.controller;

import com.grocery.inventory.dto.InventoryDtos.AvailabilityRequest;
import com.grocery.inventory.dto.InventoryDtos.AvailabilityResponse;
import com.grocery.inventory.service.AvailabilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints (Phase 5 contract §1, §4). {@code /internal/**} is never routed by the gateway, but
 * the caller's JWT is still validated here (zero trust). Any authenticated caller — cart-service forwards the
 * customer's own token, so no role is required.
 */
@RestController
@RequestMapping("/internal/inventory")
public class InternalInventoryController {

    private final AvailabilityService availability;

    public InternalInventoryController(AvailabilityService availability) {
        this.availability = availability;
    }

    /** Live {@code available} / {@code inStock} of up to 100 products; unknown or archived → {@code missing}. */
    @PostMapping("/availability")
    public AvailabilityResponse availability(@Valid @RequestBody AvailabilityRequest request) {
        return availability.availability(request.productIds());
    }
}
