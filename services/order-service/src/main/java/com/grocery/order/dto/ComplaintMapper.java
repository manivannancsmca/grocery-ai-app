package com.grocery.order.dto;

import com.grocery.order.dto.ComplaintDtos.AdminComplaintView;
import com.grocery.order.dto.ComplaintDtos.ComplaintView;
import com.grocery.order.entity.Complaint;

/** Complaint entity → API views (plain Java). */
public final class ComplaintMapper {

    private ComplaintMapper() {
    }

    /** For the customer: without {@code customerId} and {@code handledBy}. */
    public static ComplaintView view(Complaint complaint) {
        return new ComplaintView(complaint.getId(), complaint.getComplaintNumber(), complaint.getCategory(),
                complaint.getStatus(), complaint.getOrderId(), complaint.getOrderNumber(), complaint.getStoreName(),
                complaint.getDescription(), complaint.getResolution(), complaint.getCreatedAt(),
                complaint.getUpdatedAt(), complaint.getResolvedAt());
    }

    /** For admins: everything. */
    public static AdminComplaintView adminView(Complaint complaint) {
        return new AdminComplaintView(complaint.getId(), complaint.getComplaintNumber(), complaint.getCategory(),
                complaint.getStatus(), complaint.getOrderId(), complaint.getOrderNumber(), complaint.getStoreName(),
                complaint.getDescription(), complaint.getResolution(), complaint.getCreatedAt(),
                complaint.getUpdatedAt(), complaint.getResolvedAt(), complaint.getCustomerId(),
                complaint.getHandledBy());
    }
}
