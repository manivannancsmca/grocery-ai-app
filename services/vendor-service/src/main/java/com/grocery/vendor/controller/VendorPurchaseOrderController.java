package com.grocery.vendor.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderRequest;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingDtos.ReceiveRequest;
import com.grocery.vendor.dto.PurchasingExports;
import com.grocery.vendor.service.PurchaseOrderService;
import com.grocery.vendor.service.PurchaseReceivingService;
import com.grocery.vendor.service.PurchaseReceivingService.Received;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in vendor's purchase orders (docs/api/phase-12-post-mvp-features.md §4). The store comes from the JWT's
 * {@code vendor_id} claim (without it → 403 VENDOR_NOT_ACTIVE); another vendor's purchase order is 404
 * PURCHASE_ORDER_NOT_FOUND. {@code send} is also the target of the vendor assistant's SEND_PURCHASE_ORDER (with an
 * {@code X-Approval-Token}, verified by grocery-common's filter before this controller runs).
 */
@RestController
@RequestMapping("/api/vendor/purchase-orders")
@PreAuthorize("hasRole('VENDOR')")
public class VendorPurchaseOrderController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PurchaseOrderService purchaseOrders;
    private final PurchaseReceivingService receiving;
    private final Exporter exporter;

    public VendorPurchaseOrderController(PurchaseOrderService purchaseOrders, PurchaseReceivingService receiving,
                                         Exporter exporter) {
        this.purchaseOrders = purchaseOrders;
        this.receiving = receiving;
        this.exporter = exporter;
    }

    /** Newest first; {@code status} = a status or {@code OPEN}; {@code number} = PO-XXXXXXXX (exact). */
    @GetMapping
    public PageResponse<PurchaseOrderView> list(CurrentUser user,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) UUID supplierId,
                                                @RequestParam(required = false) String number,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return purchaseOrders.list(user, status, supplierId, number, page, size);
    }

    /** The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first. */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID supplierId,
                       @RequestParam(required = false) String number, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PurchasingExports.PURCHASE_ORDERS,
                purchaseOrders.listExport(user, status, supplierId, number),
                Exporter.filters("Status", status, "Supplier ID", supplierId, "Number", number));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderView create(CurrentUser user, @Valid @RequestBody PurchaseOrderRequest request) {
        return purchaseOrders.create(user, request);
    }

    @GetMapping("/{id}")
    public PurchaseOrderView get(CurrentUser user, @PathVariable UUID id) {
        return purchaseOrders.get(user, id);
    }

    /** DRAFT only (409 PURCHASE_ORDER_NOT_EDITABLE with {@code currentStatus}). */
    @PutMapping("/{id}")
    public PurchaseOrderView update(CurrentUser user, @PathVariable UUID id,
                                    @Valid @RequestBody PurchaseOrderRequest request) {
        return purchaseOrders.update(user, id, request);
    }

    @PostMapping("/{id}/send")
    public PurchaseOrderView send(CurrentUser user, @PathVariable UUID id) {
        return purchaseOrders.send(user, id);
    }

    @PostMapping("/{id}/cancel")
    public PurchaseOrderView cancel(CurrentUser user, @PathVariable UUID id) {
        return purchaseOrders.cancel(user, id);
    }

    @PostMapping("/{id}/close")
    public PurchaseOrderView close(CurrentUser user, @PathVariable UUID id) {
        return purchaseOrders.close(user, id);
    }

    /**
     * Goods received ({@code Idempotency-Key} required): 200 with the purchase order and its receipts; a retry with
     * the same key and body answers the same purchase order with {@code Idempotent-Replayed: true}.
     */
    @PostMapping("/{id}/receive")
    public ResponseEntity<PurchaseOrderView> receive(CurrentUser user, @PathVariable UUID id,
                                                     @RequestHeader(value = IdempotencyKeys.HEADER, required = false)
                                                     String idempotencyKey,
                                                     @RequestBody(required = false) ReceiveRequest request) {
        Received received = receiving.receive(user, id, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (received.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(received.purchaseOrder());
    }
}
