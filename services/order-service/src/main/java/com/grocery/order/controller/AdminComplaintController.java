package com.grocery.order.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.ComplaintDtos.AdminComplaintView;
import com.grocery.order.dto.ComplaintDtos.ResolveComplaintRequest;
import com.grocery.order.service.ComplaintQueryService;
import com.grocery.order.service.ComplaintService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Complaints for admins (docs/api/payment-and-support-agents.md §2.4): the list for any admin role; start and resolve
 * for SUPER_ADMIN, OPS_ADMIN, SUPPORT (the roles that decide returns). 404 COMPLAINT_NOT_FOUND, 409
 * INVALID_COMPLAINT_STATUS, and 409 CONCURRENT_MODIFICATION when another admin changed it at the same moment.
 */
@RestController
@RequestMapping("/api/admin/complaints")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminComplaintController {

    /** Who may start and resolve complaints (method-level, replaces the class-level read rule). */
    static final String CAN_HANDLE = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')";

    private final ComplaintService complaints;
    private final ComplaintQueryService queries;

    public AdminComplaintController(ComplaintService complaints, ComplaintQueryService queries) {
        this.complaints = complaints;
        this.queries = queries;
    }

    /**
     * Newest first; every filter optional: {@code status} (default every status), {@code category}, {@code q} = a
     * complaint number or an order number (exact, any case).
     */
    @GetMapping
    public PageResponse<AdminComplaintView> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return queries.forAdmins(status, category, q, page, size);
    }

    /** OPEN → IN_PROGRESS; the caller becomes {@code handledBy}. */
    @PostMapping("/{id}/start")
    @PreAuthorize(CAN_HANDLE)
    public AdminComplaintView start(CurrentUser user, @PathVariable UUID id) {
        return complaints.start(user.userId(), id);
    }

    /** OPEN or IN_PROGRESS → RESOLVED; the resolution is shown to the customer. */
    @PostMapping("/{id}/resolve")
    @PreAuthorize(CAN_HANDLE)
    public AdminComplaintView resolve(CurrentUser user, @PathVariable UUID id,
                                      @Valid @RequestBody ResolveComplaintRequest request) {
        return complaints.resolve(user.userId(), id, request.resolution());
    }
}
