package com.grocery.order.repository;

import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.ReturnStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The return lists' filters; every argument optional (null = no filter). */
public final class ReturnSpecifications {

    private ReturnSpecifications() {
    }

    public static Specification<OrderReturn> matching(UUID customerId, OrderType orderType, UUID storeId,
                                                      Collection<ReturnStatus> statuses) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (orderType != null) {
                predicates.add(cb.equal(root.get("orderType"), orderType));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("storeId"), storeId));
            }
            if (statuses != null) {
                predicates.add(root.get("status").in(statuses));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
