package com.grocery.vendor.controller;

import com.grocery.vendor.dto.StoreDirectoryDtos.NearbyStores;
import com.grocery.vendor.dto.StoreDirectoryDtos.StoreCard;
import com.grocery.vendor.service.StoreDirectoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The public store directory (docs/api/phase-3-catalog-and-search.md §9). No token needed ({@code /api/stores/**} is a
 * public path here and a guest read at the gateway); a valid token is accepted. Only APPROVED stores are shown.
 * {@code lat} / {@code lng} are optional here so that a missing one is 400 INVALID_PARAMETER (service), like a
 * non-numeric one or a malformed id ({@link StoreOrderParameterAdvice}).
 */
@RestController
@RequestMapping("/api/stores")
public class StoreDirectoryController {

    private final StoreDirectoryService directory;

    public StoreDirectoryController(StoreDirectoryService directory) {
        this.directory = directory;
    }

    /** The stores delivering to the point, nearest first · 400 INVALID_PARAMETER without a valid lat and lng. */
    @GetMapping("/nearby")
    public NearbyStores nearby(@RequestParam(required = false) Double lat,
                               @RequestParam(required = false) Double lng) {
        return directory.nearby(lat, lng);
    }

    /** One store; with lat and lng also its distance and deliversHere · 404 STORE_NOT_FOUND unless APPROVED. */
    @GetMapping("/{id}")
    public StoreCard store(@PathVariable UUID id,
                           @RequestParam(required = false) Double lat,
                           @RequestParam(required = false) Double lng) {
        return directory.store(id, lat, lng);
    }
}
