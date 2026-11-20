package com.grocery.pricing.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
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
 * Admin coupons (Phase 12 §2): any admin role may read; SUPER_ADMIN, OPS_ADMIN and FINANCE_ADMIN create, change,
 * pause and resume. Admins choose the scope (PLATFORM, or STORE for a given store).
 */
@RestController
@RequestMapping("/api/admin/coupons")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminCouponController {

    /** SpEL for {@code @PreAuthorize}: who may write coupons. */
    public static final String COUPON_ADMINS = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','FINANCE_ADMIN')";

    private final CouponService coupons;
    private final Exporter exporter;

    public AdminCouponController(CouponService coupons, Exporter exporter) {
        this.coupons = coupons;
        this.exporter = exporter;
    }

    /** Newest first; {@code status} = ACTIVE | PAUSED | SCHEDULED | EXPIRED; {@code q} = code prefix or name. */
    @GetMapping
    public PageResponse<CouponView> list(@RequestParam(required = false) String scope,
                                         @RequestParam(required = false) UUID storeId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return coupons.list(CouponOwner.ADMIN, scope, storeId, status, q, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code code} | {@code validity}, then {@code ,asc} | {@code ,desc}). Streamed in batches; the
     * limits and errors (422 EXPORT_TOO_LARGE, 429 EXPORT_BUSY) are grocery-common's {@link Exporter}.
     */
    @GetMapping("/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String scope,
                       @RequestParam(required = false) UUID storeId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, CouponExports.ADMIN_COUPONS,
                coupons.listExport(CouponOwner.ADMIN, scope, storeId, status, q, sort),
                Exporter.filters("Scope", scope, "Store ID", storeId, "Status", status, "Search", q));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(COUPON_ADMINS)
    public CouponView create(CurrentUser user, @Valid @RequestBody CouponRequest request) {
        return coupons.create(CouponOwner.ADMIN, request, user);
    }

    @GetMapping("/{id}")
    public CouponView get(@PathVariable UUID id) {
        return coupons.get(CouponOwner.ADMIN, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(COUPON_ADMINS)
    public CouponView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody CouponRequest request) {
        return coupons.update(CouponOwner.ADMIN, id, request, user);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(COUPON_ADMINS)
    public CouponView pause(CurrentUser user, @PathVariable UUID id) {
        return coupons.setActive(CouponOwner.ADMIN, id, false, user);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(COUPON_ADMINS)
    public CouponView resume(CurrentUser user, @PathVariable UUID id) {
        return coupons.setActive(CouponOwner.ADMIN, id, true, user);
    }

    @GetMapping("/{id}/usage")
    public CouponUsage usage(@PathVariable UUID id, @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        return coupons.usage(CouponOwner.ADMIN, id, status, page, size);
    }
}
