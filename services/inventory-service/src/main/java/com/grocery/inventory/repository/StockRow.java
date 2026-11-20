package com.grocery.inventory.repository;

import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.StockItem;

/** A stock row with its product, as loaded by the vendor list query (one query, no N+1). */
public record StockRow(StockItem stock, ProductRef product) {
}
