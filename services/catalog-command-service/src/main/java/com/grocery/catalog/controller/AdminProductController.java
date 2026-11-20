package com.grocery.catalog.controller;

import com.grocery.catalog.dto.ProductDtos.AdminProductView;
import com.grocery.catalog.dto.ProductDtos.RepublishResult;
import com.grocery.catalog.dto.ProductExports;
import com.grocery.catalog.service.ProductQueryService;
import com.grocery.catalog.service.RepublishService;
import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin product browsing (any admin) and the search-index republish (SUPER_ADMIN / OPS_ADMIN). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminProductController {

    private final ProductQueryService queries;
    private final RepublishService republish;
    private final Exporter exporter;

    public AdminProductController(ProductQueryService queries, RepublishService republish, Exporter exporter) {
        this.queries = queries;
        this.republish = republish;
        this.exporter = exporter;
    }

    @GetMapping("/products")
    public PageResponse<AdminProductView> list(@RequestParam(required = false) UUID vendorId,
                                               @RequestParam(required = false) UUID categoryId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return queries.adminList(vendorId, categoryId, status, q, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, most recently changed
     * first or by {@code sort} ({@code product} | {@code status} | {@code updated}, then {@code ,asc} | {@code ,desc}).
     * Streamed in batches; the limits and errors (422 EXPORT_TOO_LARGE, 429 EXPORT_BUSY) are grocery-common's
     * {@link Exporter}.
     */
    @GetMapping("/products/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) UUID vendorId,
                       @RequestParam(required = false) UUID categoryId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ProductExports.ADMIN_PRODUCTS,
                queries.adminExport(vendorId, categoryId, status, q, sort),
                Exporter.filters("Vendor ID", vendorId, "Category ID", categoryId, "Status", status, "Search", q));
    }

    @GetMapping("/products/{id}")
    public AdminProductView get(@PathVariable UUID id) {
        return queries.adminGet(id);
    }

    /** Re-sends every category and product snapshot (rebuilds the search index). */
    @PostMapping("/catalog/republish")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RepublishResult republish() {
        return republish.republishAll();
    }
}
