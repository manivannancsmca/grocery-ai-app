package com.grocery.reporting.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.ExportTable;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.reporting.audit.AuditRepository.Filter;
import com.grocery.reporting.audit.AuditRepository.Row;
import com.grocery.reporting.audit.AuditService;
import com.grocery.reporting.report.ReportRange;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code GET /api/admin/audit}: one search across the platform's audit logs (README "Audit trail") — sign-ins and
 * account changes, store and partner decisions, prices, stock, orders, returns, refunds, reviews, the assistants'
 * approvals and platform settings — newest first, as JSON pages or as a CSV, Excel or PDF file ({@code format}).
 * Operations admins only (SUPER_ADMIN, OPS_ADMIN): the trail has sign-in addresses and e-mails.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize(Roles.OPERATIONS_ADMIN)
public class AuditController {

    public static final Set<String> CATEGORIES = Set.of("SECURITY", "ACCOUNTS", "STORES", "PARTNERS", "PRICES",
            "STOCK", "ORDERS", "DELIVERIES", "PAYMENTS", "REVIEWS", "AI", "SETTINGS");
    public static final Set<String> TARGET_TYPES = Set.of("USER", "VENDOR", "RESTAURANT", "PARTNER", "ORDER",
            "PRODUCT", "REVIEW", "APPROVAL", "SETTING");
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;
    private static final int MAX_TEXT = 100;

    /** The category names of the Audit log page, for files people read. */
    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("SECURITY", "Sign-in"), Map.entry("ACCOUNTS", "Accounts"), Map.entry("STORES", "Stores"),
            Map.entry("PARTNERS", "Delivery partners"), Map.entry("PRICES", "Prices"), Map.entry("STOCK", "Stock"),
            Map.entry("ORDERS", "Orders"), Map.entry("DELIVERIES", "Deliveries"), Map.entry("PAYMENTS", "Payments"),
            Map.entry("REVIEWS", "Reviews"), Map.entry("AI", "AI assistants"), Map.entry("SETTINGS", "Settings"));

    static final ExportTable<Row> TABLE = ExportTable.<Row>named("audit-trail", "Audit trail")
            .dateTime("When", Row::occurredAt)
            .text("Category", row -> CATEGORY_LABELS.getOrDefault(row.category(), row.category()))
            .text("Action", Row::action)
            .text("What happened", Row::summary)
            .text("Who", AuditController::who)
            .text("Acting as", Row::actorRole)
            .text("User ID", Row::actorId)
            .text("Target type", Row::targetType)
            .text("Target", Row::targetLabel)
            .text("Target ID", Row::targetId)
            .text("Details", Row::detail)
            .text("IP address", Row::ipAddress).personal()
            .text("Service", Row::source)
            .build();

    /** One entry as the Audit log page shows it. */
    public record AuditView(UUID id, Instant occurredAt, String category, String action, String source, Actor actor,
                            Target target, String summary, String detail, String ipAddress, String userAgent) {

        public record Actor(UUID id, String role, String name, String email) {
        }

        public record Target(String type, String id, String label) {
        }

        static AuditView of(Row r) {
            return new AuditView(r.id(), r.occurredAt(), r.category(), r.action(), r.source(),
                    new Actor(r.actorId(), r.actorRole(), r.actorName(), r.actorEmail()),
                    r.targetType() == null ? null : new Target(r.targetType(), r.targetId(), r.targetLabel()),
                    r.summary(), r.detail(), r.ipAddress(), r.userAgent());
        }
    }

    private final AuditService audit;
    private final Exporter exporter;
    private final Clock clock;

    public AuditController(AuditService audit, Exporter exporter, Clock clock) {
        this.audit = audit;
        this.exporter = exporter;
        this.clock = clock;
    }

    /**
     * @param q          words found in the summary, details, target, action, the actor's name or e-mail, or the IP
     * @param category   one or more categories (repeat the parameter); none = all
     * @param from       first India date (default: 30 days ending on {@code to}); at most 366 days
     * @param to         last India date (default: today)
     * @param format     none (JSON page), {@code csv}, {@code xlsx} or {@code pdf} (the whole search, every page)
     */
    @GetMapping
    public ResponseEntity<PageResponse<AuditView>> search(CurrentUser user,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) List<String> category,
                                                          @RequestParam(required = false) String action,
                                                          @RequestParam(required = false) UUID actorId,
                                                          @RequestParam(required = false) String targetType,
                                                          @RequestParam(required = false) String targetId,
                                                          @RequestParam(required = false) String from,
                                                          @RequestParam(required = false) String to,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) String format,
                                                          HttpServletResponse response) {
        ReportRange range = ReportRange.of(from, to, clock);
        Filter filter = new Filter(range.start(), range.end(), categories(category), code("action", action, 60),
                actorId, targetType(targetType), text("targetId", targetId), text("q", q));
        if (format != null && !format.isBlank() && !"json".equalsIgnoreCase(format.strip())) {
            ExportFormat output = exportFormat(format);
            exporter.write(response, user, output, TABLE.visibleTo(user), audit.export(filter),
                    Exporter.filters("From", range.from(), "To", range.to(), "Search", filter.text(),
                            "Categories", filter.categories() == null ? null : String.join(", ",
                                    filter.categories()), "Action", filter.action(), "User ID", actorId,
                            "Target type", filter.targetType(), "Target ID", filter.targetId()));
            return null;
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BadRequestException("INVALID_PARAMETER", "page must be ≥ 0 and size between 1 and " + MAX_SIZE);
        }
        return ResponseEntity.ok(audit.search(filter, pageNumber, pageSize).map(AuditView::of));
    }

    private static List<String> categories(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> categories = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                String code = part.strip().toUpperCase(Locale.ROOT);
                if (code.isEmpty()) {
                    continue;
                }
                if (!CATEGORIES.contains(code)) {
                    throw new BadRequestException("INVALID_PARAMETER", "Unknown category '" + part.strip()
                            + "' (one of: " + String.join(", ", CATEGORIES.stream().sorted().toList()) + ")");
                }
                categories.add(code);
            }
        }
        return categories.isEmpty() ? null : categories;
    }

    private static String targetType(String value) {
        String code = code("targetType", value, 20);
        if (code != null && !TARGET_TYPES.contains(code)) {
            throw new BadRequestException("INVALID_PARAMETER", "Unknown targetType '" + value + "'");
        }
        return code;
    }

    private static String code(String name, String value, int max) {
        String text = text(name, value);
        if (text == null) {
            return null;
        }
        if (text.length() > max) {
            throw new BadRequestException("INVALID_PARAMETER", name + " has at most " + max + " characters");
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private static String text(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > MAX_TEXT) {
            throw new BadRequestException("INVALID_PARAMETER", name + " has at most " + MAX_TEXT + " characters");
        }
        return text;
    }

    private static ExportFormat exportFormat(String format) {
        try {
            return ExportFormat.parse(format.strip().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new BadRequestException("INVALID_PARAMETER", "Unknown format '" + format
                    + "' (one of: json, csv, xlsx, pdf)");
        }
    }

    /** The actor for people: their name, else their e-mail, else what they acted as. */
    private static String who(Row row) {
        if (row.actorName() != null) {
            return row.actorName();
        }
        if (row.actorEmail() != null) {
            return row.actorEmail();
        }
        return switch (row.actorRole()) {
            case "SYSTEM" -> "System";
            case "ANONYMOUS" -> "Not signed in";
            default -> row.actorId() == null ? row.actorRole() : null;
        };
    }
}
