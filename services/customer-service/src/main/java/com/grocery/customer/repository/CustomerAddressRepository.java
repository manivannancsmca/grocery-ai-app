package com.grocery.customer.repository;

import com.grocery.customer.entity.CustomerAddress;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {

    List<CustomerAddress> findByCustomerId(UUID customerId, Sort sort);

    /** Ownership check built into the lookup: another customer's address is simply "not found". */
    Optional<CustomerAddress> findByIdAndCustomerId(UUID id, UUID customerId);
}
