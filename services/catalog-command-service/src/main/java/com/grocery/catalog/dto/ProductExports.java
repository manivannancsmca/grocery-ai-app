package com.grocery.catalog.dto;

import com.grocery.catalog.dto.ProductDtos.AdminProductView;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.entity.Product;
import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;

/**
 * The CSV / Excel / PDF exports of the product lists: the vendor's own catalog and the admin product browser. Each
 * shows the same fields as its list (never more); images, descriptions and attributes stay on the product page.
 */
public final class ProductExports {

    /** The category path as the pages show it (root › leaf). */
    private static final String PATH_SEPARATOR = " › ";

    private ProductExports() {
    }

    /**
     * Most recently changed first, like the lists; a client may sort by name, status or last change (never-null
     * columns; the stored status codes sort like their labels).
     */
    public static final ExportSort<Product> SORT = ExportSort.<Product>by(
                    Key.desc("updatedAt", Product::getUpdatedAt), Key.desc("id", Product::getId))
            .option("product", "name", Product::getName)
            .option("status", "status", Product::getStatus)
            .option("updated", "updatedAt", Product::getUpdatedAt)
            .build();

    /** {@code GET /api/vendor/products/export}: the vendor's own products. */
    public static final ExportTable<ProductView> VENDOR_PRODUCTS = ExportTable.<ProductView>named(
                    "my-products", "My products")
            .text("Product", ProductView::name)
            .text("SKU", ProductView::sku)
            .text("Brand", view -> view.brand() == null ? null : view.brand().name())
            .text("Category", ProductExports::category)
            .text("Unit", ProductView::unitLabel)
            .label("Food type", ProductView::foodType)
            .text("Barcode", ProductView::barcode)
            .text("Tags", ProductView::tags)
            .label("Status", ProductView::status)
            .dateTime("Published", ProductView::publishedAt)
            .dateTime("Created", ProductView::createdAt)
            .dateTime("Updated", ProductView::updatedAt)
            .build();

    /** {@code GET /api/admin/products/export}: every vendor's products with the vendor (no personal data). */
    public static final ExportTable<AdminProductView> ADMIN_PRODUCTS = ExportTable.<AdminProductView>named(
                    "products", "Products")
            .text("Product", row -> row.product().name())
            .text("SKU", row -> row.product().sku())
            .text("Brand", row -> row.product().brand() == null ? null : row.product().brand().name())
            .text("Vendor", AdminProductView::vendorName)
            .text("Vendor ID", row -> row.product().vendorId())
            .text("Category", row -> category(row.product()))
            .text("Unit", row -> row.product().unitLabel())
            .label("Food type", row -> row.product().foodType())
            .text("Barcode", row -> row.product().barcode())
            .label("Status", row -> row.product().status())
            .dateTime("Published", row -> row.product().publishedAt())
            .dateTime("Created", row -> row.product().createdAt())
            .dateTime("Updated", row -> row.product().updatedAt())
            .build();

    /** Root › leaf; empty for an archived product whose category was deleted later. */
    private static String category(ProductView view) {
        return view.category() == null ? null : String.join(PATH_SEPARATOR, view.category().path());
    }
}
