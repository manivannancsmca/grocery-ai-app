package com.grocery.catalogquery.index;

/**
 * The three search indexes. Code always reads and writes the {@link #alias()}; the concrete, versioned index
 * behind it ({@link #index()}) can be rebuilt as {@code _v2} and switched over without touching callers.
 */
public enum IndexDefinition {

    PRODUCTS("products", "products_v1", "elasticsearch/products.json"),
    RESTAURANTS("restaurants", "restaurants_v1", "elasticsearch/restaurants.json"),
    CATEGORIES("categories", "categories_v1", "elasticsearch/categories.json");

    private final String alias;
    private final String index;
    private final String resource;

    IndexDefinition(String alias, String index, String resource) {
        this.alias = alias;
        this.index = index;
        this.resource = resource;
    }

    public String alias() {
        return alias;
    }

    public String index() {
        return index;
    }

    /** Classpath location of the settings + mappings JSON. */
    public String resource() {
        return resource;
    }
}
