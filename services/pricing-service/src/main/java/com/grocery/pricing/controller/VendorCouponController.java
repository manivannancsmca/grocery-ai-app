package com.grocery.pricing.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.dto.CouponDtos.CouponRequest;
import com.grocery.pricing.dto.CouponDtos.CouponUsage;
import com.grocery.pricing.dto.CouponDtos.CouponView;
import com.grocery.pricing.dto.CouponExports;
import com.grocery.pricing.service.CouponOwner;
import com.grocery.pricing.service.CouponService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in vendor's own coupons (Phase 12 §2): scope forced to STORE with the {@code vendor_id} claim (403
 * VENDOR_NOT_ACTIVE without it). Another store's coupon → 404 COUPON_NOT_FOUND.
 */
@RestController
@RequestMapping("/api/vendor/coupons")
@PreAuthorize("hasRole('VENDOR')")
public class VendorCouponController {

    private final CouponService coupons;
    private final Exporter exporter;

    public VendorCouponController(CouponService coupons, Exporter exporter) {
        this.coupons = coupons;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<CouponView> list(CurrentUser user, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return coupons.list(CouponOwner.vendor(user), null, null, status, q, page, size);
    }

    /**
     * The caller's coupons as a file ({@code format} = csv | xlsx | pdf), with the list's filters, newest first or by
     * {@code sort} ({@code code} | {@code validity}, then {@code ,asc} | {@code ,desc}); the store is the caller's own
     * vendor (from the JWT), never a parameter.
     */
    @GetMapping("/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, CouponExports.STORE_COUPONS,
                coupons.listExport(CouponOwner.vendor(user), null, null, status, q, sort),
                Exporter.filters("Status", status, "Search", q));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CouponView create(CurrentUser user, @Valid @RequestBody CouponRequest request) {
        return coupons.create(CouponOwner.vendor(user), request, user);
    }

    @GetMapping("/{id}")
    public CouponView get(CurrentUser user, @PathVariable UUID id) {
        return coupons.get(CouponOwner.vendor(user), id);
    }

    @PutMapping("/{id}")
    public CouponView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody CouponRequest request) {
        return coupons.update(CouponOwner.vendor(user), id, request, user);
    }

    @PostMapping("/{id}/pause")
    public CouponView pause(CurrentUser user, @PathVariable UUID id) {
        return coupons.setActive(CouponOwner.vendor(user), id, false, user);
    }

    @PostMapping("/{id}/resume")
    public CouponView resume(CurrentUser user, @PathVariable UUID id) {
        return coupons.setActive(CouponOwner.vendor(user), id, true, user);
    }

    @GetMapping("/{id}/usage")
    public CouponUsage usage(CurrentUser user, @PathVariable UUID id, @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        return coupons.usage(CouponOwner.vendor(user), id, status, page, size);
    }
}
