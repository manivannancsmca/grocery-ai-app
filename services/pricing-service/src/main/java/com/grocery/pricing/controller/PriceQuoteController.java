package com.grocery.pricing.controller;

import com.grocery.pricing.dto.PriceDtos.QuoteRequest;
import com.grocery.pricing.dto.PriceDtos.QuoteResponse;
import com.grocery.pricing.service.PriceQuoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price quote (Phase 5 contract §4, internal — {@code /internal/**} is never routed by the gateway): cart and
 * order ask for the current prices of up to 100 products. Any authenticated caller (the JWT is still validated
 * here: zero trust). Moved from Phase 4's {@code /api/prices/quote}; request, response and cache are unchanged.
 */
@RestController
@RequestMapping("/internal/prices")
public class PriceQuoteController {

    private final PriceQuoteService quotes;

    public PriceQuoteController(PriceQuoteService quotes) {
        this.quotes = quotes;
    }

    @PostMapping("/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        return quotes.quote(request.productIds());
    }
}
