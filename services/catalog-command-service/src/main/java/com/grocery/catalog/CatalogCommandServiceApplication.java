package com.grocery.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog command service (Phase 3): the write side of the product catalog — category tree, brands
 * and vendor products. Every change is published as a complete snapshot event through the outbox;
 * catalog-query-service turns those events into the Elasticsearch read model.
 */
@SpringBootApplication
public class CatalogCommandServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogCommandServiceApplication.class, args);
    }
}
