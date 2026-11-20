package com.grocery.catalogquery.controller;

import com.grocery.catalogquery.dto.CatalogDtos.ProductSearchResponse;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantSearchResponse;
import com.grocery.catalogquery.dto.CatalogDtos.SuggestResponse;
import com.grocery.catalogquery.service.ProductSearchService;
import com.grocery.catalogquery.service.ProductSearchService.FoodType;
import com.grocery.catalogquery.service.ProductSearchService.Sort;
import com.grocery.catalogquery.service.RestaurantSearchService;
import com.grocery.catalogquery.service.SearchPage;
import com.grocery.common.web.BadRequestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Public search (no token needed): products, suggestions and restaurants. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ProductSearchService products;
    private final RestaurantSearchService restaurants;

    public SearchController(ProductSearchService products, RestaurantSearchService restaurants) {
        this.products = products;
        this.restaurants = restaurants;
    }

    /** {@code vendorId} is repeatable (products of any of those stores, at most 50 values); one value as before. */
    @GetMapping("/products")
    public ProductSearchResponse searchProducts(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) UUID categoryId,
                                                @RequestParam(required = false) UUID brandId,
                                                @RequestParam(required = false) List<UUID> vendorId,
                                                @RequestParam(required = false) String foodType,
                                                @RequestParam(required = false) Boolean inStock,
                                                @RequestParam(required = false) BigDecimal minPrice,
                                                @RequestParam(required = false) BigDecimal maxPrice,
                                                @RequestParam(required = false) String sort,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        ProductSearchService.validatePriceRange(minPrice, maxPrice);
        return products.search(new ProductSearchService.Criteria(q,
                text(categoryId), text(brandId), ProductSearchService.vendorIds(vendorId),
                BadRequestException.parseEnum(FoodType.class, "foodType", foodType),
                Boolean.TRUE.equals(inStock), minPrice, maxPrice,
                parseSort(sort),
                SearchPage.of(page, size)));
    }

    @GetMapping("/suggest")
    public SuggestResponse suggest(@RequestParam(required = false) String q) {
        return products.suggest(q);
    }

    @GetMapping("/restaurants")
    public RestaurantSearchResponse searchRestaurants(@RequestParam(required = false) Double lat,
                                                      @RequestParam(required = false) Double lng,
                                                      @RequestParam(required = false) Double radiusKm,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) List<String> cuisine,
                                                      @RequestParam(required = false) Boolean pureVeg,
                                                      @RequestParam(required = false) Boolean openNow,
                                                      @RequestParam(required = false) Double minRating,
                                                      @RequestParam(required = false) String sort,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return restaurants.search(new RestaurantSearchService.Criteria(q,
                RestaurantSearchService.location(lat, lng, radiusKm),
                cuisine,
                Boolean.TRUE.equals(pureVeg),
                Boolean.TRUE.equals(openNow),
                RestaurantSearchService.minRating(minRating),
                parseRestaurantSort(sort),
                SearchPage.of(page, size)));
    }

    /** rating (case-insensitive, Phase 12); blank = relevance / distance / name as before. */
    private static RestaurantSearchService.Sort parseRestaurantSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        return BadRequestException.parseEnum(RestaurantSearchService.Sort.class, "sort",
                sort.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * relevance | newest | name_asc | price_asc | price_desc | rating (case-insensitive); blank = default for the
     * query.
     */
    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        return BadRequestException.parseEnum(Sort.class, "sort", sort.strip().toUpperCase(Locale.ROOT));
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
