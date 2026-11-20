package com.grocery.order.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.ExportTable;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.service.ReturnQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own returns across orders (docs/api/phase-12-post-mvp-features.md §3), newest first. Requesting one
 * and an order's returns are under {@code /api/orders/{id}/returns} ({@link OrderController}).
 */
@RestController
@RequestMapping("/api/returns")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerReturnController {

    static final ExportTable<ReturnView> EXPORT = OrderExports.returns("my-returns", "My returns");

    private final ReturnQueryService queries;
    private final Exporter exporter;

    public CustomerReturnController(ReturnQueryService queries, Exporter exporter) {
        this.queries = queries;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<ReturnView> list(CurrentUser user, @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return queries.ofCustomer(user.userId(), page, size);
    }

    /** The caller's returns as a file ({@code format} = csv | xlsx | pdf), newest first. */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, EXPORT, queries.ofCustomerExport(user.userId(), sort));
    }
}
