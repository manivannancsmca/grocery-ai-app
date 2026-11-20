package com.grocery.restaurant.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.MenuDtos.AcceptingOrdersRequest;
import com.grocery.restaurant.dto.MenuDtos.AcceptingOrdersView;
import com.grocery.restaurant.service.RestaurantOperationsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Day-to-day switches of the signed-in owner's restaurant (found by the JWT's user id). */
@RestController
@RequestMapping("/api/restaurant")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantOperationsController {

    private final RestaurantOperationsService operations;

    public RestaurantOperationsController(RestaurantOperationsService operations) {
        this.operations = operations;
    }

    @PutMapping("/accepting-orders")
    public AcceptingOrdersView acceptingOrders(CurrentUser user, @Valid @RequestBody AcceptingOrdersRequest request) {
        return operations.setAcceptingOrders(user, request.acceptingOrders());
    }
}
