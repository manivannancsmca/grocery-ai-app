package com.grocery.delivery.dispatch.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminDeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliverySummary;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DropView;
import com.grocery.delivery.dispatch.entity.Delivery;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The CSV / Excel / PDF exports of the delivery lists (the admin monitor, a partner's history). */
public final class DeliveryExports {

    private DeliveryExports() {
    }

    /** The admin monitor: newest first like the list, or by order number / total (JPQL paths, alias {@code d}). */
    public static final ExportSort<Delivery> ADMIN_SORT = ExportSort.<Delivery>by(
                    Key.desc("d.createdAt", Delivery::getCreatedAt), Key.desc("d.id", Delivery::getId))
            .option("order", "d.orderNumber", Delivery::getOrderNumber)
            .option("total", "d.total", Delivery::getTotal)
            .build();

    /** A partner's history: the most recently finished first, like the list. */
    public static final ExportSort<Delivery> HISTORY_SORT = ExportSort.fixed(
            Key.desc("d.finishedAt", Delivery::getFinishedAt), Key.desc("d.id", Delivery::getId));

    /**
     * {@code GET /api/admin/deliveries/export}. The recipient's name, phone and street are personal: only support and
     * operations admins get them ({@link ExportTable#visibleTo}); the area (city, postal code) is always there.
     */
    public static final ExportTable<AdminDeliveryView> ADMIN_DELIVERIES = ExportTable.<AdminDeliveryView>named(
                    "deliveries", "Deliveries")
            .text("Order number", AdminDeliveryView::orderNumber)
            .label("Type", AdminDeliveryView::orderType)
            .label("Status", AdminDeliveryView::status)
            .text("Store", view -> view.pickup() == null ? null : view.pickup().name())
            .text("Store city", view -> view.pickup() == null ? null : view.pickup().city())
            .text("Drop area", view -> area(view.drop()))
            .text("Recipient", view -> view.drop() == null ? null : view.drop().recipientName()).personal()
            .text("Recipient phone", view -> view.drop() == null ? null : view.drop().phone()).personal()
            .text("Drop address", view -> street(view.drop())).personal()
            .integer("Items", AdminDeliveryView::itemCount)
            .money("Order total (INR)", AdminDeliveryView::total)
            .text("Partner", AdminDeliveryView::partnerName)
            .text("Partner ID", AdminDeliveryView::partnerId)
            .yesNo("Assignment failed", AdminDeliveryView::assignmentFailed)
            .integer("Offers sent", AdminDeliveryView::offersSent)
            .dateTime("Ready by", AdminDeliveryView::readyBy)
            .dateTime("Assigned", AdminDeliveryView::assignedAt)
            .dateTime("Picked up", AdminDeliveryView::pickedUpAt)
            .dateTime("Delivered", AdminDeliveryView::deliveredAt)
            .dateTime("Failed", AdminDeliveryView::failedAt)
            .label("Failure reason", view -> view.failure() == null ? null : view.failure().reason())
            .build();

    /** {@code GET /api/delivery/history/export}: the partner's own finished deliveries (no customer details). */
    public static final ExportTable<DeliverySummary> PARTNER_HISTORY = ExportTable.<DeliverySummary>named(
                    "my-deliveries", "My deliveries")
            .text("Order number", DeliverySummary::orderNumber)
            .label("Status", DeliverySummary::status)
            .text("Store", DeliverySummary::store)
            .text("Drop area", DeliverySummary::dropArea)
            .decimal("Trip (km)", DeliverySummary::tripKm)
            .dateTime("Picked up", DeliverySummary::pickedUpAt)
            .dateTime("Finished", DeliverySummary::finishedAt)
            .build();

    private static String area(DropView drop) {
        return drop == null ? null : join(drop.city(), drop.postalCode());
    }

    private static String street(DropView drop) {
        return drop == null ? null : join(drop.line1(), drop.line2(), drop.landmark());
    }

    private static String join(String... parts) {
        String joined = Stream.of(parts).filter(Objects::nonNull).map(String::trim).filter(part -> !part.isEmpty())
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }
}
