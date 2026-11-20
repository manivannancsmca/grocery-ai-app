package com.grocery.restaurant.repository;

import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    Optional<Restaurant> findByOwnerUserId(UUID ownerUserId);

    /**
     * SELECT … FOR UPDATE. Must be the FIRST read of a transaction that changes the public listing
     * (use RestaurantListingPublisher#lockById, which also marks the instance as locked).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restaurant r where r.id = :id")
    Optional<Restaurant> findByIdForUpdate(@Param("id") UUID id);

    /** SELECT … FOR UPDATE by owner; see {@link #findByIdForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restaurant r where r.ownerUserId = :ownerUserId")
    Optional<Restaurant> findByOwnerUserIdForUpdate(@Param("ownerUserId") UUID ownerUserId);

    /** Keyset page of ever-approved restaurants (= those with a public listing), in id order. */
    @Query("select r from Restaurant r where r.approvedAt is not null and r.id > :after order by r.id")
    List<Restaurant> findListedAfter(@Param("after") UUID after, Limit limit);

    boolean existsByOwnerUserId(UUID ownerUserId);

    /** The admin search's filters, shared by the list and its export (alias {@code r}). */
    String ADMIN_SEARCH = """
            from Restaurant r
            where (:status is null or r.status = :status)
              and (:query is null or lower(r.name) like :query
                   or lower(r.contactEmail) like :query or lower(r.address.city) like :query)
            """;

    /** Admin search; filters optional. {@code query} is lower-cased with % wildcards. */
    @Query("select r " + ADMIN_SEARCH)
    Page<Restaurant> search(@Param("status") RestaurantStatus status, @Param("query") String query, Pageable pageable);
}
