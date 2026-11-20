package com.grocery.order.repository;

import com.grocery.order.entity.CustomerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerOrderRepository
        extends JpaRepository<CustomerOrder, UUID>, JpaSpecificationExecutor<CustomerOrder> {

    /**
     * {@code SELECT … FOR UPDATE} on one order row (by primary key: a record lock, no gap lock): the first read of
     * every status change. Only {@code OrderLocks} calls it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> lockById(@Param("id") UUID id);

    boolean existsByOrderNumber(String orderNumber);

    /** By its number (unique, upper case: GA-7K3F9Q2M). */
    Optional<CustomerOrder> findByOrderNumber(String orderNumber);

    /** [status, type, count] of the orders created in {@code [from, to)}. */
    @Query("""
            select o.status, o.type, count(o) from CustomerOrder o
            where o.createdAt >= :from and o.createdAt < :to
            group by o.status, o.type
            """)
    List<Object[]> countCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** One row: [count, sum of totals] of the orders confirmed (paid) in {@code [from, to)}. */
    @Query("""
            select count(o), coalesce(sum(o.total), 0) from CustomerOrder o
            where o.confirmedAt >= :from and o.confirmedAt < :to
            """)
    List<Object[]> confirmedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
