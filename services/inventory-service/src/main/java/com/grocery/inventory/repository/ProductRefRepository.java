package com.grocery.inventory.repository;

import com.grocery.inventory.entity.ProductRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRefRepository extends JpaRepository<ProductRef, UUID> {
}
