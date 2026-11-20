package com.grocery.catalogquery.controller;

import com.grocery.catalogquery.dto.CatalogDtos.ProductLookupRequest;
import com.grocery.catalogquery.dto.CatalogDtos.ProductLookupResponse;
import com.grocery.catalogquery.service.ProductSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service catalog reads (docs/api/phase-5-cart-and-checkout-preview.md §4). Not routed by the gateway;
 * unlike {@code /api/search/**} and {@code /api/catalog/**} these paths are NOT public: any authenticated caller
 * (callers forward the user's token).
 */
@RestController
@RequestMapping("/internal/catalog")
public class InternalCatalogController {

    private final ProductSearchService products;

    public InternalCatalogController(ProductSearchService products) {
        this.products = products;
    }

    /** 1–100 product ids → cards of the visible ones + the ids that are not visible (unknown or hidden). */
    @PostMapping("/products/lookup")
    public ProductLookupResponse lookup(@Valid @RequestBody ProductLookupRequest request) {
        return products.lookup(request.productIds());
    }
}
