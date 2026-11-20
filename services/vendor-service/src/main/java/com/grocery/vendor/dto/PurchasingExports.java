package com.grocery.vendor.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingDtos.SupplierView;
import com.grocery.vendor.entity.PurchaseOrder;

/**
 * The CSV / Excel / PDF exports of the purchasing lists: the vendor's suppliers, the vendor's purchase orders and the
 * admins' read-only list of every vendor's purchase orders. Each shows the same fields as its list (never more).
 */
public final class PurchasingExports {

    private PurchasingExports() {
    }

    /**
     * Purchase orders: newest first like the lists (JPQL paths, alias {@code p}); a client may sort by number or
     * creation time (never-null columns). The total is computed from the lines, not a column: it cannot order a keyset.
     */
    public static final ExportSort<PurchaseOrder> PURCHASE_ORDER_SORT = ExportSort.<PurchaseOrder>by(
                    Key.desc("p.createdAt", PurchaseOrder::getCreatedAt), Key.desc("p.id", PurchaseOrder::getId))
            .option("number", "p.number", PurchaseOrder::getNumber)
            .option("created", "p.createdAt", PurchaseOrder::getCreatedAt)
            .build();

    /** {@code GET /api/admin/purchase-orders/export}: every vendor's purchase orders (the vendor by id, as listed). */
    public static final ExportTable<PurchaseOrderView> ADMIN_PURCHASE_ORDERS = purchaseOrders(true);

    /** {@code GET /api/vendor/purchase-orders/export}: the vendor's own purchase orders. */
    public static final ExportTable<PurchaseOrderView> PURCHASE_ORDERS = purchaseOrders(false);

    /**
     * {@code GET /api/vendor/suppliers/export}: the vendor's suppliers. Their contact details are the vendor's own
     * business data (the people the vendor buys from), shown on the list as they are here.
     */
    public static final ExportTable<SupplierView> SUPPLIERS = ExportTable.<SupplierView>named("suppliers", "Suppliers")
            .text("Supplier", SupplierView::name)
            .text("GSTIN", SupplierView::gstin)
            .text("Contact person", SupplierView::contactName)
            .text("Phone", SupplierView::phone)
            .text("Email", SupplierView::email)
            .text("Address", SupplierView::address)
            .integer("Lead time (days)", SupplierView::leadTimeDays)
            .yesNo("Active", SupplierView::active)
            .text("Notes", SupplierView::notes)
            .dateTime("Added", SupplierView::createdAt)
            .dateTime("Last change", SupplierView::updatedAt)
            .build();

    private static ExportTable<PurchaseOrderView> purchaseOrders(boolean withVendor) {
        ExportTable.Builder<PurchaseOrderView> table = ExportTable.<PurchaseOrderView>named(
                "purchase-orders", "Purchase orders").text("PO number", PurchaseOrderView::number);
        if (withVendor) {
            table.text("Vendor ID", PurchaseOrderView::vendorId);
        }
        return table.text("Supplier", PurchaseOrderView::supplierName)
                .label("Status", PurchaseOrderView::status)
                .integer("Products", view -> view.lines().size())
                .integer("Units ordered", PurchaseOrderView::orderedUnits)
                .integer("Units received", PurchaseOrderView::receivedUnits)
                .money("Total (INR)", PurchaseOrderView::totalCost)
                .money("Received value (INR)", PurchaseOrderView::receivedCost)
                .date("Expected", PurchaseOrderView::expectedAt)
                .text("Notes", PurchaseOrderView::notes)
                .dateTime("Created", PurchaseOrderView::createdAt)
                .dateTime("Sent", PurchaseOrderView::sentAt)
                .dateTime("Fully received", PurchaseOrderView::fullyReceivedAt)
                .dateTime("Closed", PurchaseOrderView::closedAt)
                .dateTime("Cancelled", PurchaseOrderView::cancelledAt)
                .build();
    }
}
