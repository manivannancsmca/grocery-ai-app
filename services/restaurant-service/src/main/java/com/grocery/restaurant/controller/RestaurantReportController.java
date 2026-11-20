package com.grocery.restaurant.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.StoreOrderDtos.SalesReport;
import com.grocery.restaurant.service.SalesReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in owner's sales report (contract §3), for the restaurant of the {@code restaurant_id} claim. */
@RestController
@RequestMapping("/api/restaurant/reports")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantReportController {

    private final SalesReportService reports;

    public RestaurantReportController(SalesReportService reports) {
        this.reports = reports;
    }

    /** {@code from} / {@code to}: dates (India time), inclusive; default the last 7 days; at most 92 days. */
    @GetMapping("/sales")
    public SalesReport sales(CurrentUser user, @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return reports.sales(user, from, to);
    }
}
