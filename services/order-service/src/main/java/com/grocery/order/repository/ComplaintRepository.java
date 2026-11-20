package com.grocery.order.repository;

import com.grocery.order.entity.Complaint;
import com.grocery.order.entity.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Complaints (docs/api/payment-and-support-agents.md §2). Lists go through {@link ComplaintSpecifications}. */
public interface ComplaintRepository extends JpaRepository<Complaint, UUID>, JpaSpecificationExecutor<Complaint> {

    /** The customer's own complaint (anyone else's is "not found"). */
    Optional<Complaint> findByIdAndCustomerId(UUID id, UUID customerId);

    /** The order's complaint in one of {@code statuses} (at most one is open). Served by {@code idx_complaint_order_status}. */
    Optional<Complaint> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<ComplaintStatus> statuses);

    /** How many of the customer's complaints are in {@code statuses}. Served by {@code idx_complaint_customer_status}. */
    long countByCustomerIdAndStatusIn(UUID customerId, Collection<ComplaintStatus> statuses);
}
