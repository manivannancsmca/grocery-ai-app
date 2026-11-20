package com.grocery.vendor.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.vendor.dto.PurchasingDtos.SupplierRequest;
import com.grocery.vendor.dto.PurchasingDtos.SupplierView;
import com.grocery.vendor.dto.PurchasingExports;
import com.grocery.vendor.service.SupplierService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in vendor's suppliers (docs/api/phase-12-post-mvp-features.md §4). The store comes from the JWT's
 * {@code vendor_id} claim (without it → 403 VENDOR_NOT_ACTIVE); another vendor's supplier is 404 SUPPLIER_NOT_FOUND.
 */
@RestController
@RequestMapping("/api/vendor/suppliers")
@PreAuthorize("hasRole('VENDOR')")
public class VendorSupplierController {

    private final SupplierService suppliers;
    private final Exporter exporter;

    public VendorSupplierController(SupplierService suppliers, Exporter exporter) {
        this.suppliers = suppliers;
        this.exporter = exporter;
    }

    /** Active first, then by name; {@code active=true|false} filters. */
    @GetMapping
    public List<SupplierView> list(CurrentUser user, @RequestParam(required = false) Boolean active) {
        return suppliers.list(user, active);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filter and order. A vendor's suppliers are few,
     * so the list is read at once (as the page reads it) and written from memory.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) Boolean active, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PurchasingExports.SUPPLIERS,
                RowSource.of(suppliers.list(user, active)),
                Exporter.filters("Status", active == null ? null : active ? "Active" : "Inactive"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierView create(CurrentUser user, @Valid @RequestBody SupplierRequest request) {
        return suppliers.create(user, request);
    }

    @GetMapping("/{id}")
    public SupplierView get(CurrentUser user, @PathVariable UUID id) {
        return suppliers.get(user, id);
    }

    @PutMapping("/{id}")
    public SupplierView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return suppliers.update(user, id, request);
    }

    /** Deactivates the supplier (409 SUPPLIER_IN_USE while it has open purchase orders). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser user, @PathVariable UUID id) {
        suppliers.deactivate(user, id);
    }
}
