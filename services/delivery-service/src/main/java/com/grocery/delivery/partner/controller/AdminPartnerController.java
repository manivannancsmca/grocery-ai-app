package com.grocery.delivery.partner.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.dto.PartnerExports;
import com.grocery.delivery.partner.dto.PartnerDtos.ReasonRequest;
import com.grocery.delivery.partner.dto.PartnerDtos.StatusChangeView;
import com.grocery.delivery.partner.service.PartnerAdminService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Any admin role may look; SUPER_ADMIN, OPS_ADMIN and DELIVERY_MANAGER (who run the fleet) may change
 * a partner's status.
 */
@RestController
@RequestMapping("/api/admin/delivery-partners")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminPartnerController {

    /** SpEL for {@code @PreAuthorize}: partner approvals and suspensions. */
    static final String FLEET_ADMIN = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','DELIVERY_MANAGER')";

    private final PartnerAdminService admin;
    private final Exporter exporter;

    public AdminPartnerController(PartnerAdminService admin, Exporter exporter) {
        this.admin = admin;
        this.exporter = exporter;
    }

    /**
     * The partner list as a file ({@code format} = csv | xlsx | pdf), with the search's filters, every page, in the
     * list's order. E-mail and phone only for SUPER_ADMIN, OPS_ADMIN and SUPPORT; licence numbers never.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String query, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PartnerExports.PARTNERS.visibleTo(user),
                admin.searchExport(status, query), Exporter.filters("Status", status, "Search", query));
    }

    @GetMapping
    public PageResponse<PartnerView> search(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String query,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return admin.search(status, query, page, size);
    }

    @GetMapping("/{id}")
    public PartnerView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @GetMapping("/{id}/history")
    public List<StatusChangeView> history(@PathVariable UUID id) {
        return admin.history(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(FLEET_ADMIN)
    public PartnerView approve(@PathVariable UUID id, CurrentUser user) {
        return admin.approve(id, user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(FLEET_ADMIN)
    public PartnerView reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.reject(id, request.reason(), user);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(FLEET_ADMIN)
    public PartnerView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.suspend(id, request.reason(), user);
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize(FLEET_ADMIN)
    public PartnerView reinstate(@PathVariable UUID id, CurrentUser user) {
        return admin.reinstate(id, user);
    }
}
