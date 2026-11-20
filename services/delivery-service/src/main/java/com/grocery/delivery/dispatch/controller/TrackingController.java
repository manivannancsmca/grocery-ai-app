package com.grocery.delivery.dispatch.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingView;
import com.grocery.delivery.dispatch.service.TrackingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Customer tracking (contract §4): the order's customer, or any admin. */
@RestController
public class TrackingController {

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/api/delivery/orders/{orderId}/tracking")
    @PreAuthorize("hasRole('CUSTOMER') or " + Roles.ANY_ADMIN)
    public TrackingView track(CurrentUser user, @PathVariable UUID orderId) {
        return tracking.track(user, orderId);
    }
}
