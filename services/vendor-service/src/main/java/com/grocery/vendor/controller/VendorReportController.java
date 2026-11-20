package com.grocery.vendor.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.vendor.dto.StoreOrderDtos.SalesReport;
import com.grocery.vendor.service.SalesReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in vendor's sales report (contract §3), for the store of the {@code vendor_id} claim. */
@RestController
@RequestMapping("/api/vendor/reports")
@PreAuthorize("hasRole('VENDOR')")
public class VendorReportController {

    private final SalesReportService reports;

    public VendorReportController(SalesReportService reports) {
        this.reports = reports;
    }

    /** {@code from} / {@code to}: dates (India time), inclusive; default the last 7 days; at most 92 days. */
    @GetMapping("/sales")
    public SalesReport sales(CurrentUser user, @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return reports.sales(user, from, to);
    }
}
