package com.grocery.review.repository;

import com.grocery.review.entity.Review;
import com.grocery.review.entity.ReviewStatus;
import com.grocery.review.entity.TargetType;
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

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /** The review, row-locked until the transaction ends (every change starts here). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findByIdForUpdate(@Param("id") UUID id);

    Optional<Review> findByCustomerIdAndOrderIdAndTargetTypeAndTargetId(UUID customerId, UUID orderId,
                                                                        TargetType targetType, UUID targetId);

    List<Review> findByCustomerIdAndOrderIdIn(UUID customerId, Collection<UUID> orderIds);

    Page<Review> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * The customer's own reviews, every status: the filter of {@link #findByCustomerId} (the list) for its export
     * (alias {@code r}).
     */
    String CUSTOMER_REVIEWS = """
            from Review r
            where r.customerId = :customerId
            """;

    /** Published reviews of one target (the public listing). */
    Page<Review> findByTargetTypeAndTargetIdAndStatus(TargetType targetType, UUID targetId, ReviewStatus status,
                                                      Pageable pageable);

    /** The store list's filters, shared by the list, its count and its export (alias {@code r}). */
    String STORE_REVIEWS = """
            from Review r
            where r.storeId = :storeId and r.status = :status and r.targetType in :targetTypes
              and (:rating is null or r.rating = :rating)
            """;

    /** A store's published reviews of the given target types, optionally with one rating. */
    @Query("select r " + STORE_REVIEWS)
    Page<Review> findStoreReviews(@Param("storeId") UUID storeId, @Param("status") ReviewStatus status,
                                  @Param("targetTypes") Collection<TargetType> targetTypes,
                                  @Param("rating") Integer rating, Pageable pageable);

    /** The moderation list's filters, shared by the list, its count and its export (alias {@code r}). */
    String MODERATION = """
            from Review r
            where r.status = :status
              and (:targetType is null or r.targetType = :targetType)
              and (:q is null
                   or lower(r.title) like :q or lower(r.comment) like :q or lower(r.authorName) like :q
                   or lower(r.targetName) like :q or lower(r.orderNumber) like :q)
            """;

    /**
     * The admin moderation list: one status, optionally one target type, optionally a text ({@code q}, already
     * lower-cased and wrapped in {@code %}) found in the title, comment, author, target name or order number.
     */
    @Query("select r " + MODERATION)
    Page<Review> findForModeration(@Param("status") ReviewStatus status, @Param("targetType") TargetType targetType,
                                   @Param("q") String q, Pageable pageable);
}
