package com.grocery.inventory.service;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A reservation could not be made because at least one product lacks stock (or is unknown / archived, which
 * counts as 0 available). Nothing was changed. Phase 6 turns this into {@code StockReservationFailed}.
 */
public class ReservationFailedException extends ApiException {

    /** One product that cannot be reserved: how much was asked for and how much is available. */
    public record UnavailableItem(UUID productId, int requested, int available) {
    }

    private final UUID orderId;
    private final List<UnavailableItem> unavailableItems;

    public ReservationFailedException(UUID orderId, List<UnavailableItem> unavailableItems) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Not enough stock for order " + orderId + ": " + unavailableItems.size() + " item(s) unavailable",
                Map.of("unavailableItems", unavailableItems.stream()
                        .map(i -> Map.of("productId", i.productId().toString(), "requested", i.requested(),
                                "available", i.available()))
                        .toList()));
        this.orderId = orderId;
        this.unavailableItems = List.copyOf(unavailableItems);
    }

    public UUID orderId() {
        return orderId;
    }

    public List<UnavailableItem> unavailableItems() {
        return unavailableItems;
    }
}
