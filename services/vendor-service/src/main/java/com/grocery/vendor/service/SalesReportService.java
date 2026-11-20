package com.grocery.vendor.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.vendor.config.FulfillmentProperties;
import com.grocery.vendor.dto.StoreOrderDtos.DailySales;
import com.grocery.vendor.dto.StoreOrderDtos.SalesReport;
import com.grocery.vendor.dto.StoreOrderDtos.TopItem;
import com.grocery.vendor.entity.StoreOrderStatus;
import com.grocery.vendor.entity.VendorOrder;
import com.grocery.vendor.repository.ReportRow;
import com.grocery.vendor.repository.VendorOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The vendor's sales report (contract §3). {@code from} / {@code to} are dates in the report time zone
 * ({@code grocery.fulfillment.timezone}, India time), inclusive; {@code to} defaults to today and {@code from} to six
 * days before {@code to} (the last 7 days including today); at most 92 days. Orders count on the day they were
 * received ({@code receivedAt} in that zone).
 * <ul>
 *   <li>{@code ordersReceived}: every store order received in the range; {@code ordersAccepted}: those that reached
 *       ACCEPTED or later ({@code acceptedAt} set, also when cancelled afterwards); {@code ordersRejected}: REJECTED, of
 *       which {@code autoRejected} at the deadline; {@code ordersCancelled}: CANCELLED (before or after acceptance).</li>
 *   <li>Sold orders = accepted and not cancelled: {@code grossSales} = sum of their subtotals (the delivery fee is not
 *       the store's), {@code itemsSold} = their units, {@code averageOrderValue} = grossSales / their number.</li>
 *   <li>{@code acceptanceRate} and {@code averageOrderValue} have 2 decimals (half up), 0.00 when there is nothing to
 *       divide by. {@code daily} lists every day of the range, zeros included. {@code topItems}: the 5 items with the
 *       most units in the sold orders (ties: higher sales, then item id).</li>
 * </ul>
 */
@Service
public class SalesReportService {

    static final int MAX_DAYS = 92;
    static final int DEFAULT_DAYS = 7;
    static final int TOP_ITEMS = 5;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final VendorOrderRepository orders;
    private final FulfillmentProperties properties;
    private final Clock clock;

    public SalesReportService(VendorOrderRepository orders, FulfillmentProperties properties, Clock clock) {
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SalesReport sales(CurrentUser user, String fromParameter, String toParameter) {
        UUID vendorId = user.requireVendorId();
        ZoneId zone = properties.zone();
        LocalDate to = toParameter == null || toParameter.isBlank()
                ? LocalDate.now(clock.withZone(zone)) : date("to", toParameter);
        LocalDate from = fromParameter == null || fromParameter.isBlank()
                ? to.minusDays(DEFAULT_DAYS - 1) : date("from", fromParameter);
        if (from.isAfter(to)) {
            throw new BadRequestException("INVALID_PARAMETER", "'from' (" + from + ") must not be after 'to' (" + to + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "The report covers at most " + MAX_DAYS + " days (asked: " + days + ")");
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, Day> daily = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            daily.put(day, new Day());
        }
        Day total = new Day();
        long rejected = 0;
        long autoRejected = 0;
        long cancelled = 0;
        for (ReportRow row : orders.reportRows(vendorId, start, end)) {
            Day day = daily.get(LocalDate.ofInstant(row.receivedAt(), zone));
            boolean accepted = row.acceptedAt() != null;
            boolean sold = accepted && row.status() != StoreOrderStatus.CANCELLED;
            total.add(accepted, sold, row);
            day.add(accepted, sold, row);
            if (row.status() == StoreOrderStatus.REJECTED) {
                rejected++;
                if (Boolean.TRUE.equals(row.rejectionAuto())) {
                    autoRejected++;
                }
            } else if (row.status() == StoreOrderStatus.CANCELLED) {
                cancelled++;
            }
        }

        List<DailySales> dailySales = new ArrayList<>(daily.size());
        daily.forEach((date, day) -> dailySales.add(new DailySales(date, day.received, day.accepted, day.grossSales)));
        List<TopItem> topItems = orders.topItems(vendorId.toString(), start, end, TOP_ITEMS).stream()
                .map(row -> new TopItem(UUID.fromString((String) row[0]), (String) row[3],
                        ((Number) row[1]).longValue(), money(row[2])))
                .toList();
        return new SalesReport(from, to, zone.getId(), VendorOrder.CURRENCY, total.received, total.accepted, rejected,
                autoRejected, cancelled, ratio(total.accepted, total.received), total.grossSales, total.itemsSold,
                total.sold == 0 ? ZERO : total.grossSales.divide(BigDecimal.valueOf(total.sold), 2, RoundingMode.HALF_UP),
                dailySales, topItems);
    }

    private static LocalDate date(String parameter, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "Invalid " + parameter + " '" + value + "' (expected a date like 2026-11-05)");
        }
    }

    private static BigDecimal ratio(long part, long whole) {
        return whole == 0 ? ZERO : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Object value) {
        BigDecimal amount = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Running totals of one day (or of the whole range). */
    private static final class Day {
        long received;
        long accepted;
        long sold;
        long itemsSold;
        BigDecimal grossSales = ZERO;

        void add(boolean isAccepted, boolean isSold, ReportRow row) {
            received++;
            if (isAccepted) {
                accepted++;
            }
            if (isSold) {
                sold++;
                itemsSold += row.itemCount();
                grossSales = grossSales.add(row.subtotal());
            }
        }
    }
}
