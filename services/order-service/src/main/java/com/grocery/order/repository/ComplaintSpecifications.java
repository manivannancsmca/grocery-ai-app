package com.grocery.order.repository;

import com.grocery.order.entity.Complaint;
import com.grocery.order.entity.ComplaintCategory;
import com.grocery.order.entity.ComplaintStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The complaint lists' filters; every argument optional (null = no filter). */
public final class ComplaintSpecifications {

    private ComplaintSpecifications() {
    }

    /**
     * @param number a complaint number or an order number (exact, already upper case): matches either
     */
    public static Specification<Complaint> matching(UUID customerId, ComplaintStatus status,
                                                    ComplaintCategory category, String number) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (number != null) {
                predicates.add(cb.or(cb.equal(root.get("complaintNumber"), number),
                        cb.equal(root.get("orderNumber"), number)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
