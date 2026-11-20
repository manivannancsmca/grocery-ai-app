package com.grocery.pricing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pricing API bodies (docs/api/phase-4-inventory-and-pricing.md §3–§5). Money is a JSON number with
 * 2 decimals (INR).
 */
public final class PriceDtos {

    public static final String APPLIED = "APPLIED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";

    private PriceDtos() {
    }

    /** PUT /api/vendor/prices/{productId}. {@code sellingPrice > mrp} → 422 SELLING_PRICE_ABOVE_MRP. */
    public record PriceRequest(
            @NotNull
            @DecimalMin("1.00")
            @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal mrp,
            @NotNull
            @DecimalMin("1.00")
            @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal sellingPrice,
            @Size(max = 300) String note) {
    }

    /** The live price as shown to vendors. */
    public record LivePrice(BigDecimal mrp, BigDecimal sellingPrice, String currency, int discountPercent,
                            Instant updatedAt) {
    }

    /** {@code price} null = not priced yet; {@code outcome} only in the PUT response. */
    public record PriceView(UUID productId, String productName, String unitLabel, String sku, String productStatus,
                            LivePrice price, PriceChangeRequestView pendingRequest,
                            BigDecimal approvalThresholdPercent,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String outcome) {

        public PriceView withOutcome(String newOutcome) {
            return new PriceView(productId, productName, unitLabel, sku, productStatus, price, pendingRequest,
                    approvalThresholdPercent, newOutcome);
        }
    }

    public record PriceSummary(long products, long priced, long unpriced, long pendingApproval) {
    }

    public record PriceHistoryEntry(UUID id, BigDecimal oldMrp, BigDecimal oldSellingPrice, BigDecimal newMrp,
                                    BigDecimal newSellingPrice, BigDecimal changePercent, String source,
                                    UUID requestId, UUID changedBy, Instant changedAt) {
    }

    /** {@code vendorName} only in admin responses (when known). */
    public record PriceChangeRequestView(UUID id, UUID productId, String productName, String unitLabel, UUID vendorId,
                                         @JsonInclude(JsonInclude.Include.NON_NULL) String vendorName,
                                         BigDecimal currentMrp, BigDecimal currentSellingPrice,
                                         BigDecimal requestedMrp, BigDecimal requestedSellingPrice,
                                         BigDecimal changePercent, String status, String note, UUID requestedBy,
                                         Instant requestedAt, UUID decidedBy, Instant decidedAt,
                                         String decisionReason) {
    }

    /** POST …/approve (optional body). */
    public record ApproveRequest(@Size(max = 300) String note) {
    }

    /** POST …/reject. */
    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /** POST /api/admin/pricing/republish response. */
    public record RepublishResult(int prices) {
    }

    /** POST /internal/prices/quote (Phase 5 contract §4; was /api/prices/quote in Phase 4). */
    public record QuoteRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> productIds) {
    }

    public record QuoteItem(UUID productId, UUID vendorId, BigDecimal mrp, BigDecimal sellingPrice, String currency,
                            long priceVersion) {
    }

    /** {@code missing} = requested products without a live price, unknown or archived (request order). */
    public record QuoteResponse(List<QuoteItem> items, List<UUID> missing) {
    }
}
