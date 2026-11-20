package com.grocery.restaurant.controller;

import com.grocery.restaurant.dto.MenuQuoteDtos.MenuQuote;
import com.grocery.restaurant.dto.MenuQuoteDtos.MenuQuoteRequest;
import com.grocery.restaurant.service.MenuQuoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service restaurant reads (docs/api/phase-5-cart-and-checkout-preview.md §4). Not routed by the
 * gateway; any authenticated caller (callers forward the user's token).
 */
@RestController
@RequestMapping("/internal/restaurants")
public class InternalRestaurantController {

    private final MenuQuoteService quotes;

    public InternalRestaurantController(MenuQuoteService quotes) {
        this.quotes = quotes;
    }

    /** 1–100 menu item ids → MenuQuote · 404 RESTAURANT_NOT_FOUND. */
    @PostMapping("/{restaurantId}/menu-quote")
    public MenuQuote menuQuote(@PathVariable UUID restaurantId, @Valid @RequestBody MenuQuoteRequest request) {
        return quotes.quote(restaurantId, request.itemIds());
    }
}
