package com.grocery.customer.repository;

import com.grocery.customer.entity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** SELECT … FOR UPDATE: serialises address-book changes of one customer (limit, single default). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);

    /** The admin search's filter, shared by the list and its export (alias {@code c}). */
    String ADMIN_SEARCH = """
            from Customer c
            where :query is null or lower(c.email) like :query or lower(c.fullName) like :query
            """;

    /** Admin search; {@code query} is optional, lower-cased with % wildcards. */
    @Query("select c " + ADMIN_SEARCH)
    Page<Customer> search(@Param("query") String query, Pageable pageable);
}
