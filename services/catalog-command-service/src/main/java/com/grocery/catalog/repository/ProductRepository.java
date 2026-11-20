package com.grocery.catalog.repository;

import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /** {@code SELECT … FOR UPDATE}: the first statement of every product command. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> lockById(@Param("id") UUID id);

    /** Non-archived products in any of these categories, locked for a re-snapshot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.categoryId in :categoryIds and p.status <> :archived order by p.id")
    List<Product> lockLiveByCategoryIds(@Param("categoryIds") Collection<UUID> categoryIds,
                                        @Param("archived") ProductStatus archived);

    /** Non-archived products of one vendor, locked for a re-snapshot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.vendorId = :vendorId and p.status <> :archived order by p.id")
    List<Product> lockLiveByVendorId(@Param("vendorId") UUID vendorId, @Param("archived") ProductStatus archived);

    /** Is the SKU used by another non-archived product of this vendor? (case-insensitive collation) */
    @Query("""
            select case when count(p) > 0 then true else false end from Product p
            where p.vendorId = :vendorId and p.sku = :sku and p.status <> :archived
              and (:excludeId is null or p.id <> :excludeId)
            """)
    boolean skuTaken(@Param("vendorId") UUID vendorId, @Param("sku") String sku,
                     @Param("archived") ProductStatus archived, @Param("excludeId") UUID excludeId);

    @Query("select count(p) from Product p where p.categoryId = :categoryId and p.status <> :archived")
    long countLiveInCategory(@Param("categoryId") UUID categoryId, @Param("archived") ProductStatus archived);

    /** [categoryId, count] of non-archived products directly in each category. */
    @Query("""
            select p.categoryId, count(p) from Product p
            where p.categoryId is not null and p.status <> :archived
            group by p.categoryId
            """)
    List<Object[]> countLiveByCategory(@Param("archived") ProductStatus archived);

    /** Keyset paging for republish: ids in id order (UUIDv7 = creation order). */
    @Query("select p.id from Product p order by p.id")
    List<UUID> findFirstIds(Pageable pageable);

    @Query("select p.id from Product p where p.id > :after order by p.id")
    List<UUID> findIdsAfter(@Param("after") UUID after, Pageable pageable);
}
