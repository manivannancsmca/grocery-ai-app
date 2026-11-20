package com.grocery.order.repository;

import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Filters of the customer and admin order lists (contract §2, §4); every argument is optional (null = no filter). */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    /**
     * @param statuses     one status, or {@code CREATED + AWAITING_PAYMENT} for {@code status=OPEN}
     * @param orderNumber  exact order number, already upper case
     * @param createdFrom  inclusive
     * @param createdUntil exclusive
     */
    public static Specification<CustomerOrder> matching(UUID customerId, Collection<OrderStatus> statuses,
                                                        OrderType type, UUID storeId, String orderNumber,
                                                        Instant createdFrom, Instant createdUntil) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("storeId"), storeId));
            }
            if (orderNumber != null) {
                predicates.add(cb.equal(root.get("orderNumber"), orderNumber));
            }
            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdUntil != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), createdUntil));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
