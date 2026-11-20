package com.grocery.pricing.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.dto.PriceDtos;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceHistoryEntry;
import com.grocery.pricing.dto.PriceDtos.PriceRequest;
import com.grocery.pricing.dto.PriceDtos.PriceSummary;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.dto.PriceExports;
import com.grocery.pricing.service.PriceQueryService;
import com.grocery.pricing.service.VendorPriceService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in vendor's prices and price-change requests (contract §3). Only approved vendors: the
 * {@code vendor_id} token claim is required (403 VENDOR_NOT_ACTIVE) and is the only source of ownership.
 */
@RestController
@RequestMapping("/api/vendor")
@PreAuthorize("hasRole('VENDOR')")
public class VendorPriceController {

    private final VendorPriceService commands;
    private final PriceQueryService queries;
    private final Exporter exporter;

    public VendorPriceController(VendorPriceService commands, PriceQueryService queries, Exporter exporter) {
        this.commands = commands;
        this.queries = queries;
        this.exporter = exporter;
    }

    @GetMapping("/prices")
    public PageResponse<PriceView> list(CurrentUser user,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String filter,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return queries.vendorList(user, q, filter, page, size);
    }

    /**
     * The caller's prices as a file ({@code format} = csv | xlsx | pdf), with the list's search and filter, by product
     * name; the vendor is the caller's own (from the JWT), never a parameter.
     */
    @GetMapping("/prices/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String filter,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PriceExports.PRICES, queries.vendorListExport(user, q, filter),
                Exporter.filters("Search", q, "Show", filter));
    }

    @GetMapping("/prices/summary")
    public PriceSummary summary(CurrentUser user) {
        return queries.vendorSummary(user);
    }

    @GetMapping("/prices/{productId}")
    public PriceView get(CurrentUser user, @PathVariable UUID productId) {
        return queries.vendorGet(user, productId);
    }

    /** 200 + {@code outcome APPLIED} (live now) or 202 + {@code outcome PENDING_APPROVAL} (waits for an admin). */
    @PutMapping("/prices/{productId}")
    public ResponseEntity<PriceView> setPrice(CurrentUser user, @PathVariable UUID productId,
                                              @Valid @RequestBody PriceRequest request) {
        PriceView view = commands.setPrice(user, productId, request);
        HttpStatus status = PriceDtos.PENDING_APPROVAL.equals(view.outcome()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping("/prices/{productId}/history")
    public PageResponse<PriceHistoryEntry> history(CurrentUser user, @PathVariable UUID productId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return queries.vendorHistory(user, productId, page, size);
    }

    @GetMapping("/price-change-requests")
    public PageResponse<PriceChangeRequestView> requests(CurrentUser user,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return queries.vendorRequests(user, status, page, size);
    }

    /** The caller's price-change requests as a file ({@code format} = csv | xlsx | pdf), newest first. */
    @GetMapping("/price-change-requests/export")
    public void requestsExport(CurrentUser user,
                               @RequestParam(required = false) String format,
                               @RequestParam(required = false) String status,
                               HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PriceExports.VENDOR_REQUESTS,
                queries.vendorRequestsExport(user, status), Exporter.filters("Status", status));
    }

    @PostMapping("/price-change-requests/{id}/cancel")
    public PriceChangeRequestView cancel(CurrentUser user, @PathVariable UUID id) {
        return commands.cancel(user, id);
    }
}
