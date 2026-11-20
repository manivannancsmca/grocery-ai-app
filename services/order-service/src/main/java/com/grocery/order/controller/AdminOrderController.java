package com.grocery.order.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.OrderDtos.AdminCancelRequest;
import com.grocery.order.dto.OrderDtos.AdminOrderSummary;
import com.grocery.order.dto.OrderDtos.AdminOrderView;
import com.grocery.order.dto.OrderDtos.OrderEventView;
import com.grocery.order.dto.OrderDtos.StuckOrderView;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.service.OrderCancellationService;
import com.grocery.order.service.OrderQueryService;
import com.grocery.order.service.OrderQueryService.AdminFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The admin order monitor (contract §4): reads for any admin role, cancel for SUPER_ADMIN, OPS_ADMIN, SUPPORT.
 * Phase 10: the stuck orders (§4) and an order's event timeline (§5), any admin role.
 */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminOrderController {

    /** Who may cancel any order (method-level, replaces the class-level read rule). */
    static final String CAN_CANCEL = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')";

    private final OrderQueryService queries;
    private final OrderCancellationService cancellation;
    private final Exporter exporter;

    public AdminOrderController(OrderQueryService queries, OrderCancellationService cancellation, Exporter exporter) {
        this.queries = queries;
        this.cancellation = cancellation;
        this.exporter = exporter;
    }

    /**
     * Newest first. {@code status} = an order status or {@code OPEN}; {@code q} = order number (exact,
     * case-insensitive); {@code from}/{@code to} = dates in India time, inclusive.
     */
    @GetMapping
    public PageResponse<AdminOrderSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.adminOrders(new AdminFilter(status, type, customerId, storeId, q, from, to), page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code order} | {@code total}, then {@code ,asc} | {@code ,desc}). Streamed in batches; the limits
     * and errors (422 EXPORT_TOO_LARGE, 429 EXPORT_BUSY) are grocery-common's {@link Exporter}.
     */
    @GetMapping("/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) UUID customerId,
                       @RequestParam(required = false) UUID storeId,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        AdminFilter filter = new AdminFilter(status, type, customerId, storeId, q, from, to);
        exporter.write(response, user, fileFormat, OrderExports.ADMIN_ORDERS, queries.adminOrdersExport(filter, sort),
                Exporter.filters("Status", status, "Type", type, "Customer ID", customerId, "Store ID", storeId,
                        "Order number", q, "From", from, "To", to));
    }

    @GetMapping("/{id}")
    public AdminOrderView get(@PathVariable UUID id) {
        return queries.adminOrder(id);
    }

    /** Orders the watchdog found stuck and that are still in that status, longest overdue first. */
    @GetMapping("/stuck")
    public List<StuckOrderView> stuck() {
        return queries.stuckOrders();
    }

    /** Every event published or consumed for the order, oldest first; unknown order → 404 ORDER_NOT_FOUND. */
    @GetMapping("/{id}/events")
    public List<OrderEventView> events(@PathVariable UUID id) {
        return queries.orderEvents(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CAN_CANCEL)
    public AdminOrderView cancel(CurrentUser user, @PathVariable UUID id,
                                 @Valid @RequestBody AdminCancelRequest request) {
        return cancellation.cancelByAdmin(user.userId(), id, request.reason());
    }
}
