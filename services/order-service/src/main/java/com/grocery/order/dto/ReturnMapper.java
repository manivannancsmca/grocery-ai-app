package com.grocery.order.dto;

import com.grocery.order.dto.ReturnDtos.ReturnItemView;
import com.grocery.order.dto.ReturnDtos.ReturnSummary;
import com.grocery.order.dto.ReturnDtos.ReturnSummaryItem;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.entity.OrderReturn;

/** Return entity → API views (plain Java). The lines must be loadable (call inside a transaction). */
public final class ReturnMapper {

    private ReturnMapper() {
    }

    public static ReturnView view(OrderReturn request) {
        return new ReturnView(request.getId(), request.getReturnNumber(), request.getOrderId(),
                request.getOrderNumber(), request.getOrderType(), request.getStoreId(), request.getStoreName(),
                request.getStatus(), request.getReason(), request.getNote(),
                request.getItems().stream()
                        .map(item -> new ReturnItemView(item.getItemId(), item.getName(), item.getQuantity(),
                                item.getRefundAmount()))
                        .toList(),
                request.getRefundAmount(), request.getRestock(), request.getDecideBy(), request.getRequestedAt(),
                request.getDecidedAt(), request.getDecidedByRole(), request.getComment(), request.getRefundedAt());
    }

    public static ReturnSummary summary(OrderReturn request) {
        return new ReturnSummary(request.getId(), request.getReturnNumber(), request.getStatus(),
                request.getReason(), request.getRefundAmount(), request.getRequestedAt(),
                request.getItems().stream()
                        .map(item -> new ReturnSummaryItem(item.getItemId(), item.getQuantity()))
                        .toList());
    }
}
