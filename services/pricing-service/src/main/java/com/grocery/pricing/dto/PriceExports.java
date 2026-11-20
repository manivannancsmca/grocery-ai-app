package com.grocery.pricing.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.ProductRef;

/**
 * The CSV / Excel / PDF exports of the price lists: the vendor's prices, the vendor's own price-change requests and
 * the admin approval queue. Each shows the same fields as its list, never more.
 */
public final class PriceExports {

    private PriceExports() {
    }

    /** The vendor's prices: by product name like the list (the page has no sortable columns). */
    public static final ExportSort<ProductRef> PRICE_SORT = ExportSort.<ProductRef>fixed(
            Key.asc("name", ProductRef::getName), Key.asc("productId", ProductRef::getProductId));

    /** The admin queue (PENDING): the oldest request first, like the list; or by a column the page sorts by. */
    public static final ExportSort<PriceChangeRequest> PENDING_SORT = requestSort(
            Key.asc("requestedAt", PriceChangeRequest::getRequestedAt), Key.asc("id", PriceChangeRequest::getId));

    /**
     * Decided requests (the admin's other tabs) and the vendor's own requests: the newest request first, like the
     * lists; admins may choose a column the page sorts by.
     */
    public static final ExportSort<PriceChangeRequest> NEWEST_SORT = requestSort(
            Key.desc("requestedAt", PriceChangeRequest::getRequestedAt), Key.desc("id", PriceChangeRequest::getId));

    /** {@code GET /api/vendor/prices/export}: the vendor's products with their live and pending prices. */
    public static final ExportTable<PriceView> PRICES = ExportTable.<PriceView>named("prices", "Prices")
            .text("Product", PriceView::productName)
            .text("SKU", PriceView::sku)
            .text("Unit", PriceView::unitLabel)
            .label("Product status", PriceView::productStatus)
            .money("MRP (INR)", view -> view.price() == null ? null : view.price().mrp())
            .money("Selling price (INR)", view -> view.price() == null ? null : view.price().sellingPrice())
            .integer("Discount (%)", view -> view.price() == null ? null : view.price().discountPercent())
            .money("Pending MRP (INR)", view -> view.pendingRequest() == null ? null
                    : view.pendingRequest().requestedMrp())
            .money("Pending selling price (INR)", view -> view.pendingRequest() == null ? null
                    : view.pendingRequest().requestedSellingPrice())
            .dateTime("Price updated", view -> view.price() == null ? null : view.price().updatedAt())
            .build();

    /** {@code GET /api/admin/price-change-requests/export}: the approval queue, with the vendor (no personal data). */
    public static final ExportTable<PriceChangeRequestView> ADMIN_REQUESTS = requests(
            "price-change-requests", "Price change requests", true);

    /** {@code GET /api/vendor/price-change-requests/export}: the vendor's own requests. */
    public static final ExportTable<PriceChangeRequestView> VENDOR_REQUESTS = requests(
            "my-price-change-requests", "My price change requests", false);

    private static ExportSort<PriceChangeRequest> requestSort(Key<PriceChangeRequest> requestedAt,
                                                              Key<PriceChangeRequest> id) {
        return ExportSort.by(requestedAt, id)
                .option("current", "currentSellingPrice", PriceChangeRequest::getCurrentSellingPrice)
                .option("requested", "requestedSellingPrice", PriceChangeRequest::getRequestedSellingPrice)
                .option("change", "changePercent", PriceChangeRequest::getChangePercent)
                .option("requestedAt", "requestedAt", PriceChangeRequest::getRequestedAt)
                .build();
    }

    private static ExportTable<PriceChangeRequestView> requests(String name, String title, boolean withVendor) {
        ExportTable.Builder<PriceChangeRequestView> table = ExportTable.<PriceChangeRequestView>named(name, title)
                .text("Product", PriceChangeRequestView::productName)
                .text("Unit", PriceChangeRequestView::unitLabel);
        if (withVendor) {
            table.text("Vendor", PriceChangeRequestView::vendorName)
                    .text("Vendor ID", PriceChangeRequestView::vendorId);
        }
        return table
                .money("MRP before (INR)", PriceChangeRequestView::currentMrp)
                .money("Price before (INR)", PriceChangeRequestView::currentSellingPrice)
                .money("Requested MRP (INR)", PriceChangeRequestView::requestedMrp)
                .money("Requested price (INR)", PriceChangeRequestView::requestedSellingPrice)
                .decimal("Change (%)", PriceChangeRequestView::changePercent)
                .label("Status", PriceChangeRequestView::status)
                .text(withVendor ? "Vendor note" : "Note", PriceChangeRequestView::note)
                .dateTime("Requested", PriceChangeRequestView::requestedAt)
                .dateTime("Decided", PriceChangeRequestView::decidedAt)
                .text("Decision reason", PriceChangeRequestView::decisionReason)
                .build();
    }
}
