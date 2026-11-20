package com.grocery.vendor.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The public store directory (docs/api/phase-3-catalog-and-search.md §9): what a guest may see about an APPROVED
 * grocery store. Never GSTIN, FSSAI, contact e-mail / phone, owner or status.
 */
public final class StoreDirectoryDtos {

    private StoreDirectoryDtos() {
    }

    /**
     * One store. {@code name} = business name, {@code area} = address line 2 (may be null). {@code distanceKm} = road
     * distance from the caller's point (haversine × road factor, 1 decimal HALF_UP — the cart's formula) and
     * {@code deliversHere} = {@code distanceKm ≤ deliveryLimitKm}; both null when no point was given.
     */
    public record StoreCard(UUID id, String name, String line1, String area, String city, String postalCode,
                            Double latitude, Double longitude, BigDecimal distanceKm, Boolean deliversHere) {
    }

    /** GET /api/stores/nearby: the stores delivering to the point, nearest first (at most {@code max-results}). */
    public record NearbyStores(double deliveryLimitKm, List<StoreCard> content) {
    }
}
