package com.grocery.restaurant.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.restaurant.dto.MenuDtos.MenuView;
import com.grocery.restaurant.dto.MenuDtos.RepublishResult;
import com.grocery.restaurant.dto.RestaurantDtos.ReasonRequest;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.dto.RestaurantDtos.StatusChangeView;
import com.grocery.restaurant.dto.RestaurantExports;
import com.grocery.restaurant.service.RestaurantAdminService;
import com.grocery.restaurant.service.RestaurantMenuService;
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

import java.util.List;
import java.util.UUID;

/**
 * Any admin role may look (incl. the menu, to review it before approving); only SUPER_ADMIN / OPS_ADMIN may
 * change a restaurant's status or re-send the search listings.
 */
@RestController
@RequestMapping("/api/admin/restaurants")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminRestaurantController {

    private final RestaurantAdminService admin;
    private final RestaurantMenuService menus;
    private final Exporter exporter;

    public AdminRestaurantController(RestaurantAdminService admin, RestaurantMenuService menus, Exporter exporter) {
        this.admin = admin;
        this.menus = menus;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<RestaurantView> search(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String query,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return admin.search(status, query, page, size);
    }

    /**
     * The restaurant list as a file ({@code format} = csv | xlsx | pdf), with the search's filters, every page, in the
     * list's order. The owner's e-mail, phone and street address only for SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String query, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, RestaurantExports.RESTAURANTS.visibleTo(user),
                admin.searchExport(status, query), Exporter.filters("Status", status, "Search", query));
    }

    @GetMapping("/{id}")
    public RestaurantView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @GetMapping("/{id}/history")
    public List<StatusChangeView> history(@PathVariable UUID id) {
        return admin.history(id);
    }

    @GetMapping("/{id}/menu")
    public MenuView menu(@PathVariable UUID id) {
        return menus.menu(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RestaurantView approve(@PathVariable UUID id, CurrentUser user) {
        return admin.approve(id, user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RestaurantView reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.reject(id, request.reason(), user);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RestaurantView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request, CurrentUser user) {
        return admin.suspend(id, request.reason(), user);
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RestaurantView reinstate(@PathVariable UUID id, CurrentUser user) {
        return admin.reinstate(id, user);
    }

    /** Rebuilds the search index after it was lost: re-sends every listing with its current version. */
    @PostMapping("/listings/republish")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RepublishResult republishListings() {
        return new RepublishResult(admin.republishListings());
    }
}
