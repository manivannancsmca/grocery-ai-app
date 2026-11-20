package com.grocery.order.service;

import com.grocery.common.data.Pages;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.ComplaintDtos.AdminComplaintView;
import com.grocery.order.dto.ComplaintDtos.ComplaintView;
import com.grocery.order.dto.ComplaintMapper;
import com.grocery.order.entity.ComplaintCategory;
import com.grocery.order.entity.ComplaintStatus;
import com.grocery.order.repository.ComplaintRepository;
import com.grocery.order.repository.ComplaintSpecifications;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * The complaint lists and the customer's single complaint (docs/api/payment-and-support-agents.md §2.4). Plain reads
 * in read-only transactions; lists are newest first. An unknown {@code status} / {@code category} value is 400
 * INVALID_PARAMETER.
 */
@Service
public class ComplaintQueryService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ComplaintRepository complaints;

    public ComplaintQueryService(ComplaintRepository complaints) {
        this.complaints = complaints;
    }

    /** {@code GET /api/complaints?status=}: the customer's complaints (one status or all), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ComplaintView> ofCustomer(UUID customerId, String status, Integer page, Integer size) {
        ComplaintStatus wanted = BadRequestException.parseEnum(ComplaintStatus.class, "status", status);
        return Pages.toResponse(complaints.findAll(ComplaintSpecifications.matching(customerId, wanted, null, null),
                Pages.request(page, size, NEWEST_FIRST)), ComplaintMapper::view);
    }

    /** {@code GET /api/complaints/{id}}: the customer's own complaint; anyone else's → 404 COMPLAINT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public ComplaintView ofCustomer(UUID customerId, UUID complaintId) {
        return complaints.findByIdAndCustomerId(complaintId, customerId)
                .map(ComplaintMapper::view)
                .orElseThrow(OrderException::complaintNotFound);
    }

    /**
     * {@code GET /api/admin/complaints?status=&category=&q=}: every customer's complaints, newest first; each filter
     * optional (no status = every status). {@code q} = a complaint number or an order number (exact, any case).
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminComplaintView> forAdmins(String status, String category, String q, Integer page,
                                                      Integer size) {
        ComplaintStatus wantedStatus = BadRequestException.parseEnum(ComplaintStatus.class, "status", status);
        ComplaintCategory wantedCategory = BadRequestException.parseEnum(ComplaintCategory.class, "category", category);
        String number = q == null || q.isBlank() ? null : q.strip().toUpperCase(Locale.ROOT);
        return Pages.toResponse(complaints.findAll(
                        ComplaintSpecifications.matching(null, wantedStatus, wantedCategory, number),
                        Pages.request(page, size, NEWEST_FIRST)),
                ComplaintMapper::adminView);
    }
}
