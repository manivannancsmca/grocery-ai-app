package com.grocery.review.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.common.web.PageResponse;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.dto.ReviewExports;
import com.grocery.review.dto.ReviewMapper;
import com.grocery.review.entity.Review;
import com.grocery.review.entity.ReviewStatus;
import com.grocery.review.entity.TargetType;
import com.grocery.review.repository.ReviewRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The store side of contract §1: a vendor sees the published reviews of its store and products, a restaurant owner
 * those of its restaurant and dishes, and may write one public reply per review (editable). Reviews of the delivery
 * partner are not the store's. The store comes from the JWT ({@code vendor_id} / {@code restaurant_id}).
 */
@Service
public class StoreReviewService {

    /** The caller's store and the target types that belong to it. */
    record Store(UUID id, Set<TargetType> targetTypes) {
    }

    /** The list's filters: the caller's store, its target types (narrowed to one, maybe to none) and the rating. */
    private record StoreFilter(UUID storeId, Set<TargetType> targetTypes, Integer rating) {
    }

    private final ReviewRepository reviews;
    private final ReviewEventPublisher events;
    private final ReviewMapper mapper;
    private final ExportQueries exports;
    private final Clock clock;

    public StoreReviewService(ReviewRepository reviews, ReviewEventPublisher events, ReviewMapper mapper,
                              ExportQueries exports, Clock clock) {
        this.reviews = reviews;
        this.events = events;
        this.mapper = mapper;
        this.exports = exports;
        this.clock = clock;
    }

    /**
     * Published reviews of the caller's store and of its products or dishes, newest first.
     *
     * @param targetType optional: only this type (another store type's or DELIVERY_PARTNER gives an empty page)
     * @param rating     optional: only this many stars (1–5)
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewView> list(CurrentUser user, String targetType, Integer rating, Integer page,
                                         Integer size) {
        StoreFilter filter = storeFilter(user, targetType, rating);
        var pageable = Pages.request(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        if (filter.targetTypes().isEmpty()) {
            return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        }
        return Pages.toResponse(reviews.findStoreReviews(filter.storeId(), ReviewStatus.PUBLISHED,
                filter.targetTypes(), filter.rating(), pageable), mapper::view);
    }

    /**
     * {@code GET /api/store/reviews/export}: the list's filters on every page, newest first, in keyset batches (the
     * store from the JWT, like the list). No transaction here: each batch has its own.
     */
    public RowSource<ReviewView> listExport(CurrentUser user, String targetType, Integer rating) {
        StoreFilter filter = storeFilter(user, targetType, rating);
        if (filter.targetTypes().isEmpty()) {
            return RowSource.of(List.of());
        }
        return exports.jpql(Review.class, "r", ReviewRepository.STORE_REVIEWS)
                .param("storeId", filter.storeId())
                .param("status", ReviewStatus.PUBLISHED)
                .param("targetTypes", filter.targetTypes())
                .param("rating", filter.rating())
                .orderBy(ReviewExports.NEWEST_FIRST)
                .rows(mapper::view);
    }

    /** The caller's store and the list's filters; 400 INVALID_PARAMETER for an unknown type or a rating not 1–5. */
    private static StoreFilter storeFilter(CurrentUser user, String targetType, Integer rating) {
        Store store = storeOf(user);
        Set<TargetType> types = store.targetTypes();
        TargetType only = BadRequestException.parseEnum(TargetType.class, "targetType",
                targetType == null ? null : targetType.strip().toUpperCase(Locale.ROOT));
        if (only != null) {
            types = types.contains(only) ? Set.of(only) : Set.of();
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new BadRequestException("INVALID_PARAMETER", "rating must be between 1 and 5");
        }
        return new StoreFilter(store.id(), types, rating);
    }

    /** The store's reply (first or edited) to one of its published reviews; anything else is 404. */
    @Transactional
    public ReviewView reply(CurrentUser user, UUID reviewId, String text) {
        Store store = storeOf(user);
        Review review = reviews.findByIdForUpdate(reviewId)
                .filter(r -> r.getStoreId().equals(store.id()) && store.targetTypes().contains(r.getTargetType())
                        && r.getStatus() == ReviewStatus.PUBLISHED)
                .orElseThrow(ReviewException::notFound);
        review.reply(text.strip(), user.userId(), clock.instant());
        reviews.saveAndFlush(review);
        events.replied(review);
        return mapper.view(review);
    }

    /** A vendor's store or a restaurant; 403 VENDOR_NOT_ACTIVE / RESTAURANT_NOT_ACTIVE without one in the token. */
    static Store storeOf(CurrentUser user) {
        if (user.hasRole(Roles.VENDOR)) {
            return new Store(user.requireVendorId(), TargetType.VENDOR_STORE);
        }
        if (user.hasRole(Roles.RESTAURANT_OWNER)) {
            return new Store(user.requireRestaurantId(), TargetType.RESTAURANT_STORE);
        }
        throw new ForbiddenOperationException("FORBIDDEN", "Only vendors and restaurant owners have store reviews");
    }
}
