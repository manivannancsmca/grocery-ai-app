package com.grocery.restaurant.mapper;

import com.grocery.restaurant.dto.StoreOrderDtos.CancellationView;
import com.grocery.restaurant.dto.StoreOrderDtos.CustomerView;
import com.grocery.restaurant.dto.StoreOrderDtos.ItemView;
import com.grocery.restaurant.dto.StoreOrderDtos.RejectionView;
import com.grocery.restaurant.dto.StoreOrderDtos.StoreOrderView;
import com.grocery.restaurant.dto.StoreOrderDtos.TimelineEntry;
import com.grocery.restaurant.entity.RestaurantOrder;
import com.grocery.restaurant.entity.StoreOrderStatus;

/** RestaurantOrder → StoreOrderView (plain Java; call inside the reading transaction: lines and history are lazy). */
public final class StoreOrderMapper {

    private StoreOrderMapper() {
    }

    public static StoreOrderView toView(RestaurantOrder order) {
        RejectionView rejection = order.getStatus() == StoreOrderStatus.REJECTED
                ? new RejectionView(order.getRejectionReason().name(), order.getRejectionNote(), order.isRejectionAuto(),
                order.getRejectedAt())
                : null;
        CancellationView cancellation = order.getStatus() == StoreOrderStatus.CANCELLED
                ? new CancellationView(order.getCancellationReason(), order.getCancelledBy(), order.getCancelledAt())
                : null;
        return new StoreOrderView(order.getOrderId(), order.getOrderNumber(), order.getRestaurantId(),
                order.getStatus().name(),
                order.getItems().stream().map(item -> new ItemView(item.getItemId(), item.getName(), item.getQuantity(),
                        item.getUnitPrice(), item.getLineTotal())).toList(),
                order.getItemCount(), order.getSubtotal(), order.getTotal(), order.getCurrency(),
                new CustomerView(order.getCustomerName(), order.getCustomerArea()), order.getNote(),
                order.getReceivedAt(), order.getAcceptBy(), order.getAcceptedAt(), order.getReadyInMinutes(),
                order.getReadyBy(), order.getPreparingAt(), order.getReadyAt(), rejection, cancellation,
                order.getHistory().stream().map(change -> new TimelineEntry(change.getStatus().name(),
                        change.getChangedAt())).toList());
    }
}
