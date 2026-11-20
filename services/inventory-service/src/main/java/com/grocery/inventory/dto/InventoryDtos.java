package com.grocery.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vendor/admin inventory API bodies (docs/api/phase-4-inventory-and-pricing.md §2) and the internal availability
 * check (docs/api/phase-5-cart-and-checkout-preview.md §4). */
public final class InventoryDtos {

    public static final String TYPE = "^(INCREASE|DECREASE|SET)$";
    public static final String REASON = "^(RESTOCK|RETURN|DAMAGED|EXPIRED|STOCK_COUNT|CORRECTION|OTHER)$";
    public static final String TYPE_MESSAGE = "must be one of INCREASE, DECREASE, SET";
    public static final String REASON_MESSAGE =
            "must be one of RESTOCK, RETURN, DAMAGED, EXPIRED, STOCK_COUNT, CORRECTION, OTHER";

    private InventoryDtos() {
    }

    /** The fields shared by a single adjustment and a bulk item. */
    public interface Adjustment {
        String type();

        Integer quantity();

        String reason();

        String note();
    }

    /**
     * {@code quantity}: INCREASE/DECREASE 1–100000, SET 0–1000000. The type-dependent lower range is checked by
     * the service (400 VALIDATION_FAILED, field {@code quantity}).
     */
    public record AdjustmentRequest(
            @NotNull @Pattern(regexp = TYPE, message = TYPE_MESSAGE) String type,
            @NotNull @Min(0) @Max(1_000_000) Integer quantity,
            @NotNull @Pattern(regexp = REASON, message = REASON_MESSAGE) String reason,
            @Size(max = 300) String note) implements Adjustment {
    }

    public record BulkAdjustmentItem(
            @NotNull UUID productId,
            @NotNull @Pattern(regexp = TYPE, message = TYPE_MESSAGE) String type,
            @NotNull @Min(0) @Max(1_000_000) Integer quantity,
            @NotNull @Pattern(regexp = REASON, message = REASON_MESSAGE) String reason,
            @Size(max = 300) String note) implements Adjustment {
    }

    public record BulkAdjustmentRequest(
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid BulkAdjustmentItem> items) {
    }

    public record ThresholdRequest(@NotNull @Min(0) @Max(100_000) Integer lowStockThreshold) {
    }

    public record StockView(
            UUID productId,
            String productName,
            String unitLabel,
            String sku,
            String productStatus,
            int onHand,
            int reserved,
            int available,
            int lowStockThreshold,
            boolean lowStock,
            boolean inStock,
            Instant updatedAt) {
    }

    public record StockMovementView(
            UUID id,
            String type,
            int quantityDelta,
            int onHandAfter,
            int reservedAfter,
            String reason,
            String note,
            String reference,
            UUID actorUserId,
            Instant createdAt) {
    }

    /** Non-archived products; {@code lowStock} counts products that are low but not out of stock. */
    public record StockSummary(long products, long inStock, long lowStock, long outOfStock) {
    }

    public record RepublishResult(long products) {
    }

    // ---- internal (Phase 5 contract §4) ------------------------------------------------------------------------

    /** POST /internal/inventory/availability: 1–100 product ids; a product listed twice is answered once. */
    public record AvailabilityRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> productIds) {
    }

    /** Live stock of one product: {@code available = onHand − reserved}, {@code inStock = available > 0}. */
    public record AvailabilityItem(UUID productId, UUID vendorId, int available, boolean inStock) {
    }

    /** {@code items} and {@code missing} (unknown or archived products) both in request order. */
    public record AvailabilityResponse(List<AvailabilityItem> items, List<UUID> missing) {
    }
}
