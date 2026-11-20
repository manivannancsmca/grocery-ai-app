package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer's complaint (docs/api/payment-and-support-agents.md §2), about one of their orders or about the app in
 * general. The order's number and store name are snapshotted when it is raised.
 *
 * <p>Every transition checks the current status and throws {@link IllegalStateException} in the wrong one, so callers
 * check first ({@link ComplaintStatus#canStart()}, {@link ComplaintStatus#canResolve()}) and answer 409
 * INVALID_COMPLAINT_STATUS. No row lock is taken: {@code @Version} makes the second of two admins changing the same
 * complaint at once fail (409 CONCURRENT_MODIFICATION).
 */
@Entity
@Table(name = "complaint")
public class Complaint {

    public static final int DESCRIPTION_MIN = 10;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int RESOLUTION_MIN = 5;
    public static final int RESOLUTION_MAX = 1000;

    @Id
    private UUID id;

    @Column(name = "complaint_number", nullable = false, length = 12, updatable = false)
    private String complaintNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_number", length = 12, updatable = false)
    private String orderNumber;

    @Column(name = "store_name", length = 150, updatable = false)
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ComplaintCategory category;

    @Column(nullable = false, length = DESCRIPTION_MAX, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ComplaintStatus status;

    @Column(length = RESOLUTION_MAX)
    private String resolution;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private Long version;

    protected Complaint() {
    }

    /**
     * A new OPEN complaint of {@code customerId}.
     *
     * @param order       the customer's own order it is about (the caller checked that), or null for none
     * @param description already trimmed and checked (10-1000 characters)
     */
    public static Complaint raise(UUID id, String complaintNumber, UUID customerId, CustomerOrder order,
                                  ComplaintCategory category, String description, Instant now) {
        Complaint complaint = new Complaint();
        complaint.id = id;
        complaint.complaintNumber = complaintNumber;
        complaint.customerId = customerId;
        if (order != null) {
            complaint.orderId = order.getId();
            complaint.orderNumber = order.getOrderNumber();
            complaint.storeName = order.getStoreName();
        }
        complaint.category = category;
        complaint.description = description;
        complaint.status = ComplaintStatus.OPEN;
        complaint.createdAt = now;
        complaint.updatedAt = now;
        return complaint;
    }

    // ---- transitions ------------------------------------------------------------------------------------------

    /** OPEN → IN_PROGRESS: {@code adminId} works on it now. */
    public void start(UUID adminId, Instant now) {
        if (!status.canStart()) {
            throw new IllegalStateException("Complaint " + complaintNumber + " is " + status + ", not OPEN");
        }
        status = ComplaintStatus.IN_PROGRESS;
        handledBy = adminId;
        updatedAt = now;
    }

    /** OPEN or IN_PROGRESS → RESOLVED with {@code resolution}, the answer the customer sees (already trimmed). */
    public void resolve(String resolution, UUID adminId, Instant now) {
        if (!status.canResolve()) {
            throw new IllegalStateException("Complaint " + complaintNumber + " is " + status
                    + ", not OPEN or IN_PROGRESS");
        }
        this.resolution = resolution;
        status = ComplaintStatus.RESOLVED;
        handledBy = adminId;
        resolvedAt = now;
        updatedAt = now;
    }

    // ---- getters ----------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getComplaintNumber() {
        return complaintNumber;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getStoreName() {
        return storeName;
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public String getResolution() {
        return resolution;
    }

    public UUID getHandledBy() {
        return handledBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
