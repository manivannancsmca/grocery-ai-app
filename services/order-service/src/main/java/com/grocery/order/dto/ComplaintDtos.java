package com.grocery.order.dto;

import com.grocery.order.entity.Complaint;
import com.grocery.order.entity.ComplaintCategory;
import com.grocery.order.entity.ComplaintStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Complaint API bodies (docs/api/payment-and-support-agents.md §2.4). Nulls are written out ({@code "orderId": null}
 * without an order, {@code "resolution": null} until resolved). The texts are trimmed by the service, which checks
 * the length again after trimming (400 VALIDATION_FAILED on {@code description} / {@code resolution}).
 */
public final class ComplaintDtos {

    private ComplaintDtos() {
    }

    // ---- requests ---------------------------------------------------------------------------------------------

    /** POST /api/complaints. {@code orderId} optional: one of the caller's own orders. */
    public record NewComplaintRequest(
            @NotNull ComplaintCategory category,
            UUID orderId,
            @NotBlank @Size(min = Complaint.DESCRIPTION_MIN, max = Complaint.DESCRIPTION_MAX) String description) {
    }

    /** POST /api/admin/complaints/{id}/resolve — the answer the customer sees. */
    public record ResolveComplaintRequest(
            @NotBlank @Size(min = Complaint.RESOLUTION_MIN, max = Complaint.RESOLUTION_MAX) String resolution) {
    }

    // ---- views ------------------------------------------------------------------------------------------------

    /** A complaint as its customer sees it. */
    public record ComplaintView(UUID id, String complaintNumber, ComplaintCategory category, ComplaintStatus status,
                                UUID orderId, String orderNumber, String storeName, String description,
                                String resolution, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
    }

    /** A complaint as admins see it: the customer's view plus who raised it and who started or resolved it. */
    public record AdminComplaintView(UUID id, String complaintNumber, ComplaintCategory category,
                                     ComplaintStatus status, UUID orderId, String orderNumber, String storeName,
                                     String description, String resolution, Instant createdAt, Instant updatedAt,
                                     Instant resolvedAt, UUID customerId, UUID handledBy) {
    }
}
