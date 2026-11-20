package com.grocery.catalogquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Catalog read side (Phase 3): product and restaurant search, suggestions, the category tree and the public
 * product/restaurant pages. Elasticsearch is its only store; it is filled from catalog.events and
 * restaurant.events. Prices and stock (Phase 4) and ratings (Phase 12) are added to the same documents later.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatalogQueryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogQueryServiceApplication.class, args);
    }
}
