package com.grocery.catalogquery.controller;

import com.grocery.catalogquery.dto.CatalogDtos.CategoryNode;
import com.grocery.catalogquery.dto.CatalogDtos.ProductDetail;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantDetail;
import com.grocery.catalogquery.service.CategoryTreeService;
import com.grocery.catalogquery.service.ProductSearchService;
import com.grocery.catalogquery.service.RestaurantSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public catalog pages (no token needed): category tree, product page, restaurant page. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CategoryTreeService categories;
    private final ProductSearchService products;
    private final RestaurantSearchService restaurants;

    public CatalogController(CategoryTreeService categories, ProductSearchService products,
                             RestaurantSearchService restaurants) {
        this.categories = categories;
        this.products = products;
        this.restaurants = restaurants;
    }

    @GetMapping("/categories")
    public List<CategoryNode> categories() {
        return categories.tree();
    }

    @GetMapping("/products/{id}")
    public ProductDetail product(@PathVariable String id) {
        return products.detail(id);
    }

    @GetMapping("/restaurants/{id}")
    public RestaurantDetail restaurant(@PathVariable String id) {
        return restaurants.detail(id);
    }
}
