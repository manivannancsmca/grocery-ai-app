package com.grocery.restaurant.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.restaurant.dto.StoreOrderDtos.AcceptRequest;
import com.grocery.restaurant.dto.StoreOrderDtos.RejectRequest;
import com.grocery.restaurant.dto.StoreOrderDtos.StoreOrderView;
import com.grocery.restaurant.dto.StoreOrderExports;
import com.grocery.restaurant.entity.RejectionReason;
import com.grocery.restaurant.service.StoreOrderService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in owner's order board (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §2). Unlike the menu, the
 * restaurant comes from the JWT's {@code restaurant_id} claim (identity adds it while the restaurant is APPROVED;
 * without it → 403 RESTAURANT_NOT_ACTIVE); another restaurant's order is 404 STORE_ORDER_NOT_FOUND.
 */
@RestController
@RequestMapping("/api/restaurant/orders")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantOrderController {

    private final StoreOrderService storeOrders;
    private final Exporter exporter;

    public RestaurantOrderController(StoreOrderService storeOrders, Exporter exporter) {
        this.storeOrders = storeOrders;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<StoreOrderView> board(CurrentUser user,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return storeOrders.board(user, status, page, size);
    }

    /**
     * The board as a file ({@code format} = csv | xlsx | pdf): the same filter, every page, in the board's order. The
     * customer by name and area only, as on the board.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, StoreOrderExports.ORDERS, storeOrders.boardExport(user, status),
                Exporter.filters("Status", status));
    }

    @GetMapping("/{orderId}")
    public StoreOrderView get(CurrentUser user, @PathVariable UUID orderId) {
        return storeOrders.get(user, orderId);
    }

    /** Body optional: {@code {"readyInMinutes": 25}} = the preparation time (5–180, default 20). */
    @PostMapping("/{orderId}/accept")
    public StoreOrderView accept(CurrentUser user, @PathVariable UUID orderId,
                                 @Valid @RequestBody(required = false) AcceptRequest request) {
        return storeOrders.accept(user, orderId, request == null ? null : request.readyInMinutes());
    }

    @PostMapping("/{orderId}/reject")
    public StoreOrderView reject(CurrentUser user, @PathVariable UUID orderId,
                                 @Valid @RequestBody RejectRequest request) {
        return storeOrders.reject(user, orderId, RejectionReason.valueOf(request.reason()), request.note());
    }

    @PostMapping("/{orderId}/preparing")
    public StoreOrderView preparing(CurrentUser user, @PathVariable UUID orderId) {
        return storeOrders.preparing(user, orderId);
    }

    @PostMapping("/{orderId}/ready")
    public StoreOrderView ready(CurrentUser user, @PathVariable UUID orderId) {
        return storeOrders.ready(user, orderId);
    }
}
