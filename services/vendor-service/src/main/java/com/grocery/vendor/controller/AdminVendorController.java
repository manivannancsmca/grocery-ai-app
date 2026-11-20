package com.grocery.vendor.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.dto.VendorDtos.ReasonRequest;
import com.grocery.vendor.dto.VendorDtos.StatusChangeView;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.dto.VendorExports;
import com.grocery.vendor.service.VendorAdminService;
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

/** Any admin role may look; only SUPER_ADMIN / OPS_ADMIN may change a vendor's status. */
@RestController
@RequestMapping("/api/admin/vendors")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminVendorController {

    private final VendorAdminService admin;
    private final Exporter exporter;

    public AdminVendorController(VendorAdminService admin, Exporter exporter) {
        this.admin = admin;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<VendorView> search(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String query,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return admin.search(status, query, page, size);
    }

    /**
     * The vendor list as a file ({@code format} = csv | xlsx | pdf), with the search's filters, every page, in the
     * list's order. The owner's e-mail, phone and street address only for SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String query, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, VendorExports.VENDORS.visibleTo(user),
                admin.searchExport(status, query), Exporter.filters("Status", status, "Search", query));
    }

    @GetMapping("/{id}")
    public VendorView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @GetMapping("/{id}/history")
    public List<StatusChangeView> history(@PathVariable UUID id) {
        return admin.history(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public VendorView approve(@PathVariable UUID id, CurrentUser user) {
        return admin.approve(id, user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public VendorView reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.reject(id, request.reason(), user);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public VendorView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.suspend(id, request.reason(), user);
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public VendorView reinstate(@PathVariable UUID id, CurrentUser user) {
        return admin.reinstate(id, user);
    }
}
