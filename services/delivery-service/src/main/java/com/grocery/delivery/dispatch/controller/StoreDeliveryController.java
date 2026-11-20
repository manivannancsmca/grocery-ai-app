package com.grocery.delivery.dispatch.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.delivery.dispatch.dto.DispatchDtos.StoreDeliveryView;
import com.grocery.delivery.dispatch.service.StoreDeliveryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The store order board (phase-9 contract, store addendum): the caller's own store only (vendor or restaurant). */
@RestController
public class StoreDeliveryController {

    private final StoreDeliveryService deliveries;

    public StoreDeliveryController(StoreDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping("/api/delivery/store/deliveries")
    @PreAuthorize("hasAnyRole('VENDOR','RESTAURANT_OWNER')")
    public List<StoreDeliveryView> deliveries(CurrentUser user) {
        return deliveries.atStore(user);
    }
}
