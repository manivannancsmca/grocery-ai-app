package com.grocery.order.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.ComplaintDtos.ComplaintView;
import com.grocery.order.dto.ComplaintDtos.NewComplaintRequest;
import com.grocery.order.service.ComplaintQueryService;
import com.grocery.order.service.ComplaintService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * The customer's own complaints (docs/api/payment-and-support-agents.md §2.4); the customer is the JWT subject, never
 * a request field. Raising one: 404 ORDER_NOT_FOUND for an order that is not the caller's, 409 COMPLAINT_ALREADY_OPEN,
 * 409 TOO_MANY_OPEN_COMPLAINTS ({@link ComplaintService}). Another customer's complaint is 404 COMPLAINT_NOT_FOUND.
 */
@RestController
@RequestMapping("/api/complaints")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerComplaintController {

    private final ComplaintService complaints;
    private final ComplaintQueryService queries;

    public CustomerComplaintController(ComplaintService complaints, ComplaintQueryService queries) {
        this.complaints = complaints;
        this.queries = queries;
    }

    /** 201 with the new OPEN complaint (and its location). */
    @PostMapping
    public ResponseEntity<ComplaintView> raise(CurrentUser user, @Valid @RequestBody NewComplaintRequest request) {
        ComplaintView created = complaints.raise(user.userId(), request);
        return ResponseEntity.created(URI.create("/api/complaints/" + created.id())).body(created);
    }

    /** The caller's complaints, newest first; {@code status} = one complaint status (default all). */
    @GetMapping
    public PageResponse<ComplaintView> list(CurrentUser user, @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return queries.ofCustomer(user.userId(), status, page, size);
    }

    @GetMapping("/{id}")
    public ComplaintView get(CurrentUser user, @PathVariable UUID id) {
        return queries.ofCustomer(user.userId(), id);
    }
}
