package com.grocery.inventory.repository;

import com.grocery.inventory.entity.ProductStatus;
import com.grocery.inventory.entity.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    /**
     * {@code SELECT … FOR UPDATE} on one stock row: the first statement of every stock change. Only
     * {@code StockLocks} calls it, so that several rows are always locked in ascending product id order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.productId = :productId")
    Optional<StockItem> lockById(@Param("productId") UUID productId);

    /** The vendor's non-archived products, optionally searched ({@code q}) and filtered (see contract §2). */
    String VISIBLE = """
            from StockItem s join ProductRef p on p.productId = s.productId
            where s.vendorId = :vendorId and p.status <> :archived
              and (:q is null or lower(p.name) like :namePattern escape '!' or p.sku = :q)
              and (:filter is null
                   or (:filter = 'IN_STOCK' and s.onHand - s.reserved > 0)
                   or (:filter = 'OUT_OF_STOCK' and s.onHand - s.reserved <= 0)
                   or (:filter = 'LOW_STOCK' and s.lowStockThreshold > 0
                       and s.onHand - s.reserved <= s.lowStockThreshold))
            """;

    /** What the list (and its export) selects from {@link #VISIBLE}: each stock row with its product. */
    String VISIBLE_ROW = "new com.grocery.inventory.repository.StockRow(s, p)";

    @Query(value = "select " + VISIBLE_ROW + " " + VISIBLE + " order by p.name, p.productId",
            countQuery = "select count(s) " + VISIBLE)
    Page<StockRow> findVisible(@Param("vendorId") UUID vendorId, @Param("archived") ProductStatus archived,
                               @Param("q") String q, @Param("namePattern") String namePattern,
                               @Param("filter") String filter, Pageable pageable);

    /** One row: [products, inStock, lowStock (low but not out), outOfStock] of the vendor's non-archived products. */
    @Query("""
            select count(s),
                   coalesce(sum(case when s.onHand - s.reserved > 0 then 1 else 0 end), 0),
                   coalesce(sum(case when s.lowStockThreshold > 0 and s.onHand - s.reserved > 0
                                          and s.onHand - s.reserved <= s.lowStockThreshold then 1 else 0 end), 0),
                   coalesce(sum(case when s.onHand - s.reserved <= 0 then 1 else 0 end), 0)
            from StockItem s join ProductRef p on p.productId = s.productId
            where s.vendorId = :vendorId and p.status <> :archived
            """)
    List<Object[]> summarize(@Param("vendorId") UUID vendorId, @Param("archived") ProductStatus archived);

    /**
     * The internal availability check (Phase 5 contract §4): the stock rows of the non-archived products among
     * {@code ids}, in one plain (non-locking, consistent) read. Unknown and archived products are simply absent.
     */
    @Query("""
            select s from StockItem s join ProductRef p on p.productId = s.productId
            where s.productId in :ids and p.status <> :archived
            """)
    List<StockItem> findAvailable(@Param("ids") Collection<UUID> ids, @Param("archived") ProductStatus archived);

    /** Keyset paging for republish: non-archived stock rows in product id order. */
    @Query("""
            select s from StockItem s join ProductRef p on p.productId = s.productId
            where p.status <> :archived order by s.productId
            """)
    List<StockItem> findFirstLive(@Param("archived") ProductStatus archived, Pageable pageable);

    @Query("""
            select s from StockItem s join ProductRef p on p.productId = s.productId
            where p.status <> :archived and s.productId > :after order by s.productId
            """)
    List<StockItem> findLiveAfter(@Param("archived") ProductStatus archived, @Param("after") UUID after,
                                  Pageable pageable);
}
