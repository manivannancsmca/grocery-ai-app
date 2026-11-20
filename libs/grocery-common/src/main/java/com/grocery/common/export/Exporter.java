package com.grocery.common.export;

import com.grocery.common.security.CurrentUser;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Streams an export to the HTTP response: the one place that knows about formats, limits and failures, so each
 * service only says <em>which</em> rows (its own list query, with its own authorization) and <em>which</em> columns.
 * <pre>{@code
 * @GetMapping("/export")
 * public void export(CurrentUser user, @RequestParam String format, …filters…, HttpServletResponse response) {
 *     exporter.write(response, user, ExportFormat.parse(format), ORDERS, queries.exportOrders(user, filter),
 *             Exporter.filters("Status", status, "From", from));
 * }
 * }</pre>
 * In order:
 * <ol>
 *   <li>a slot (429 EXPORT_BUSY when this user or this instance already runs its share of exports);</li>
 *   <li>the row count, checked against the limits (422 EXPORT_TOO_LARGE) — still nothing written, so every error so
 *       far is a normal ProblemDetail;</li>
 *   <li>the file, written batch by batch and flushed after each one: memory stays flat, the download starts at once;
 *   </li>
 *   <li>the outcome in the log and the metrics ({@code grocery_export_seconds}, {@code grocery_export_rows}).</li>
 * </ol>
 * The client going away (cancel, closed tab) just stops the export. A failure after the first bytes were sent cannot
 * become an error response any more: it is rethrown as {@link ExportStreamException} and the connection is cut, so
 * the download fails instead of leaving a truncated file that looks complete.
 *
 * <p>Call it from the controller (outside any transaction): the rows are read in their own short transactions.
 */
public final class Exporter {

    private static final Logger log = LoggerFactory.getLogger(Exporter.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT);
    private static final DateTimeFormatter SHOWN_STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm",
            Locale.ENGLISH);

    /** The number of rows the export will have: lets the client show progress. */
    public static final String ROWS_HEADER = "X-Export-Rows";

    private final ExportProperties properties;
    private final ExportLimiter limiter;
    private final MeterRegistry meters;
    private final Clock clock;

    public Exporter(ExportProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.limiter = new ExportLimiter(properties.getMaxConcurrent(), properties.getMaxPerUser());
        this.meters = meters;
        this.clock = clock;
    }

    /** {@link #write(HttpServletResponse, CurrentUser, ExportFormat, ExportTable, RowSource, Map)} without filters. */
    public <R> void write(HttpServletResponse response, CurrentUser user, ExportFormat format, ExportTable<R> table,
                          RowSource<? extends R> rows) {
        write(response, user, format, table, rows, Map.of());
    }

    /**
     * Writes the export as the response.
     *
     * @param user    the caller (from the JWT): the per-user limit and the audit log line
     * @param filters what the rows were filtered by, as label → value (see {@link #filters}); shown in the PDF
     */
    public <R> void write(HttpServletResponse response, CurrentUser user, ExportFormat format, ExportTable<R> table,
                          RowSource<? extends R> rows, Map<String, ?> filters) {
        if (!properties.isEnabled()) {
            throw ExportException.disabled();
        }
        Objects.requireNonNull(user, "user");
        long started = System.nanoTime();
        try (ExportLimiter.Permit ignored = limiter.acquire(user.userId())) {
            long total = rows.count();
            long limit = checkLimits(format, rows, total);
            LocalDateTime now = LocalDateTime.now(clock.withZone(properties.getTimezone()));

            String fileName = table.name() + "-" + FILE_STAMP.format(now) + "." + format.extension();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(format.contentType());
            // The name is ASCII by construction (export name, date, extension): a plain quoted filename is enough.
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(ROWS_HEADER, Long.toString(Math.min(total, limit)));

            CellValues values = new CellValues(properties.getTimezone());
            TableWriter.Layout layout = new TableWriter.Layout(table.title(), List.copyOf(table.columns()),
                    description(total, limit, now, user, filters), values);
            stream(response, format, table, rows, layout, limit, user, started);
        }
    }

    private <R> void stream(HttpServletResponse response, ExportFormat format, ExportTable<R> table,
                            RowSource<? extends R> rows, TableWriter.Layout layout, long limit, CurrentUser user,
                            long started) {
        long[] written = {0};
        TrackedOutput out;
        try {
            out = new TrackedOutput(response.getOutputStream());
        } catch (IOException e) {
            finished(table, format, "cancelled", written[0], user, started);
            return;
        }
        TableWriter writer = TableWriter.create(format, out, layout);
        try {
            writer.start();
            rows.forEachBatch(Math.max(1, properties.getBatchSize()), batch -> {
                for (R row : batch) {
                    if (written[0] >= limit) {
                        return false;
                    }
                    writer.row(table.values(row));
                    written[0]++;
                }
                writer.flush();
                return true;
            });
            writer.finish();
            finished(table, format, "done", written[0], user, started);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IOException || out.failed()) {
                // The client cancelled or lost the connection: nobody is waiting for an answer.
                log.info("Export {} ({}) cancelled by the client after {} rows", table.name(), format, written[0]);
                finished(table, format, "cancelled", written[0], user, started);
                return;
            }
            finished(table, format, "failed", written[0], user, started);
            if (response.isCommitted()) {
                log.error("Export {} ({}) failed after {} rows; the connection is closed so the download fails",
                        table.name(), format, written[0], e);
                throw new ExportStreamException("Export " + table.name() + " failed after " + written[0] + " rows", e);
            }
            response.reset(); // nothing sent yet: the error becomes a normal ProblemDetail response
            throw (RuntimeException) e;
        }
    }

    private long checkLimits(ExportFormat format, RowSource<?> rows, long total) {
        if (total > properties.getMaxRows()) {
            throw ExportException.tooLarge(total, properties.getMaxRows(), "Narrow the filters (a date range, a "
                    + "status) and export again.");
        }
        if (rows.customSorted() && total > properties.getSortedMaxRows()) {
            throw ExportException.tooLarge(total, properties.getSortedMaxRows(), "Sorted exports are smaller: "
                    + "clear the column sort to export in the default order, or narrow the filters.");
        }
        if (format == ExportFormat.PDF && total > properties.getPdfMaxRows()) {
            throw ExportException.tooLarge(total, properties.getPdfMaxRows(), "Choose CSV or Excel for this many "
                    + "rows, or narrow the filters.");
        }
        return format == ExportFormat.PDF ? properties.getPdfMaxRows()
                : rows.customSorted() ? properties.getSortedMaxRows() : properties.getMaxRows();
    }

    private String description(long total, long limit, LocalDateTime now, CurrentUser user, Map<String, ?> filters) {
        List<String> parts = new ArrayList<>();
        long rows = Math.min(total, limit);
        parts.add(String.format(Locale.ENGLISH, "%,d %s", rows, rows == 1 ? "row" : "rows"));
        parts.add("Exported " + SHOWN_STAMP.format(now) + " (" + properties.getTimezone().getId() + ")"
                + (user.name() == null || user.name().isBlank() ? "" : " by " + user.name()));
        List<String> applied = new ArrayList<>();
        filters.forEach((label, value) -> {
            String shown = value == null ? "" : CellValues.filterValue(value);
            if (!shown.isBlank()) {
                applied.add(label + ": " + shown);
            }
        });
        parts.add(applied.isEmpty() ? "No filters" : "Filters: " + String.join(", ", applied));
        return String.join("  ·  ", parts);
    }

    private void finished(ExportTable<?> table, ExportFormat format, String outcome, long rows, CurrentUser user,
                          long started) {
        long nanos = System.nanoTime() - started;
        if (!outcome.equals("cancelled")) {
            log.info("Export {} ({}) {}: {} rows in {} ms, user {}", table.name(), format, outcome, rows,
                    TimeUnit.NANOSECONDS.toMillis(nanos), user.userId());
        }
        if (meters != null) {
            String fileFormat = format.extension();
            Timer.builder("grocery.export").description("Exports streamed to clients, by outcome")
                    .tags("export", table.name(), "format", fileFormat, "outcome", outcome)
                    .register(meters).record(nanos, TimeUnit.NANOSECONDS);
            DistributionSummary.builder("grocery.export.rows").description("Rows written per export")
                    .tags("export", table.name(), "format", fileFormat)
                    .register(meters).record(rows);
        }
    }

    /**
     * The filters to show in the PDF heading, from label/value pairs: {@code filters("Status", status, "From", from)}.
     * Empty values are left out; enum constants become words.
     */
    public static Map<String, Object> filters(Object... labelsAndValues) {
        if (labelsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("filters(…) takes label/value pairs");
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            Object value = labelsAndValues[i + 1];
            if (value != null && !value.toString().isBlank()) {
                filters.put(String.valueOf(labelsAndValues[i]), value);
            }
        }
        return filters;
    }

    /** The response stream, remembering whether a write failed (the client went away). */
    private static final class TrackedOutput extends FilterOutputStream {

        private volatile boolean failed;

        TrackedOutput(OutputStream out) {
            super(out);
        }

        boolean failed() {
            return failed;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            flush(); // the container closes the response stream itself
        }
    }
}
