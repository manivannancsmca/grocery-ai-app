package com.grocery.pricing.repository;

import com.grocery.pricing.entity.ProductPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, UUID> {

    /**
     * Quote rows: [ProductPrice, productVersion, ProductStatus] for the priced products among {@code ids}.
     * One statement = one consistent snapshot of price and product.
     */
    @Query("""
            select p, r.productVersion, r.status from ProductPrice p join ProductRef r on r.productId = p.productId
            where p.productId in :ids
            """)
    List<Object[]> findQuoteRows(@Param("ids") Collection<UUID> ids);

    /** Keyset paging for republish, in id order (UUIDv7 = creation order). */
    @Query("select p from ProductPrice p order by p.productId")
    List<ProductPrice> findFirstBatch(Pageable pageable);

    @Query("select p from ProductPrice p where p.productId > :after order by p.productId")
    List<ProductPrice> findBatchAfter(@Param("after") UUID after, Pageable pageable);
}
