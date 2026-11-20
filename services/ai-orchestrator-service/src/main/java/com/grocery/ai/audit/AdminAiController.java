package com.grocery.ai.audit;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** AI audit (and its export) and monitoring for any admin (contract §6). */
@RestController
@RequestMapping("/api/admin/ai")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminAiController {

    private final AiMonitoringService monitoring;
    private final Exporter exporter;

    public AdminAiController(AiMonitoringService monitoring, Exporter exporter) {
        this.monitoring = monitoring;
        this.exporter = exporter;
    }

    /** Newest first; {@code from} / {@code to} = dates (India time), inclusive. */
    @GetMapping("/tool-invocations")
    public PageResponse<AiMonitoringService.ToolInvocationView> invocations(
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            CurrentUser admin) {
        return monitoring.invocations(admin, agent, userId, tool, status, from, to, page, size);
    }

    /**
     * The audit list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code when} | {@code user} | {@code status}, then {@code ,asc} | {@code ,desc}). The model's
     * arguments only for SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/tool-invocations/export")
    public void invocationsExport(
            CurrentUser admin,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort,
            HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, admin, fileFormat, AuditExports.INVOCATIONS.visibleTo(admin),
                monitoring.invocationsExport(agent, userId, tool, status, from, to, sort),
                Exporter.filters("Assistant", agent, "User ID", userId, "Tool", tool, "Status", status, "From", from,
                        "To", to));
    }

    /** One day (India time, default today). */
    @GetMapping("/summary")
    public AiMonitoringService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return monitoring.summary(date);
    }
}
