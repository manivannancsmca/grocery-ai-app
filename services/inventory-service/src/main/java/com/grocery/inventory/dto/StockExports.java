package com.grocery.inventory.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.repository.StockRow;

/** The CSV / Excel / PDF export of the vendor's stock list. */
public final class StockExports {

    private StockExports() {
    }

    /**
     * By product name like the list (JPQL paths: {@code s} the stock row, {@code p} its product); the page has no
     * sortable columns, so this order is fixed.
     */
    public static final ExportSort<StockRow> SORT = ExportSort.<StockRow>fixed(
            Key.asc("p.name", row -> row.product().getName()),
            Key.asc("p.productId", row -> row.product().getProductId()));

    /** {@code GET /api/vendor/inventory/export}: the vendor's own stock, as the list shows it. */
    public static final ExportTable<StockView> STOCK = ExportTable.<StockView>named("inventory", "Inventory")
            .text("Product", StockView::productName)
            .text("SKU", StockView::sku)
            .text("Unit", StockView::unitLabel)
            .integer("On hand", StockView::onHand)
            .integer("Reserved", StockView::reserved)
            .integer("Available", StockView::available)
            .integer("Alert level", StockView::lowStockThreshold)
            .text("Stock", StockExports::stock)
            .label("Product status", StockView::productStatus)
            .dateTime("Updated", StockView::updatedAt)
            .build();

    /** As the page's badge: out of stock, low stock (at or below the alert level) or in stock. */
    private static String stock(StockView view) {
        return !view.inStock() ? "Out of stock" : view.lowStock() ? "Low stock" : "In stock";
    }
}
