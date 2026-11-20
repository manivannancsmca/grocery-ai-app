package com.grocery.inventory.controller;

import com.grocery.common.security.Roles;
import com.grocery.inventory.dto.InventoryDtos.RepublishResult;
import com.grocery.inventory.service.RepublishService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Search-index maintenance (SUPER_ADMIN / OPS_ADMIN). */
@RestController
@RequestMapping("/api/admin/inventory")
@PreAuthorize(Roles.OPERATIONS_ADMIN)
public class AdminInventoryController {

    private final RepublishService republish;

    public AdminInventoryController(RepublishService republish) {
        this.republish = republish;
    }

    /** Re-sends ProductAvailabilityChanged for every non-archived product with its current stockVersion. */
    @PostMapping("/republish")
    public RepublishResult republish() {
        return republish.republishAll();
    }
}
