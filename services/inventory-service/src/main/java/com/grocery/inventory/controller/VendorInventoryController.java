package com.grocery.inventory.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.inventory.dto.InventoryDtos.AdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.BulkAdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.StockMovementView;
import com.grocery.inventory.dto.InventoryDtos.StockSummary;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.dto.InventoryDtos.ThresholdRequest;
import com.grocery.inventory.dto.StockExports;
import com.grocery.inventory.service.StockAdjustmentService;
import com.grocery.inventory.service.StockQueryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in vendor's stock (contract §2). Only approved vendors: the {@code vendor_id} token claim is
 * required (403 VENDOR_NOT_ACTIVE) and is the only source of ownership.
 */
@RestController
@RequestMapping("/api/vendor/inventory")
@PreAuthorize("hasRole('VENDOR')")
public class VendorInventoryController {

    private final StockQueryService queries;
    private final StockAdjustmentService commands;
    private final Exporter exporter;

    public VendorInventoryController(StockQueryService queries, StockAdjustmentService commands, Exporter exporter) {
        this.queries = queries;
        this.commands = commands;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<StockView> list(CurrentUser user,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String filter,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return queries.list(user, q, filter, page, size);
    }

    /**
     * The caller's stock as a file ({@code format} = csv | xlsx | pdf), with the list's search and filter, by product
     * name; the vendor is the caller's own (from the JWT), never a parameter.
     */
    @GetMapping("/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String filter,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, StockExports.STOCK, queries.listExport(user, q, filter),
                Exporter.filters("Search", q, "Stock", filter));
    }

    @GetMapping("/summary")
    public StockSummary summary(CurrentUser user) {
        return queries.summary(user);
    }

    @GetMapping("/{productId}")
    public StockView get(CurrentUser user, @PathVariable UUID productId) {
        return queries.get(user, productId);
    }

    @PostMapping("/{productId}/adjustments")
    public StockView adjust(CurrentUser user, @PathVariable UUID productId,
                            @Valid @RequestBody AdjustmentRequest request) {
        return commands.adjust(user, productId, request);
    }

    /** All or nothing; the response lists the products in request order. */
    @PostMapping("/adjustments/bulk")
    public List<StockView> bulkAdjust(CurrentUser user, @Valid @RequestBody BulkAdjustmentRequest request) {
        return commands.bulkAdjust(user, request);
    }

    @PutMapping("/{productId}/threshold")
    public StockView threshold(CurrentUser user, @PathVariable UUID productId,
                               @Valid @RequestBody ThresholdRequest request) {
        return commands.changeThreshold(user, productId, request);
    }

    @GetMapping("/{productId}/movements")
    public PageResponse<StockMovementView> movements(CurrentUser user, @PathVariable UUID productId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return queries.movements(user, productId, page, size);
    }
}
