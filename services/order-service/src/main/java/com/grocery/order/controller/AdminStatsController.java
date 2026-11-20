package com.grocery.order.controller;

import com.grocery.common.security.Roles;
import com.grocery.order.dto.OrderDtos.OrderStats;
import com.grocery.order.service.OrderQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Order stats for one day in India time (contract §4), any admin role. */
@RestController
@RequestMapping("/api/admin/stats")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminStatsController {

    private final OrderQueryService queries;

    public AdminStatsController(OrderQueryService queries) {
        this.queries = queries;
    }

    /** {@code date} = {@code yyyy-MM-dd}; default today (India time). */
    @GetMapping
    public OrderStats stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                            LocalDate date) {
        return queries.stats(date);
    }
}
