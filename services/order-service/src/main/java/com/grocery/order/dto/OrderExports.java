package com.grocery.order.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.order.dto.OrderDtos.AdminOrderSummary;
import com.grocery.order.dto.OrderDtos.OrderSummary;
import com.grocery.order.dto.ReturnDtos.ReturnItemView;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderReturn;

import java.util.stream.Collectors;

/**
 * The CSV / Excel / PDF exports of the order and return lists: their columns and the orders a client may choose.
 * Each export shows the same fields as its list (never more): the customer's own orders, the store's returns, the
 * admin monitor (customer by id only — names and contact details live in customer-service).
 */
public final class OrderExports {

    private OrderExports() {
    }

    /** Orders: newest first like the lists; a client may sort by order number or total (never-null columns). */
    public static final ExportSort<CustomerOrder> ORDER_SORT = ExportSort.<CustomerOrder>by(
                    Key.desc("createdAt", CustomerOrder::getCreatedAt), Key.desc("id", CustomerOrder::getId))
            .option("order", "orderNumber", CustomerOrder::getOrderNumber)
            .option("total", "total", CustomerOrder::getTotal)
            .build();

    /** Returns: newest request first; a client may sort by return number or refund. */
    public static final ExportSort<OrderReturn> RETURN_SORT = ExportSort.<OrderReturn>by(
                    Key.desc("requestedAt", OrderReturn::getRequestedAt), Key.desc("id", OrderReturn::getId))
            .option("return", "returnNumber", OrderReturn::getReturnNumber)
            .option("refund", "refundAmount", OrderReturn::getRefundAmount)
            .build();

    /** {@code GET /api/orders/export}: the customer's own orders. */
    public static final ExportTable<OrderSummary> MY_ORDERS = ExportTable.<OrderSummary>named("my-orders", "My orders")
            .text("Order number", OrderSummary::orderNumber)
            .dateTime("Placed", OrderSummary::createdAt)
            .text("Type", OrderSummary::type)
            .text("Store", order -> order.store() == null ? null : order.store().name())
            .integer("Items", OrderSummary::itemCount)
            .text("First item", OrderSummary::firstItemName)
            .text("Status", OrderSummary::status)
            .text("Payment", order -> order.payment() == null ? null : order.payment().status())
            .text("Coupon", OrderSummary::couponCode)
            .money("Discount (INR)", OrderSummary::discount)
            .money("Total (INR)", OrderSummary::total)
            .build();

    /** {@code GET /api/admin/orders/export}: the admin order monitor. */
    public static final ExportTable<AdminOrderSummary> ADMIN_ORDERS = ExportTable.<AdminOrderSummary>named(
                    "orders", "Orders")
            .text("Order number", row -> row.order().orderNumber())
            .dateTime("Placed", row -> row.order().createdAt())
            .text("Type", row -> row.order().type())
            .text("Store", row -> row.order().store() == null ? null : row.order().store().name())
            .text("Store ID", row -> row.order().store() == null ? null : row.order().store().id())
            .text("Customer ID", AdminOrderSummary::customerId)
            .integer("Items", row -> row.order().itemCount())
            .text("Status", row -> row.order().status())
            .text("Payment", row -> row.order().payment() == null ? null : row.order().payment().status())
            .text("Coupon", row -> row.order().couponCode())
            .money("Discount (INR)", row -> row.order().discount())
            .money("Delivery fee discount (INR)", row -> row.order().deliveryFeeDiscount())
            .money("Total (INR)", row -> row.order().total())
            .build();

    /** The returns (customer, store and admin lists): the same columns for everyone who may see the list. */
    public static ExportTable<ReturnView> returns(String name, String title) {
        return ExportTable.<ReturnView>named(name, title)
                .text("Return number", ReturnView::returnNumber)
                .dateTime("Requested", ReturnView::requestedAt)
                .text("Order number", ReturnView::orderNumber)
                .text("Type", ReturnView::orderType)
                .text("Store", ReturnView::storeName)
                .text("Status", ReturnView::status)
                .text("Reason", ReturnView::reason)
                .text("Items", OrderExports::items)
                .money("Refund (INR)", ReturnView::refundAmount)
                .yesNo("Restock", ReturnView::restock)
                .dateTime("Decide by", ReturnView::decideBy)
                .dateTime("Decided", ReturnView::decidedAt)
                .text("Decided by", ReturnView::decidedByRole)
                .text("Comment", ReturnView::comment)
                .dateTime("Refunded", ReturnView::refundedAt)
                .build();
    }

    private static String items(ReturnView view) {
        return view.items() == null ? null : view.items().stream()
                .map(OrderExports::item)
                .collect(Collectors.joining("; "));
    }

    private static String item(ReturnItemView item) {
        return item.quantity() + " × " + item.name();
    }
}
