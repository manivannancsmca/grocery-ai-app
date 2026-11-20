package com.grocery.pricing.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.dto.PriceDtos.ApproveRequest;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.RejectRequest;
import com.grocery.pricing.dto.PriceDtos.RepublishResult;
import com.grocery.pricing.dto.PriceExports;
import com.grocery.pricing.service.PriceChangeDecisionService;
import com.grocery.pricing.service.PriceQueryService;
import com.grocery.pricing.service.RepublishService;
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
 * Admin price-change approval (contract §4): any admin role may read; SUPER_ADMIN, OPS_ADMIN and FINANCE_ADMIN
 * decide; SUPER_ADMIN / OPS_ADMIN republish.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminPricingController {

    /** SpEL for {@code @PreAuthorize}: who may approve or reject a price change. */
    public static final String PRICE_DECISIONS = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','FINANCE_ADMIN')";

    private final PriceQueryService queries;
    private final PriceChangeDecisionService decisions;
    private final RepublishService republish;
    private final Exporter exporter;

    public AdminPricingController(PriceQueryService queries, PriceChangeDecisionService decisions,
                                  RepublishService republish, Exporter exporter) {
        this.queries = queries;
        this.decisions = decisions;
        this.republish = republish;
        this.exporter = exporter;
    }

    /** Default {@code status=PENDING}, oldest first; other statuses newest first. */
    @GetMapping("/price-change-requests")
    public PageResponse<PriceChangeRequestView> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) UUID vendorId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return queries.adminList(status, vendorId, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, in the list's order (the
     * PENDING queue oldest first, other statuses newest first) or by {@code sort} ({@code current} | {@code requested}
     * | {@code change} | {@code requestedAt}, then {@code ,asc} | {@code ,desc}).
     */
    @GetMapping("/price-change-requests/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID vendorId,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PriceExports.ADMIN_REQUESTS,
                queries.adminListExport(status, vendorId, sort),
                Exporter.filters("Status", status == null || status.isBlank() ? "PENDING" : status,
                        "Vendor ID", vendorId));
    }

    @GetMapping("/price-change-requests/{id}")
    public PriceChangeRequestView get(@PathVariable UUID id) {
        return queries.adminGet(id);
    }

    @PostMapping("/price-change-requests/{id}/approve")
    @PreAuthorize(PRICE_DECISIONS)
    public PriceChangeRequestView approve(@PathVariable UUID id, CurrentUser user,
                                          @Valid @RequestBody(required = false) ApproveRequest request) {
        return decisions.approve(id, request == null ? null : request.note(), user);
    }

    @PostMapping("/price-change-requests/{id}/reject")
    @PreAuthorize(PRICE_DECISIONS)
    public PriceChangeRequestView reject(@PathVariable UUID id, CurrentUser user,
                                         @Valid @RequestBody RejectRequest request) {
        return decisions.reject(id, request.reason(), user);
    }

    /** Re-sends PriceChanged for every live price (rebuilds prices in search); versions unchanged. */
    @PostMapping("/pricing/republish")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RepublishResult republish() {
        return republish.republishAll();
    }
}
