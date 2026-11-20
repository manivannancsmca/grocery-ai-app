package com.grocery.pricing.repository;

import com.grocery.pricing.entity.PriceHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, UUID> {

    Page<PriceHistory> findByProductId(UUID productId, Pageable pageable);
}
