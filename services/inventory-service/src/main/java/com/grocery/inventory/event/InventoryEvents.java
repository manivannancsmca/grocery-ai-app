package com.grocery.inventory.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code inventory.events} (v1):
 * <ul>
 *   <li>aggregate type {@code Stock}, key = productId (docs/api/phase-4-inventory-and-pricing.md §8):
 *       {@code StockAdjusted}, {@code ProductAvailabilityChanged}, {@code LowStockDetected}. Consumers:
 *       catalog-query ({@code catalog-query.stock-indexer}) for {@code ProductAvailabilityChanged}; audit /
 *       notification later for the others.</li>
 *   <li>aggregate type {@code Reservation}, key = orderId (docs/api/phase-6-orders-and-payments.md §6):
 *       {@code StockReserved}, {@code StockReservationFailed}, {@code StockReservationExpired},
 *       {@code StockReleased}, and (docs/api/phase-9-delivery.md §6) {@code StockCommitted}. Consumer:
 *       order-service ({@code order-service.inventory-events}); StockCommitted is informational (audit,
 *       notification).</li>
 * </ul>
 */
public final class InventoryEvents {

    public static final String TOPIC = "inventory.events";
    public static final String STOCK_AGGREGATE = "Stock";
    public static final String RESERVATION_AGGREGATE = "Reservation";

    public static final String STOCK_ADJUSTED = "StockAdjusted";
    public static final String PRODUCT_AVAILABILITY_CHANGED = "ProductAvailabilityChanged";
    public static final String LOW_STOCK_DETECTED = "LowStockDetected";

    public static final String STOCK_RESERVED = "StockReserved";
    public static final String STOCK_RESERVATION_FAILED = "StockReservationFailed";
    public static final String STOCK_RESERVATION_EXPIRED = "StockReservationExpired";
    public static final String STOCK_RELEASED = "StockReleased";
    public static final String STOCK_COMMITTED = "StockCommitted";

    private InventoryEvents() {
    }

    /** Every vendor adjustment (INCREASE / DECREASE / SET). */
    public record StockAdjusted(
            UUID productId,
            UUID vendorId,
            UUID movementId,
            String type,
            int delta,
            int onHand,
            int reserved,
            int available,
            String reason,
            UUID actorUserId,
            long stockVersion) {
    }

    /**
     * Only when a product goes in ↔ out of stock, and on republish. {@code stockVersion} is strictly increasing
     * per product; the read side applies an event only when its version is newer than the stored one.
     */
    public record ProductAvailabilityChanged(UUID productId, UUID vendorId, boolean inStock, long stockVersion) {
    }

    /** When available drops from above the threshold to the threshold or below (threshold &gt; 0). */
    public record LowStockDetected(UUID productId, UUID vendorId, String productName, int available, int threshold) {
    }

    // ---- reservations (Phase 6) ---------------------------------------------------------------------------

    /** All lines of a grocery order are held until {@code expiresAt}. One line per product (quantities added). */
    public record StockReserved(UUID orderId, UUID reservationId, List<Line> items, Instant expiresAt) {
    }

    public record Line(UUID productId, int quantity) {
    }

    /** Nothing was held: at least one product lacks stock (unknown or archived products count as 0 available). */
    public record StockReservationFailed(UUID orderId, List<UnavailableItem> unavailableItems) {
    }

    public record UnavailableItem(UUID productId, int requested, int available) {
    }

    /** The reservation outlived {@code expiresAt} unpaid and was released by the expiry poller. */
    public record StockReservationExpired(UUID orderId, UUID reservationId) {
    }

    /** Held stock was given back; {@code reason} = {@code ORDER_CANCELLED} | {@code RESERVATION_EXPIRED}. */
    public record StockReleased(UUID orderId, UUID reservationId, String reason) {
    }

    /**
     * Phase 9: the held stock of a grocery order left the store at pickup (removed from on hand and reserved,
     * reservation COMMITTED). One line per product.
     */
    public record StockCommitted(UUID orderId, UUID reservationId, List<Line> items, Instant committedAt) {
    }
}
