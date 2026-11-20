package com.grocery.catalogquery.controller;

import com.grocery.catalogquery.dto.CatalogDtos.IndexStatus;
import com.grocery.catalogquery.service.IndexStatusService;
import com.grocery.common.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Search index status for any admin role (missing indexes are created on the way). */
@RestController
@RequestMapping("/api/admin/search")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminSearchController {

    private final IndexStatusService indexes;

    public AdminSearchController(IndexStatusService indexes) {
        this.indexes = indexes;
    }

    @GetMapping("/indexes")
    public List<IndexStatus> indexes() {
        return indexes.statuses();
    }
}
