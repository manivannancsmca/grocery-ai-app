package com.grocery.pricing.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code pricing.events} (v1, docs/api/phase-4-inventory-and-pricing.md §8), aggregate type
 * {@code Price}, key = productId. Money is a JSON number with 2 decimals.
 * Consumers: catalog-query ({@code catalog-query.price-indexer}) for PriceChanged; notification later.
 */
public final class PricingEvents {

    public static final String TOPIC = "pricing.events";
    public static final String AGGREGATE = "Price";

    public static final String PRICE_CHANGED = "PriceChanged";
    public static final String PRICE_CHANGE_REQUESTED = "PriceChangeRequested";
    public static final String PRICE_CHANGE_APPROVED = "PriceChangeApproved";
    public static final String PRICE_CHANGE_REJECTED = "PriceChangeRejected";
    public static final String PRICE_CHANGE_CANCELLED = "PriceChangeCancelled";

    private PricingEvents() {
    }

    /** Every change of the live price (priceVersion + 1), and on republish (same priceVersion). */
    public record PriceChanged(UUID productId, UUID vendorId, BigDecimal mrp, BigDecimal sellingPrice,
                               String currency, long priceVersion, Instant changedAt) {
    }

    public record PriceChangeRequested(UUID requestId, UUID productId, UUID vendorId, String productName,
                                       BigDecimal oldMrp, BigDecimal oldSellingPrice, BigDecimal newMrp,
                                       BigDecimal newSellingPrice, BigDecimal changePercent, UUID requestedBy) {
    }

    public record PriceChangeApproved(UUID requestId, UUID productId, UUID vendorId, UUID approvedBy) {
    }

    public record PriceChangeRejected(UUID requestId, UUID productId, UUID vendorId, UUID rejectedBy, String reason) {
    }

    /** {@code cancelledBy} null = automatic (the product was archived). */
    public record PriceChangeCancelled(UUID requestId, UUID productId, UUID vendorId, UUID cancelledBy, String reason) {
    }
}
