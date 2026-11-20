package com.grocery.reporting.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The JSON bodies of {@code /api/admin/reports/**} (contract §7, shapes agreed with the admin Reports page). The
 * overview and the ratings are objects; every other report is a plain JSON array. Money is INR with 2 decimals,
 * rates are fractions 0–1 with 4 decimals, average minutes have 1 decimal ({@code null} when nothing was measured).
 *
 * <p>Definitions (README.md "Report definitions"): an order counts on the India date it was <b>placed</b>; a
 * <b>confirmed</b> order is one that was paid (OrderConfirmed), even if it was cancelled later; GMV = the
 * {@code total} the customer paid for confirmed orders (items + delivery fee − coupon discounts); refunds count on the
 * date they completed and exclude {@code LATE_CAPTURE} refunds (money for orders that were never confirmed).
 */
public final class ReportDtos {

    public static final String CURRENCY = "INR";

    private ReportDtos() {
    }

    // ---------------------------------------------------------------- overview

    /** {@code orders} = confirmed orders; {@code cancellationRate} = cancelled / placed. */
    public record Overview(LocalDate from, LocalDate to, String timezone, String currency,
                           BigDecimal grossSales, BigDecimal netSales, long orders, BigDecimal averageOrderValue,
                           BigDecimal cancellationRate, BigDecimal refunds, CouponDiscounts couponDiscounts,
                           long newCustomers,
                           long placedOrders, long cancelledOrders, long rejectedOrders, long refundCount,
                           long couponOrders, long returnsApproved, BigDecimal returnedAmount) {
    }

    /**
     * Coupon discounts of the confirmed orders: {@code total} = {@code items} (on the item subtotal) +
     * {@code deliveryFee} (FREE_DELIVERY). {@code platform} / {@code store}: the same discounts by who funds the
     * coupon (its scope in pricing-service, carried by the order events since schemas 1.7.0). Orders placed before
     * that have no funding and are in neither, so {@code platform + store} can be less than {@code total}.
     */
    public record CouponDiscounts(BigDecimal platform, BigDecimal store, BigDecimal total, BigDecimal items,
                                  BigDecimal deliveryFee) {
    }

    // ---------------------------------------------------------------- daily sales (array)

    public record SalesDay(LocalDate date, long orders, BigDecimal gmv, BigDecimal discounts, BigDecimal refunds,
                           BigDecimal net) {
    }

    // ---------------------------------------------------------------- top stores / products (arrays)

    /** {@code storeType}: GROCERY (a vendor) or RESTAURANT. */
    public record StoreRow(int rank, UUID storeId, String storeName, String storeType, long orders, BigDecimal gmv,
                           BigDecimal averageOrderValue) {
    }

    /** {@code revenue} = the line totals (before the coupon discount); {@code returnedUnits} of those orders. */
    public record ProductRow(int rank, UUID itemId, String itemType, String name, UUID storeId, String storeName,
                             long units, BigDecimal revenue, long orders, long returnedUnits) {
    }

    // ---------------------------------------------------------------- delivery performance (array)

    public record DeliveryDay(LocalDate date, long deliveries, BigDecimal avgConfirmToAssignedMinutes,
                              BigDecimal avgAssignedToPickedUpMinutes, BigDecimal avgPickedUpToDeliveredMinutes,
                              long failed) {
    }

    // ---------------------------------------------------------------- promotions (array)

    /**
     * {@code discountGiven} = {@code discount} (items) + {@code deliveryFeeDiscount}; {@code gmv} = the totals of
     * those orders; {@code stores} = how many stores it was used at. {@code couponName} and {@code fundedBy}
     * (PLATFORM / STORE) are those of the coupon's newest order in the range that carries them (schemas 1.7.0);
     * {@code null} when all its orders were placed before that.
     */
    public record CouponRow(String couponCode, String couponName, long orders, BigDecimal discountGiven,
                            BigDecimal gmv, String fundedBy, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                            long stores) {
    }

    // ---------------------------------------------------------------- ratings (object)

    /**
     * {@code lowest} / {@code highest}: the current published summaries (RatingSummaryUpdated) of targets with at
     * least {@code minCount} ratings — not limited to the range. {@code reviews*}: reviews first submitted in the range
     * (ReviewSubmitted), by their status at the latest submission.
     */
    public record Ratings(LocalDate from, LocalDate to, String timezone, String targetType, int limit, int minCount,
                          long reviewsSubmitted, long reviewsPublished, long reviewsFlagged,
                          List<RatedTarget> lowest, List<RatedTarget> highest) {
    }

    /**
     * {@code targetName}: the latest name seen in the orders and deliveries, {@code null} if never seen.
     * {@code distribution}: [1★, 2★, 3★, 4★, 5★].
     */
    public record RatedTarget(int rank, String targetType, UUID targetId, String targetName, BigDecimal averageRating,
                              long ratingCount, List<Integer> distribution, Instant updatedAt) {
    }

    // ---------------------------------------------------------------- purchasing (array)

    /** One vendor on one India date: goods receipts, distinct purchase orders, units and cost (Σ quantity × unitCost). */
    public record PurchasingRow(UUID vendorId, String vendorName, LocalDate date, long units, BigDecimal cost,
                                long receipts, long purchaseOrders) {
    }
}
