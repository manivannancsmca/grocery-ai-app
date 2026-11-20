package com.grocery.reporting.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.ExportTable;
import com.grocery.common.export.Exporter;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.BadRequestException;
import com.grocery.reporting.report.Csv;
import com.grocery.reporting.report.ReportCsv;
import com.grocery.reporting.report.ReportDtos.CouponRow;
import com.grocery.reporting.report.ReportDtos.DeliveryDay;
import com.grocery.reporting.report.ReportDtos.Overview;
import com.grocery.reporting.report.ReportDtos.ProductRow;
import com.grocery.reporting.report.ReportDtos.PurchasingRow;
import com.grocery.reporting.report.ReportDtos.Ratings;
import com.grocery.reporting.report.ReportDtos.SalesDay;
import com.grocery.reporting.report.ReportDtos.StoreRow;
import com.grocery.reporting.report.ReportExports;
import com.grocery.reporting.report.ReportExports.Figure;
import com.grocery.reporting.report.ReportExports.RatedLine;
import com.grocery.reporting.report.ReportRange;
import com.grocery.reporting.report.ReportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The admin reports (contract §7), for any admin role. Every endpoint takes {@code from} / {@code to} (India dates,
 * inclusive, at most 366 days; default the last 30 days ending today) and {@code format}:
 * <ul>
 *   <li>{@code json} (the default);</li>
 *   <li>{@code csv}: {@code text/csv; charset=UTF-8} with a header row of the JSON field names, as an attachment named
 *       {@code <report>_<from>_<to>.csv} ({@link ReportCsv});</li>
 *   <li>{@code xlsx} or {@code pdf}: the same rows for people — headings in words, real numbers and dates, the period
 *       and the filters on top — written by grocery-common's {@link Exporter} ({@link ReportExports}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize(Roles.ANY_ADMIN)
public class ReportController {

    /** How a report is answered as CSV and as an Excel / PDF file. */
    private record Formats<T, R>(String csvName, Function<T, String> csv, ExportTable<R> table,
                                 Function<T, List<? extends R>> rows) {
    }

    private static final Formats<Overview, Figure> OVERVIEW =
            new Formats<>("overview", ReportCsv::overview, ReportExports.OVERVIEW, ReportExports::figures);
    private static final Formats<List<SalesDay>, SalesDay> DAILY_SALES =
            new Formats<>("sales-daily", ReportCsv::dailySales, ReportExports.DAILY_SALES, rows -> rows);
    private static final Formats<List<StoreRow>, StoreRow> TOP_STORES =
            new Formats<>("stores-top", ReportCsv::topStores, ReportExports.TOP_STORES, rows -> rows);
    private static final Formats<List<ProductRow>, ProductRow> TOP_PRODUCTS =
            new Formats<>("products-top", ReportCsv::topProducts, ReportExports.TOP_PRODUCTS, rows -> rows);
    private static final Formats<List<DeliveryDay>, DeliveryDay> DELIVERY = new Formats<>("delivery-performance",
            ReportCsv::deliveryPerformance, ReportExports.DELIVERY_PERFORMANCE, rows -> rows);
    private static final Formats<List<CouponRow>, CouponRow> PROMOTIONS =
            new Formats<>("promotions", ReportCsv::promotions, ReportExports.PROMOTIONS, rows -> rows);
    private static final Formats<Ratings, RatedLine> RATINGS =
            new Formats<>("ratings", ReportCsv::ratings, ReportExports.RATINGS, ReportExports::ratedLines);
    private static final Formats<List<PurchasingRow>, PurchasingRow> PURCHASING =
            new Formats<>("purchasing", ReportCsv::purchasing, ReportExports.PURCHASING, rows -> rows);

    private final ReportService reports;
    private final Exporter exporter;
    private final Clock clock;

    public ReportController(ReportService reports, Exporter exporter, Clock clock) {
        this.reports = reports;
        this.exporter = exporter;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(CurrentUser user,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String format,
                                      HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.overview(range), OVERVIEW, user, response, filters(range));
    }

    @GetMapping("/sales/daily")
    public ResponseEntity<?> dailySales(CurrentUser user,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) UUID storeId,
                                        @RequestParam(required = false) String format,
                                        HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.dailySales(range, type, storeId), DAILY_SALES, user, response,
                filters(range, "Store type", type, "Store ID", storeId));
    }

    @GetMapping("/stores/top")
    public ResponseEntity<?> topStores(CurrentUser user,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String metric,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String format,
                                       HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.topStores(range, metric, type, limit), TOP_STORES, user, response,
                filters(range, "Ranked by", metric, "Store type", type, "Top", limit));
    }

    @GetMapping("/products/top")
    public ResponseEntity<?> topProducts(CurrentUser user,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String metric,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String format,
                                         HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.topProducts(range, metric, type, limit), TOP_PRODUCTS, user,
                response, filters(range, "Ranked by", metric, "Store type", type, "Top", limit));
    }

    @GetMapping("/delivery/performance")
    public ResponseEntity<?> deliveryPerformance(CurrentUser user,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) String format,
                                                 HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.deliveryPerformance(range), DELIVERY, user, response, filters(range));
    }

    @GetMapping("/promotions")
    public ResponseEntity<?> promotions(CurrentUser user,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String format,
                                        HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.promotions(range), PROMOTIONS, user, response, filters(range));
    }

    @GetMapping("/ratings")
    public ResponseEntity<?> ratings(CurrentUser user,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String targetType,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) Integer minCount,
                                     @RequestParam(required = false) String format,
                                     HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.ratings(range, targetType, limit, minCount), RATINGS, user, response,
                filters(range, "Rated", targetType, "Top", limit, "At least this many ratings", minCount));
    }

    @GetMapping("/purchasing")
    public ResponseEntity<?> purchasing(CurrentUser user,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) UUID vendorId,
                                        @RequestParam(required = false) String format,
                                        HttpServletResponse response) {
        String output = output(format);
        ReportRange range = ReportRange.of(from, to, clock);
        return respond(output, range, reports.purchasing(range, vendorId), PURCHASING, user, response,
                filters(range, "Vendor ID", vendorId));
    }

    // ---------------------------------------------------------------- helpers

    /** {@code json} (no format), {@code csv}, {@code xlsx} ("excel" too) or {@code pdf}; anything else is a 400. */
    private static String output(String format) {
        if (format == null || format.isBlank()) {
            return "json";
        }
        return switch (format.strip().toLowerCase(Locale.ROOT)) {
            case "json" -> "json";
            case "csv" -> "csv";
            case "xlsx", "excel" -> "xlsx";
            case "pdf" -> "pdf";
            default -> throw new BadRequestException("INVALID_PARAMETER",
                    "Unknown format '" + format + "' (one of: json, csv, xlsx, pdf)");
        };
    }

    /** The period first, then the report's own filters, for the heading of the Excel and PDF files. */
    private static Map<String, Object> filters(ReportRange range, Object... more) {
        Object[] pairs = new Object[4 + more.length];
        pairs[0] = "From";
        pairs[1] = range.from();
        pairs[2] = "To";
        pairs[3] = range.to();
        System.arraycopy(more, 0, pairs, 4, more.length);
        return Exporter.filters(pairs);
    }

    /**
     * The report in the asked format. An Excel or PDF file is written straight to the response (sized, named and
     * limited by the exporter), so the handler returns no entity.
     */
    private <T, R> ResponseEntity<?> respond(String output, ReportRange range, T report, Formats<T, R> formats,
                                             CurrentUser user, HttpServletResponse response,
                                             Map<String, Object> filters) {
        return switch (output) {
            case "json" -> ResponseEntity.ok(report);
            case "csv" -> {
                String filename = formats.csvName() + "_" + range.from() + "_" + range.to() + ".csv";
                yield ResponseEntity.ok()
                        .contentType(Csv.TEXT_CSV)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename)
                                .build().toString())
                        .body(formats.csv().apply(report));
            }
            default -> {
                exporter.write(response, user, ExportFormat.parse(output), formats.table(),
                        RowSource.of(formats.rows().apply(report)), filters);
                yield null;
            }
        };
    }
}
