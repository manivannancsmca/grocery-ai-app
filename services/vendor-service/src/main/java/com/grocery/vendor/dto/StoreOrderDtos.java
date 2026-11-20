package com.grocery.vendor.dto;

import com.grocery.vendor.config.FulfillmentProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Store order and sales report bodies (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §2, §3). */
public final class StoreOrderDtos {

    /** The reasons a store user may give; NO_RESPONSE is only set by the auto-reject poller. */
    public static final String STORE_REJECTION_REASONS = "OUT_OF_STOCK|STORE_CLOSED|TOO_BUSY|OTHER";
    public static final int MAX_NOTE_LENGTH = 300;

    private StoreOrderDtos() {
    }

    /** {@code POST …/accept}; the body and {@code readyInMinutes} are optional (default per service). */
    public record AcceptRequest(
            @Min(value = FulfillmentProperties.MIN_READY_IN_MINUTES, message = "must be between 5 and 180")
            @Max(value = FulfillmentProperties.MAX_READY_IN_MINUTES, message = "must be between 5 and 180")
            Integer readyInMinutes) {
    }

    /** {@code POST …/reject}; {@code note} is required for OTHER (checked by the service). */
    public record RejectRequest(
            @NotNull
            @Pattern(regexp = STORE_REJECTION_REASONS, message = "must be one of OUT_OF_STOCK, STORE_CLOSED, TOO_BUSY, OTHER")
            String reason,
            @Size(max = MAX_NOTE_LENGTH) String note) {
    }

    public record StoreOrderView(UUID orderId, String orderNumber, UUID storeId, String status, List<ItemView> items,
                                 int itemCount, BigDecimal subtotal, BigDecimal total, String currency,
                                 CustomerView customer, String note, Instant receivedAt, Instant acceptBy,
                                 Instant acceptedAt, Integer readyInMinutes, Instant readyBy, Instant preparingAt,
                                 Instant readyAt, RejectionView rejection, CancellationView cancellation,
                                 List<TimelineEntry> timeline) {
    }

    public record ItemView(UUID itemId, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** The recipient's name and area only — never the phone or the full address. */
    public record CustomerView(String name, String area) {
    }

    public record RejectionView(String reason, String note, boolean auto, Instant at) {
    }

    public record CancellationView(String reason, String by, Instant at) {
    }

    public record TimelineEntry(String status, Instant at) {
    }

    public record SalesReport(LocalDate from, LocalDate to, String timezone, String currency, long ordersReceived,
                              long ordersAccepted, long ordersRejected, long autoRejected, long ordersCancelled,
                              BigDecimal acceptanceRate, BigDecimal grossSales, long itemsSold,
                              BigDecimal averageOrderValue, List<DailySales> daily, List<TopItem> topItems) {
    }

    public record DailySales(LocalDate date, long ordersReceived, long ordersAccepted, BigDecimal grossSales) {
    }

    public record TopItem(UUID itemId, String name, long quantity, BigDecimal sales) {
    }
}
