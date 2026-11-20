package com.grocery.customer.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.dto.CustomerExports;
import com.grocery.customer.service.CustomerAdminService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Any admin role may search customers (read-only). */
@RestController
@RequestMapping("/api/admin/customers")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminCustomerController {

    private final CustomerAdminService admin;
    private final Exporter exporter;

    public AdminCustomerController(CustomerAdminService admin, Exporter exporter) {
        this.admin = admin;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<CustomerProfile> search(@RequestParam(required = false) String query,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return admin.search(query, page, size);
    }

    /**
     * The directory as a file ({@code format} = csv | xlsx | pdf): the same search, every page, newest first or by
     * {@code sort} ({@code name} | {@code joined} | {@code updated}, {@code ,asc} | {@code ,desc}). E-mail and phone
     * only for SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, CustomerExports.CUSTOMERS.visibleTo(user),
                admin.searchExport(query, sort), Exporter.filters("Search", query));
    }
}
