package com.grocery.delivery.dispatch.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.dispatch.dto.DeliveryExports;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminDeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OnlinePartnerView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.ReassignRequest;
import com.grocery.delivery.dispatch.service.DeliveryAdminService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin delivery monitor (contract §5): any admin role may look; SUPER_ADMIN, OPS_ADMIN and DELIVERY_MANAGER (who
 * run the fleet) may reassign.
 */
@RestController
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminDeliveryController {

    /** SpEL for {@code @PreAuthorize}: reassignment. */
    static final String FLEET_ADMIN = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','DELIVERY_MANAGER')";

    private final DeliveryAdminService admin;
    private final Exporter exporter;

    public AdminDeliveryController(DeliveryAdminService admin, Exporter exporter) {
        this.admin = admin;
        this.exporter = exporter;
    }

    @GetMapping("/api/admin/deliveries")
    public PageResponse<AdminDeliveryView> search(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Boolean assignmentFailed,
                                                  @RequestParam(required = false) UUID partnerId,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return admin.search(status, assignmentFailed, partnerId, q, page, size);
    }

    /**
     * The monitor as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code order} | {@code total}, {@code ,asc} | {@code ,desc}). The recipient's contact details
     * only for SUPER_ADMIN, OPS_ADMIN and SUPPORT.
     */
    @GetMapping("/api/admin/deliveries/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Boolean assignmentFailed,
                       @RequestParam(required = false) UUID partnerId,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, DeliveryExports.ADMIN_DELIVERIES.visibleTo(user),
                admin.searchExport(status, assignmentFailed, partnerId, q, sort),
                Exporter.filters("Status", status, "Assignment failed", assignmentFailed, "Partner ID", partnerId,
                        "Order number", q));
    }

    @GetMapping("/api/admin/deliveries/{id}")
    public AdminDeliveryView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    /** Body optional: without one (or without {@code partnerId}) the delivery is searched again. */
    @PostMapping("/api/admin/deliveries/{id}/reassign")
    @PreAuthorize(FLEET_ADMIN)
    public AdminDeliveryView reassign(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false) ReassignRequest request,
                                      CurrentUser user) {
        return admin.reassign(id, request == null ? null : request.partnerId(),
                request == null ? null : request.reason(), user);
    }

    /** Lives next to /api/admin/delivery-partners/{id} (AdminPartnerController); the literal path wins. */
    @GetMapping("/api/admin/delivery-partners/online")
    public List<OnlinePartnerView> onlinePartners() {
        return admin.onlinePartners();
    }
}
