package com.grocery.vendor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Supplier, purchase order and goods receipt bodies (docs/api/phase-12-post-mvp-features.md §4). */
public final class PurchasingDtos {

    public static final int MAX_LINES = 100;
    public static final int MAX_QUANTITY = 100_000;

    private PurchasingDtos() {
    }

    // ---- suppliers ----------------------------------------------------------------------------------------

    /**
     * {@code POST /api/vendor/suppliers} and {@code PUT …/{id}}. Blank optional fields are stored as null;
     * {@code active} null = active on create, unchanged on update ({@code false} on update = deactivate, refused
     * while the supplier has open purchase orders).
     */
    public record SupplierRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 100) String contactName,
            @Pattern(regexp = VendorDtos.PHONE + "|^$", message = "must be 10-15 digits, optionally starting with +")
            String phone,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = VendorDtos.GSTIN + "|^$", message = "must be a valid 15-character GSTIN") String gstin,
            @Size(max = 300) String address,
            @Min(0) @Max(90) Integer leadTimeDays,
            @Size(max = 500) String notes,
            Boolean active) {
    }

    public record SupplierView(UUID id, String name, String contactName, String phone, String email, String gstin,
                               String address, Integer leadTimeDays, String notes, boolean active, Instant createdAt,
                               Instant updatedAt) {
    }

    // ---- purchase orders ----------------------------------------------------------------------------------

    /**
     * {@code POST /api/vendor/purchase-orders} and {@code PUT …/{id}} (DRAFT only; replaces everything).
     * {@code expectedAt}: an ISO-8601 instant ({@code 2026-11-20T04:30:00Z}) or a date ({@code 2026-11-20} = that day
     * 00:00 India time); optional. Each product at most once.
     */
    public record PurchaseOrderRequest(
            @NotNull UUID supplierId,
            String expectedAt,
            @Size(max = 500) String notes,
            @NotNull @Size(min = 1, max = MAX_LINES) List<@NotNull @Valid LineRequest> lines) {
    }

    /** {@code quantity} is accepted as an alias of {@code orderedQuantity} (the vendor assistant's tool shape). */
    public record LineRequest(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(MAX_QUANTITY) @JsonAlias("quantity") Integer orderedQuantity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitCost) {
    }

    /** {@code POST …/{id}/receive} (with {@code Idempotency-Key}): units received now, each product at most once. */
    public record ReceiveRequest(
            @NotNull @Size(min = 1, max = MAX_LINES) List<@NotNull @Valid ReceiveLine> lines,
            @Size(max = 300) String note) {
    }

    public record ReceiveLine(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(MAX_QUANTITY) Integer quantity) {
    }

    public record PurchaseOrderView(UUID id, String number, UUID vendorId, UUID supplierId, String supplierName,
                                    String status, Instant expectedAt, String notes, String currency,
                                    List<LineView> lines, int orderedUnits, int receivedUnits, BigDecimal totalCost,
                                    BigDecimal receivedCost, List<ReceiptView> receipts, Instant createdAt,
                                    Instant updatedAt, Instant sentAt, Instant cancelledAt, Instant closedAt,
                                    Instant fullyReceivedAt) {
    }

    /** {@code openQuantity} = ordered − received; {@code lineTotal} = ordered × unit cost. */
    public record LineView(UUID productId, String productName, int orderedQuantity, int receivedQuantity,
                           int openQuantity, BigDecimal unitCost, BigDecimal lineTotal) {
    }

    public record ReceiptView(UUID id, Instant receivedAt, UUID receivedBy, String note, List<ReceiptLineView> lines,
                              int units, BigDecimal totalCost) {
    }

    public record ReceiptLineView(UUID productId, String productName, int quantity, BigDecimal unitCost,
                                  BigDecimal lineCost) {
    }
}
