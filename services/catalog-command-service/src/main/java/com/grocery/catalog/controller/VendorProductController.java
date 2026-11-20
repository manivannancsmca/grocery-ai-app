package com.grocery.catalog.controller;

import com.grocery.catalog.dto.ProductDtos.BrandView;
import com.grocery.catalog.dto.ProductDtos.ProductRequest;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.dto.ProductExports;
import com.grocery.catalog.service.BrandService;
import com.grocery.catalog.service.ProductQueryService;
import com.grocery.catalog.service.VendorProductService;
import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
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
 * The signed-in vendor's products (contract §3). Only approved vendors: the {@code vendor_id} token claim
 * is required (403 VENDOR_NOT_ACTIVE) and is the only source of ownership.
 */
@RestController
@RequestMapping("/api/vendor")
@PreAuthorize("hasRole('VENDOR')")
public class VendorProductController {

    private final VendorProductService commands;
    private final ProductQueryService queries;
    private final BrandService brands;
    private final Exporter exporter;

    public VendorProductController(VendorProductService commands, ProductQueryService queries, BrandService brands,
                                   Exporter exporter) {
        this.commands = commands;
        this.queries = queries;
        this.brands = brands;
        this.exporter = exporter;
    }

    @GetMapping("/products")
    public PageResponse<ProductView> list(CurrentUser user,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return queries.vendorList(user, status, q, page, size);
    }

    /**
     * The caller's products as a file ({@code format} = csv | xlsx | pdf), with the list's filters, most recently
     * changed first; the vendor is the caller's own (from the JWT), never a parameter.
     */
    @GetMapping("/products/export")
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ProductExports.VENDOR_PRODUCTS,
                queries.vendorExport(user, status, q), Exporter.filters("Status", status, "Search", q));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(CurrentUser user, @Valid @RequestBody ProductRequest request) {
        return commands.create(user, request);
    }

    @GetMapping("/products/{id}")
    public ProductView get(CurrentUser user, @PathVariable UUID id) {
        return queries.vendorGet(user, id);
    }

    @PutMapping("/products/{id}")
    public ProductView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return commands.update(user, id, request);
    }

    @PostMapping("/products/{id}/publish")
    public ProductView publish(CurrentUser user, @PathVariable UUID id) {
        return commands.publish(user, id);
    }

    @PostMapping("/products/{id}/unpublish")
    public ProductView unpublish(CurrentUser user, @PathVariable UUID id) {
        return commands.unpublish(user, id);
    }

    /** Archives the product (hidden everywhere, kept for order history). */
    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(CurrentUser user, @PathVariable UUID id) {
        commands.archive(user, id);
    }

    /** Up to 10 existing brands for the brand field's suggestions. */
    @GetMapping("/brands")
    public List<BrandView> brands(CurrentUser user, @RequestParam(required = false) String q) {
        user.requireVendorId();
        return brands.suggest(q);
    }
}
