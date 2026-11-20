package com.grocery.vendor.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingExports;
import com.grocery.vendor.service.PurchaseOrderService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Every vendor's purchase orders, read-only, for any admin role (docs/api/phase-12-post-mvp-features.md §4). */
@RestController
@RequestMapping("/api/admin/purchase-orders")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminPurchaseOrderController {

    private final PurchaseOrderService purchaseOrders;
    private final Exporter exporter;

    public AdminPurchaseOrderController(PurchaseOrderService purchaseOrders, Exporter exporter) {
        this.purchaseOrders = purchaseOrders;
        this.exporter = exporter;
    }

    /** Newest first; {@code vendorId} and {@code status} (a status or {@code OPEN}) optional. */
    @GetMapping
    public PageResponse<PurchaseOrderView> list(@RequestParam(required = false) UUID vendorId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return purchaseOrders.adminList(vendorId, status, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code number} | {@code created}, {@code ,asc} | {@code ,desc}).
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) UUID vendorId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PurchasingExports.ADMIN_PURCHASE_ORDERS,
                purchaseOrders.adminListExport(vendorId, status, sort),
                Exporter.filters("Vendor ID", vendorId, "Status", status));
    }

    @GetMapping("/{id}")
    public PurchaseOrderView get(@PathVariable UUID id) {
        return purchaseOrders.adminGet(id);
    }
}
