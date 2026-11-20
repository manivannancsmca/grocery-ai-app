package com.grocery.reporting.report;

import com.grocery.common.web.BadRequestException;
import com.grocery.reporting.report.ReportDtos.CouponDiscounts;
import com.grocery.reporting.report.ReportDtos.CouponRow;
import com.grocery.reporting.report.ReportDtos.DeliveryDay;
import com.grocery.reporting.report.ReportDtos.Overview;
import com.grocery.reporting.report.ReportDtos.ProductRow;
import com.grocery.reporting.report.ReportDtos.PurchasingRow;
import com.grocery.reporting.report.ReportDtos.RatedTarget;
import com.grocery.reporting.report.ReportDtos.Ratings;
import com.grocery.reporting.report.ReportDtos.SalesDay;
import com.grocery.reporting.report.ReportDtos.StoreRow;
import com.grocery.reporting.report.ReportRepository.CountAndAmount;
import com.grocery.reporting.report.ReportRepository.DeliveryAggregate;
import com.grocery.reporting.report.ReportRepository.OrderTotals;
import com.grocery.reporting.report.ReportRepository.ProductAggregate;
import com.grocery.reporting.report.ReportRepository.RatingAggregate;
import com.grocery.reporting.report.ReportRepository.ReviewCounts;
import com.grocery.reporting.report.ReportRepository.SalesAggregate;
import com.grocery.reporting.report.ReportRepository.StoreAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admin reports of contract §7, computed from the facts at request time (each one in a single read-only
 * REPEATABLE READ transaction, so all its queries see the same snapshot). Parameters are validated here: unknown
 * values → 400 {@code INVALID_PARAMETER}.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;
    static final Set<String> ORDER_TYPES = Set.of("GROCERY", "RESTAURANT");
    static final Set<String> TARGET_TYPES = Set.of("PRODUCT", "MENU_ITEM", "VENDOR", "RESTAURANT", "DELIVERY_PARTNER");

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MICROS_PER_MINUTE = BigDecimal.valueOf(60_000_000L);

    private final ReportRepository reports;

    public ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    public Overview overview(ReportRange range) {
        OrderTotals orders = reports.orderTotals(range.start(), range.end());
        CountAndAmount refunds = reports.refundTotals(range.start(), range.end());
        CountAndAmount returns = reports.returnTotals(range.start(), range.end());
        long newCustomers = reports.newCustomers(range.start(), range.end());
        return new Overview(range.from(), range.to(), ReportRange.ZONE.getId(), ReportDtos.CURRENCY,
                orders.gmv(), orders.gmv().subtract(refunds.amount()), orders.confirmed(),
                average(orders.gmv(), orders.confirmed()), rate(orders.cancelled(), orders.placed()),
                refunds.amount(),
                new CouponDiscounts(orders.platformFunded(), orders.storeFunded(),
                        orders.discount().add(orders.deliveryFeeDiscount()),
                        orders.discount(), orders.deliveryFeeDiscount()),
                newCustomers,
                orders.placed(), orders.cancelled(), orders.rejected(), refunds.count(), orders.couponOrders(),
                returns.count(), returns.amount());
    }

    /** Every date of the range, zeros included. */
    public List<SalesDay> dailySales(ReportRange range, String typeParameter, UUID storeId) {
        String type = orderType(typeParameter);
        Map<LocalDate, SalesAggregate> sales = reports.dailySales(range.start(), range.end(), range.offsetMinutes(),
                type, storeId);
        Map<LocalDate, BigDecimal> refunds = reports.dailyRefunds(range.start(), range.end(), range.offsetMinutes(),
                type, storeId);
        List<SalesDay> days = new ArrayList<>();
        for (LocalDate date : range.dates()) {
            SalesAggregate day = sales.getOrDefault(date, new SalesAggregate(0, ZERO, ZERO));
            BigDecimal dayRefunds = refunds.getOrDefault(date, ZERO);
            days.add(new SalesDay(date, day.orders(), day.gmv(), day.discounts(), dayRefunds,
                    day.gmv().subtract(dayRefunds)));
        }
        return days;
    }

    public List<StoreRow> topStores(ReportRange range, String metricParameter, String typeParameter,
                                    Integer limitParameter) {
        String metric = choice("metric", metricParameter, "gmv", Set.of("gmv", "orders"));
        String type = orderType(typeParameter);
        int limit = limit(limitParameter);
        List<StoreRow> stores = new ArrayList<>();
        for (StoreAggregate row : reports.topStores(range.start(), range.end(), type, metric.equals("orders"),
                limit)) {
            stores.add(new StoreRow(stores.size() + 1, row.storeId(), row.storeName(), row.orderType(), row.orders(),
                    row.gmv(), average(row.gmv(), row.orders())));
        }
        return stores;
    }

    public List<ProductRow> topProducts(ReportRange range, String metricParameter, String typeParameter,
                                        Integer limitParameter) {
        String metric = choice("metric", metricParameter, "units", Set.of("units", "revenue"));
        String type = orderType(typeParameter);
        int limit = limit(limitParameter);
        List<ProductAggregate> rows = reports.topProducts(range.start(), range.end(), type,
                metric.equals("revenue"), limit);
        Map<UUID, Long> returned = reports.returnedUnits(range.start(), range.end(),
                rows.stream().map(ProductAggregate::itemId).toList());
        List<ProductRow> products = new ArrayList<>(rows.size());
        for (ProductAggregate row : rows) {
            products.add(new ProductRow(products.size() + 1, row.itemId(), row.itemType(), row.name(), row.storeId(),
                    row.storeName(), row.units(), row.revenue(), row.orders(),
                    returned.getOrDefault(row.itemId(), 0L)));
        }
        return products;
    }

    /** Every date of the range, zeros (and null averages) included. */
    public List<DeliveryDay> deliveryPerformance(ReportRange range) {
        Map<LocalDate, DeliveryAggregate> deliveries = reports.deliveryDays(range.start(), range.end(),
                range.offsetMinutes());
        Map<LocalDate, Long> failed = reports.failedDeliveries(range.start(), range.end(), range.offsetMinutes());
        List<DeliveryDay> days = new ArrayList<>();
        for (LocalDate date : range.dates()) {
            DeliveryAggregate day = deliveries.getOrDefault(date, new DeliveryAggregate(0, null, null, null));
            days.add(new DeliveryDay(date, day.deliveries(), minutes(day.confirmedToAssigned()),
                    minutes(day.assignedToPickedUp()), minutes(day.pickedUpToDelivered()),
                    failed.getOrDefault(date, 0L)));
        }
        return days;
    }

    public List<CouponRow> promotions(ReportRange range) {
        return reports.coupons(range.start(), range.end()).stream()
                .map(row -> new CouponRow(row.couponCode(), row.couponName(), row.orders(),
                        row.discount().add(row.deliveryFeeDiscount()), row.gmv(), row.fundedBy(), row.discount(),
                        row.deliveryFeeDiscount(), row.stores()))
                .toList();
    }

    public Ratings ratings(ReportRange range, String targetTypeParameter, Integer limitParameter,
                           Integer minCountParameter) {
        String targetType = targetTypeParameter == null || targetTypeParameter.isBlank() ? null
                : choice("targetType", targetTypeParameter.toUpperCase(Locale.ROOT), null, TARGET_TYPES);
        int limit = limit(limitParameter);
        int minCount = minCountParameter == null ? 1 : minCountParameter;
        if (minCount < 1) {
            throw new BadRequestException("INVALID_PARAMETER", "minCount must be at least 1 (was " + minCount + ")");
        }
        ReviewCounts reviews = reports.reviewCounts(range.start(), range.end(), targetType);
        return new Ratings(range.from(), range.to(), ReportRange.ZONE.getId(), targetType, limit, minCount,
                reviews.submitted(), reviews.published(), reviews.flagged(),
                ranked(reports.ratings(targetType, minCount, true, limit)),
                ranked(reports.ratings(targetType, minCount, false, limit)));
    }

    /** Per vendor and India date, in date order; only days with receipts. */
    public List<PurchasingRow> purchasing(ReportRange range, UUID vendorId) {
        return reports.purchasing(range.start(), range.end(), range.offsetMinutes(), vendorId).stream()
                .map(row -> new PurchasingRow(row.vendorId(), row.vendorName(), row.date(), row.units(), row.cost(),
                        row.receipts(), row.purchaseOrders()))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private static List<RatedTarget> ranked(List<RatingAggregate> rows) {
        List<RatedTarget> ranked = new ArrayList<>(rows.size());
        for (RatingAggregate row : rows) {
            ranked.add(new RatedTarget(ranked.size() + 1, row.targetType(), row.targetId(), row.name(),
                    row.averageRating().setScale(2, RoundingMode.HALF_UP), row.ratingCount(), row.distribution(),
                    row.updatedAt()));
        }
        return ranked;
    }

    /** {@code part / whole}, 4 decimals (0.0000 when there is nothing to divide by). */
    static BigDecimal rate(long part, long whole) {
        return whole == 0 ? new BigDecimal("0.0000")
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    /** {@code amount / count}, 2 decimals (0.00 when there is nothing to divide by). */
    static BigDecimal average(BigDecimal amount, long count) {
        return count == 0 ? ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** Microseconds → minutes, 1 decimal; {@code null} stays {@code null} (nothing measured). */
    static BigDecimal minutes(BigDecimal micros) {
        return micros == null ? null : micros.divide(MICROS_PER_MINUTE, 1, RoundingMode.HALF_UP);
    }

    private static String orderType(String value) {
        return value == null || value.isBlank() ? null
                : choice("type", value.toUpperCase(Locale.ROOT), null, ORDER_TYPES);
    }

    private static String choice(String parameter, String value, String defaultValue, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.strip();
        if (allowed.contains(normalized)) {
            return normalized;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (allowed.contains(lower)) {
            return lower;
        }
        throw new BadRequestException("INVALID_PARAMETER", "Unknown " + parameter + " '" + value + "' (one of: "
                + String.join(", ", allowed.stream().sorted().toList()) + ")");
    }

    private static int limit(Integer value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "limit must be between 1 and " + MAX_LIMIT + " (was " + value + ")");
        }
        return value;
    }
}
