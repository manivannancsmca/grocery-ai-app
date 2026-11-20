package com.grocery.order.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.ExportTable;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.ReturnDtos.ApproveReturnRequest;
import com.grocery.order.dto.ReturnDtos.RejectReturnRequest;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.service.ReturnQueryService;
import com.grocery.order.service.ReturnService;
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

import java.util.UUID;

/**
 * Returns for admins (docs/api/phase-12-post-mvp-features.md §3): the list for any admin role; approve and reject for
 * SUPER_ADMIN, OPS_ADMIN, SUPPORT — REQUESTED (before the store decided) or ESCALATED returns, 409 RETURN_NOT_PENDING
 * otherwise.
 */
@RestController
@RequestMapping("/api/admin/returns")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminReturnController {

    /** Who may decide returns (method-level, replaces the class-level read rule). */
    static final String CAN_DECIDE = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')";

    static final ExportTable<ReturnView> EXPORT = OrderExports.returns("returns", "Returns");

    private final ReturnService returns;
    private final ReturnQueryService queries;
    private final Exporter exporter;

    public AdminReturnController(ReturnService returns, ReturnQueryService queries, Exporter exporter) {
        this.returns = returns;
        this.queries = queries;
        this.exporter = exporter;
    }

    /** Newest first; {@code status} = one return status (default REQUESTED + ESCALATED), {@code storeId} optional. */
    @GetMapping
    public PageResponse<ReturnView> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) UUID storeId,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return queries.forAdmins(status, storeId, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code return} | {@code refund}, {@code ,asc} | {@code ,desc}).
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID storeId,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, EXPORT, queries.forAdminsExport(status, storeId, sort),
                Exporter.filters("Status", status == null || status.isBlank() ? "Requested or escalated" : status,
                        "Store ID", storeId));
    }

    /** {@code restock} (optional, default false) is forced false for a restaurant order. */
    @PostMapping("/{id}/approve")
    @PreAuthorize(CAN_DECIDE)
    public ReturnView approve(CurrentUser user, @PathVariable UUID id,
                              @RequestBody(required = false) ApproveReturnRequest request) {
        return returns.approveByAdmin(user.userId(), id, request == null ? null : request.restock());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(CAN_DECIDE)
    public ReturnView reject(CurrentUser user, @PathVariable UUID id,
                             @Valid @RequestBody RejectReturnRequest request) {
        return returns.rejectByAdmin(user.userId(), id, request.comment());
    }
}
