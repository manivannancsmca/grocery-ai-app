package com.grocery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A free-form "name: value" fact about a product (e.g. Origin: Nashik). */
@Embeddable
public class ProductAttribute {

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "value", nullable = false, length = 200)
    private String value;

    protected ProductAttribute() {
    }

    public ProductAttribute(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }
}
