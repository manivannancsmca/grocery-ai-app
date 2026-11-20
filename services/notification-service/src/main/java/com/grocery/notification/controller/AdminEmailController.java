package com.grocery.notification.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.notification.dto.EmailDtos.EmailLogEntry;
import com.grocery.notification.dto.EmailExports;
import com.grocery.notification.service.EmailSettingsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 §5: {@code GET /api/admin/notifications/emails?status=PENDING|SENT|FAILED&to=&page=&size=} (any admin) —
 * the email log, newest first: {@code to}, {@code subject}, {@code type}, {@code status}, {@code attempts},
 * {@code lastError}, {@code createdAt}, {@code sentAt}. {@code to} matches any part of the address, ignoring case.
 */
@RestController
@RequestMapping("/api/admin/notifications/emails")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminEmailController {

    private final EmailSettingsService settings;
    private final Exporter exporter;

    public AdminEmailController(EmailSettingsService settings, Exporter exporter) {
        this.settings = settings;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<EmailLogEntry> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return settings.log(status, to, page, size);
    }

    /**
     * The log as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code to} | {@code subject} | {@code status} | {@code attempts} | {@code when}, then {@code ,asc}
     * | {@code ,desc}). Never an e-mail's body or link; the recipient's address and the last error only for
     * SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String to,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, EmailExports.EMAIL_LOG.visibleTo(user),
                settings.logExport(status, to, sort), Exporter.filters("Status", status, "Recipient", to));
    }
}
