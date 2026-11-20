package com.grocery.order.dto;

import com.grocery.order.dto.OrderDtos.AddressView;
import com.grocery.order.dto.OrderDtos.AdminOrderSummary;
import com.grocery.order.dto.OrderDtos.AdminOrderView;
import com.grocery.order.dto.OrderDtos.CancellationView;
import com.grocery.order.dto.OrderDtos.DeliveryView;
import com.grocery.order.dto.OrderDtos.FailureView;
import com.grocery.order.dto.OrderDtos.ItemView;
import com.grocery.order.dto.OrderDtos.OrderSummary;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.dto.OrderDtos.PaymentStatusView;
import com.grocery.order.dto.OrderDtos.PaymentView;
import com.grocery.order.dto.OrderDtos.RejectionView;
import com.grocery.order.dto.OrderDtos.StoreView;
import com.grocery.order.dto.OrderDtos.TimelineEntry;
import com.grocery.order.dto.OrderDtos.UnavailableItemView;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.DeliveryAddress;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderStatus;

import java.util.List;

/** Entity → API views (plain Java). The order's collections must be loadable (call inside a transaction). */
public final class OrderMapper {

    /**
     * Phase 12 §3: what the order view shows of returns — {@code returnable} (a return can be requested now) and the
     * order's return requests, oldest first.
     */
    public record Returns(boolean returnable, List<OrderReturn> requests) {

        /** No returns and none possible: a new, cancelled or rejected order. */
        public static final Returns NONE = new Returns(false, List.of());
    }

    private OrderMapper() {
    }

    /** An order that cannot have returns (new, cancelled, rejected): {@code returnable} false, {@code returns} []. */
    public static OrderView view(CustomerOrder order) {
        return view(order, Returns.NONE);
    }

    public static OrderView view(CustomerOrder order, Returns returns) {
        return new OrderView(
                order.getId(),
                order.getOrderNumber(),
                order.getType(),
                order.getStatus(),
                store(order),
                order.getItems().stream()
                        .map(item -> new ItemView(item.getItemId(), item.getType(), item.getName(), item.getUnitLabel(),
                                item.getImageUrl(), item.getFoodType(), item.getQuantity(), item.getUnitPrice(),
                                item.getMrp(), item.getLineTotal()))
                        .toList(),
                order.getItemCount(),
                order.getSubtotal(),
                order.getMrpTotal(),
                order.getSavings(),
                order.getDeliveryFee(),
                order.getCouponCode(),
                order.getDiscount(),
                order.getDeliveryFeeDiscount(),
                order.getTotal(),
                order.getCurrency(),
                address(order.getDeliveryAddress()),
                order.getDeliveryDistanceKm(),
                order.getNote(),
                payment(order),
                order.getAcceptedAt(),
                order.getReadyInMinutes(),
                order.getReadyBy(),
                order.getStatus().isCancellable(),
                order.getStatus() == OrderStatus.CANCELLED
                        ? new CancellationView(order.getCancellationReason(), order.getCancellationNote(),
                        order.getCancelledBy(), order.getCancelledAt())
                        : null,
                order.getStatus() == OrderStatus.REJECTED
                        ? new RejectionView(order.getRejectionReason(), order.getRejectedItems().stream()
                        .map(item -> new UnavailableItemView(item.getItemId(), item.getName(), item.getRequested(),
                                item.getAvailable()))
                        .toList())
                        : null,
                delivery(order),
                order.getStatus().isTrackable(),
                order.getHistory().stream()
                        .map(change -> new TimelineEntry(change.getStatus(), change.getChangedAt(), change.getReason()))
                        .toList(),
                returns.returnable(),
                returns.requests().stream().map(ReturnMapper::summary).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    /** See {@link #view(CustomerOrder)}: for an order that cannot have returns. */
    public static AdminOrderView adminView(CustomerOrder order) {
        return adminView(order, Returns.NONE);
    }

    public static AdminOrderView adminView(CustomerOrder order, Returns returns) {
        return new AdminOrderView(order.getCustomerId(), order.getStatus().isAdminCancellable(),
                view(order, returns));
    }

    public static OrderSummary summary(CustomerOrder order) {
        return new OrderSummary(order.getId(), order.getOrderNumber(), order.getType(), order.getStatus(),
                store(order), order.getItemCount(), order.getCouponCode(), order.getDiscount(),
                order.getDeliveryFeeDiscount(), order.getTotal(), order.getCurrency(), order.getFirstItemName(),
                paymentStatus(order), order.getCreatedAt());
    }

    public static AdminOrderSummary adminSummary(CustomerOrder order) {
        return new AdminOrderSummary(order.getCustomerId(), order.getStatus().isAdminCancellable(), summary(order));
    }

    private static StoreView store(CustomerOrder order) {
        return new StoreView(order.getStoreId(), order.getType(), order.getStoreName());
    }

    /**
     * Null until a partner is assigned (and again after an unassignment before pickup). Only the partner's first name
     * is shown, as in delivery-service's tracking view and the notifications (privacy).
     */
    private static DeliveryView delivery(CustomerOrder order) {
        if (order.getPartnerId() == null && order.getPickedUpAt() == null) {
            return null;
        }
        return new DeliveryView(firstName(order.getPartnerName()), order.getPartnerAssignedAt(),
                order.getPickedUpAt(), order.getDeliveredAt());
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().split("\\s+", 2)[0];
    }

    /** {@code lastFailure} is shown only while the order can still be paid. */
    private static PaymentView payment(CustomerOrder order) {
        FailureView lastFailure = order.getStatus() == OrderStatus.AWAITING_PAYMENT && order.getLastFailureCode() != null
                ? new FailureView(order.getLastFailureCode(), order.getLastFailureDescription(),
                order.getLastFailureAt())
                : null;
        return new PaymentView(order.getPaymentStatus(), order.getPaymentDueAt(), order.getPaidAt(),
                order.getRefundedAmount(), lastFailure);
    }

    /**
     * The lists' payment: {@code dueAt} only while the order waits for the customer's payment (a column of the order
     * row, so no extra query per row); after that the deadline means nothing to the customer.
     */
    private static PaymentStatusView paymentStatus(CustomerOrder order) {
        return new PaymentStatusView(order.getPaymentStatus(),
                order.getStatus() == OrderStatus.AWAITING_PAYMENT ? order.getPaymentDueAt() : null);
    }

    private static AddressView address(DeliveryAddress address) {
        if (address == null) {
            return null;
        }
        return new AddressView(address.getLabel(), address.getRecipientName(), address.getPhone(), address.getLine1(),
                address.getLine2(), address.getLandmark(), address.getCity(), address.getState(),
                address.getPostalCode(), address.getLatitude(), address.getLongitude());
    }
}
