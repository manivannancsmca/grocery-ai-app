package com.grocery.order.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.ExportTable;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.ReturnDtos.ApproveReturnRequest;
import com.grocery.order.dto.ReturnDtos.RejectReturnRequest;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.service.ReturnQueryService;
import com.grocery.order.service.ReturnService;
import com.grocery.order.service.StoreRef;
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
 * The store's side of returns (docs/api/phase-12-post-mvp-features.md §3), VENDOR and RESTAURANT_OWNER. The store is
 * the caller's own, from the JWT ({@code vendor_id} / {@code restaurant_id}, see {@link StoreRef}); another store's
 * return is 404 RETURN_NOT_FOUND. Only REQUESTED returns can be decided here (ESCALATED ones belong to the admins):
 * 409 RETURN_NOT_PENDING with {@code currentStatus} otherwise.
 */
@RestController
@RequestMapping("/api/store/returns")
@PreAuthorize("hasAnyRole('VENDOR','RESTAURANT_OWNER')")
public class StoreReturnController {

    static final ExportTable<ReturnView> EXPORT = OrderExports.returns("store-returns", "Returns");

    private final ReturnService returns;
    private final ReturnQueryService queries;
    private final Exporter exporter;

    public StoreReturnController(ReturnService returns, ReturnQueryService queries, Exporter exporter) {
        this.returns = returns;
        this.queries = queries;
        this.exporter = exporter;
    }

    /** The store's returns, newest first; {@code status} = one return status (default all). */
    @GetMapping
    public PageResponse<ReturnView> list(CurrentUser user, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return queries.ofStore(StoreRef.of(user), status, page, size);
    }

    /**
     * The store's returns as a file ({@code format} = csv | xlsx | pdf), with the list's status filter; the store is
     * the caller's own (from the JWT), never a parameter.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, EXPORT, queries.ofStoreExport(StoreRef.of(user), status, sort),
                Exporter.filters("Status", status));
    }

    /** {@code restock} (optional, default false) is forced false for a restaurant order. */
    @PostMapping("/{id}/approve")
    public ReturnView approve(CurrentUser user, @PathVariable UUID id,
                              @RequestBody(required = false) ApproveReturnRequest request) {
        return returns.approveByStore(StoreRef.of(user), user.userId(), id,
                request == null ? null : request.restock());
    }

    @PostMapping("/{id}/reject")
    public ReturnView reject(CurrentUser user, @PathVariable UUID id,
                             @Valid @RequestBody RejectReturnRequest request) {
        return returns.rejectByStore(StoreRef.of(user), user.userId(), id, request.comment());
    }
}
