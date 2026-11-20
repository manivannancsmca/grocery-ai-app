package com.grocery.vendor.dto;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.ExportTable;
import com.grocery.vendor.dto.StoreOrderDtos.ItemView;
import com.grocery.vendor.dto.StoreOrderDtos.StoreOrderView;
import com.grocery.vendor.dto.StoreOrderDtos.TimelineEntry;
import com.grocery.vendor.entity.StoreOrderStatus;
import com.grocery.vendor.entity.VendorOrder;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * The CSV / Excel / PDF export of the vendor's order board. The store sees the customer's name and area on the board,
 * so the export has them too — never a customer's e-mail, phone or street address (the store never gets them).
 */
public final class StoreOrderExports {

    private StoreOrderExports() {
    }

    /**
     * The board's first part, its NEW orders: the most urgent deadline first, then newest first, like the board
     * (JPQL paths, alias {@code o}).
     */
    public static final Order<VendorOrder> NEW_BY_DEADLINE = Order.of(
            Key.asc("o.acceptBy", VendorOrder::getAcceptBy), Key.desc("o.receivedAt", VendorOrder::getReceivedAt),
            Key.desc("o.orderId", VendorOrder::getOrderId));

    /** The board's second part, every other status: newest first, like the board. */
    public static final Order<VendorOrder> NEWEST_FIRST = Order.of(
            Key.desc("o.receivedAt", VendorOrder::getReceivedAt), Key.desc("o.orderId", VendorOrder::getOrderId));

    /** {@code GET /api/vendor/orders/export}: the vendor's own orders. */
    public static final ExportTable<StoreOrderView> ORDERS = ExportTable.<StoreOrderView>named("orders", "Orders")
            .text("Order number", StoreOrderView::orderNumber)
            // groceries are packed: the board shows PREPARING as "Packing"
            .label("Status", view -> "PREPARING".equals(view.status()) ? "PACKING" : view.status())
            .dateTime("Received", StoreOrderView::receivedAt)
            .text("Customer", view -> view.customer() == null ? null : view.customer().name())
            .text("Customer area", view -> view.customer() == null ? null : view.customer().area())
            .integer("Items", StoreOrderView::itemCount)
            .text("Products", StoreOrderExports::products)
            .money("Subtotal (INR)", StoreOrderView::subtotal)
            .money("Total (INR)", StoreOrderView::total)
            .text("Note", StoreOrderView::note)
            .dateTime("Accept by", StoreOrderView::acceptBy)
            .dateTime("Accepted", StoreOrderView::acceptedAt)
            .dateTime("Ready by", StoreOrderView::readyBy)
            .dateTime("Packing started", StoreOrderView::preparingAt)
            .dateTime("Ready", StoreOrderView::readyAt)
            .dateTime("Picked up", StoreOrderExports::pickedUpAt)
            .label("Rejection reason", view -> view.rejection() == null ? null : view.rejection().reason())
            .yesNo("Auto-rejected", view -> view.rejection() == null ? null : view.rejection().auto())
            .text("Rejection note", view -> view.rejection() == null ? null : view.rejection().note())
            .dateTime("Rejected", view -> view.rejection() == null ? null : view.rejection().at())
            .label("Cancellation reason", view -> view.cancellation() == null ? null : view.cancellation().reason())
            .label("Cancelled by", view -> view.cancellation() == null ? null : view.cancellation().by())
            .dateTime("Cancelled", view -> view.cancellation() == null ? null : view.cancellation().at())
            .build();

    private static String products(StoreOrderView view) {
        return view.items() == null ? null : view.items().stream()
                .map(StoreOrderExports::product)
                .collect(Collectors.joining("; "));
    }

    private static String product(ItemView item) {
        return item.quantity() + " × " + item.name();
    }

    /** When the delivery partner took the order (the last PICKED_UP of its timeline), as the board shows it. */
    private static Instant pickedUpAt(StoreOrderView view) {
        if (view.timeline() == null) {
            return null;
        }
        return view.timeline().stream()
                .filter(entry -> StoreOrderStatus.PICKED_UP.name().equals(entry.status()))
                .map(TimelineEntry::at)
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
