package com.grocery.review.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.review.config.ReviewProperties;
import com.grocery.review.dto.ReviewDtos.EditReviewRequest;
import com.grocery.review.dto.ReviewDtos.EligibleTarget;
import com.grocery.review.dto.ReviewDtos.PublicReview;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.dto.ReviewDtos.SubmitReviewRequest;
import com.grocery.review.dto.ReviewExports;
import com.grocery.review.dto.ReviewMapper;
import com.grocery.review.entity.Review;
import com.grocery.review.entity.ReviewStatus;
import com.grocery.review.entity.TargetType;
import com.grocery.review.repository.OrderProjection;
import com.grocery.review.repository.OrderProjection.OrderItem;
import com.grocery.review.repository.OrderProjection.ProjectedOrder;
import com.grocery.review.repository.ReviewRepository;
import com.grocery.review.service.ReviewException.NotEligible;
import com.grocery.review.settings.ReviewSettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer side of contract §1: what can be reviewed, submitting, editing and deleting reviews, my reviews, and
 * the public listing. A change of a target's published reviews recomputes its rating summary in the same transaction.
 */
@Service
public class ReviewService {

    static final String DEFAULT_VENDOR_NAME = "Store";
    static final String DEFAULT_RESTAURANT_NAME = "Restaurant";
    static final String DELIVERY_PARTNER_NAME = "Delivery partner";

    /** Something of an order that can be reviewed. */
    record Target(TargetType type, UUID id, String name) {
    }

    private record Key(UUID orderId, TargetType type, UUID targetId) {
    }

    public enum PublicSort { NEWEST, HIGHEST, LOWEST }

    private final ReviewRepository reviews;
    private final OrderProjection orders;
    private final AutoModerator moderator;
    private final RatingSummaryService summaries;
    private final ReviewEventPublisher events;
    private final ReviewMapper mapper;
    private final ReviewProperties properties;
    private final ReviewSettingsService settings;
    private final ExportQueries exports;
    private final Clock clock;

    public ReviewService(ReviewRepository reviews, OrderProjection orders, AutoModerator moderator,
                         RatingSummaryService summaries, ReviewEventPublisher events, ReviewMapper mapper,
                         ReviewProperties properties, ReviewSettingsService settings, ExportQueries exports,
                         Clock clock) {
        this.reviews = reviews;
        this.orders = orders;
        this.moderator = moderator;
        this.summaries = summaries;
        this.events = events;
        this.mapper = mapper;
        this.properties = properties;
        this.settings = settings;
        this.exports = exports;
        this.clock = clock;
    }

    /**
     * The targets of the caller's delivered orders that are still in the review window, each with the caller's review
     * of it (or null): one order with {@code orderId} (empty when it is not the caller's, not delivered or too old),
     * otherwise every such order, newest delivery first. Per order: its products or dishes in line order, then the
     * store, then the delivery partner.
     */
    @Transactional(readOnly = true)
    public List<EligibleTarget> eligible(CurrentUser user, UUID orderId) {
        Instant since = clock.instant().minus(settings.current().reviewWindow());     // the admin setting in force
        List<ProjectedOrder> delivered = orderId != null
                ? orders.find(orderId)
                        .filter(o -> o.customerId().equals(user.userId()) && o.delivered()
                                && !o.deliveredAt().isBefore(since))
                        .map(List::of).orElse(List.of())
                : orders.deliveredSince(user.userId(), since, Math.max(1, properties.eligibleOrdersLimit()));
        if (delivered.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = delivered.stream().map(ProjectedOrder::orderId).toList();
        Map<UUID, List<OrderItem>> items = orders.items(orderIds);
        Map<Key, Review> mine = new HashMap<>();
        for (Review review : reviews.findByCustomerIdAndOrderIdIn(user.userId(), orderIds)) {
            mine.put(new Key(review.getOrderId(), review.getTargetType(), review.getTargetId()), review);
        }
        List<EligibleTarget> result = new ArrayList<>();
        for (ProjectedOrder order : delivered) {
            for (Target target : targets(order, items.getOrDefault(order.orderId(), List.of()))) {
                Review review = mine.get(new Key(order.orderId(), target.type(), target.id()));
                result.add(new EligibleTarget(order.orderId(), order.orderNumber(), order.deliveredAt(),
                        target.type().name(), target.id(), target.name(), review == null ? null : mapper.view(review)));
            }
        }
        return result;
    }

    /**
     * A new review: the order must be the caller's, delivered, within the review window and contain the target
     * (422 REVIEW_NOT_ELIGIBLE), and not reviewed yet (409 ALREADY_REVIEWED). Moderated automatically.
     */
    @Transactional
    public ReviewView submit(CurrentUser user, SubmitReviewRequest request) {
        TargetType targetType = targetType(request.targetType());
        String title = text(request.title());
        String comment = text(request.comment());
        Instant now = clock.instant();
        // locked: submissions for one order are serialised, so the duplicate check below cannot race
        ProjectedOrder order = orders.findForUpdate(request.orderId())
                .filter(o -> o.customerId().equals(user.userId()) && o.delivered())
                .orElseThrow(() -> ReviewException.notEligible(NotEligible.NOT_DELIVERED));
        if (now.isAfter(order.deliveredAt().plus(settings.current().reviewWindow()))) {
            throw ReviewException.notEligible(NotEligible.WINDOW_OVER);
        }
        Target target = targetOf(order, targetType, request.targetId())
                .orElseThrow(() -> ReviewException.notEligible(NotEligible.NOT_IN_ORDER));
        reviews.findByCustomerIdAndOrderIdAndTargetTypeAndTargetId(user.userId(), order.orderId(), target.type(),
                        target.id())
                .ifPresent(existing -> {
                    throw ReviewException.alreadyReviewed(existing.getId());
                });

        Review review = Review.submit(Ids.newId(), user.userId(), order.orderId(), order.orderNumber(), target.type(),
                target.id(), target.name(), order.storeId(), order.storeName(), request.rating(), title, comment,
                AuthorNames.publicName(user.name()), moderator.moderate(title, comment), now);
        try {
            reviews.saveAndFlush(review);       // flushed: the summary below is recomputed with SQL
        } catch (DataIntegrityViolationException e) {
            throw ReviewException.alreadyReviewed();
        }
        events.submitted(review);
        if (review.getStatus() == ReviewStatus.PUBLISHED) {
            summaries.recompute(review.getTargetType(), review.getTargetId());
        }
        return mapper.view(review);
    }

    /** The author's edit, within 7 days of writing it (422 REVIEW_EDIT_WINDOW_OVER); moderated again. */
    @Transactional
    public ReviewView edit(CurrentUser user, UUID reviewId, EditReviewRequest request) {
        Review review = own(user, reviewId);
        Instant now = clock.instant();
        if (now.isAfter(review.getCreatedAt().plus(properties.editWindow()))) {
            throw ReviewException.editWindowOver();
        }
        String title = text(request.title());
        String comment = text(request.comment());
        boolean wasPublished = review.getStatus() == ReviewStatus.PUBLISHED;
        review.edit(request.rating(), title, comment, AuthorNames.publicName(user.name()),
                moderator.moderate(title, comment), now);
        reviews.saveAndFlush(review);
        events.submitted(review);
        if (wasPublished || review.getStatus() == ReviewStatus.PUBLISHED) {
            summaries.recompute(review.getTargetType(), review.getTargetId());
        }
        return mapper.view(review);
    }

    /** The author deletes the review (any time, any status); a published one leaves the rating summary. */
    @Transactional
    public void delete(CurrentUser user, UUID reviewId) {
        Review review = own(user, reviewId);
        boolean wasPublished = review.getStatus() == ReviewStatus.PUBLISHED;
        reviews.delete(review);
        reviews.flush();
        if (wasPublished) {
            summaries.recompute(review.getTargetType(), review.getTargetId());
        }
    }

    /** The caller's reviews, every status, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReviewView> mine(CurrentUser user, Integer page, Integer size) {
        return Pages.toResponse(reviews.findByCustomerId(user.userId(),
                Pages.request(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), mapper::view);
    }

    /**
     * {@code GET /api/reviews/mine/export}: the caller's reviews, every status, newest first, in keyset batches. No
     * transaction here: each batch has its own.
     */
    public RowSource<ReviewView> mineExport(CurrentUser user) {
        return exports.jpql(Review.class, "r", ReviewRepository.CUSTOMER_REVIEWS)
                .param("customerId", user.userId())
                .orderBy(ReviewExports.NEWEST_FIRST)
                .rows(mapper::view);
    }

    /** Published reviews of a target. */
    @Transactional(readOnly = true)
    public PageResponse<PublicReview> published(TargetType targetType, UUID targetId, PublicSort sort, Integer page,
                                                Integer size) {
        Sort order = switch (sort) {
            case NEWEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case HIGHEST -> Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case LOWEST -> Sort.by(Sort.Order.asc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
        return Pages.toResponse(reviews.findByTargetTypeAndTargetIdAndStatus(targetType, targetId,
                ReviewStatus.PUBLISHED, Pages.request(page, size, order)), mapper::publicView);
    }

    /** The caller's own review, row-locked; another customer's is "not found". */
    private Review own(CurrentUser user, UUID reviewId) {
        return reviews.findByIdForUpdate(reviewId)
                .filter(review -> review.getCustomerId().equals(user.userId()))
                .orElseThrow(ReviewException::notFound);
    }

    /** Products or dishes in line order, then the store, then the delivery partner. */
    static List<Target> targets(ProjectedOrder order, List<OrderItem> items) {
        List<Target> targets = new ArrayList<>();
        for (OrderItem item : items) {
            TargetType type = TargetType.itemOf(item.itemType());
            if (type != null) {
                targets.add(new Target(type, item.itemId(), item.name()));
            }
        }
        store(order).ifPresent(targets::add);
        if (order.partnerId() != null) {
            targets.add(new Target(TargetType.DELIVERY_PARTNER, order.partnerId(), DELIVERY_PARTNER_NAME));
        }
        return targets;
    }

    private Optional<Target> targetOf(ProjectedOrder order, TargetType type, UUID targetId) {
        return switch (type) {
            case PRODUCT, MENU_ITEM -> orders.item(order.orderId(), type.name(), targetId)
                    .map(item -> new Target(type, item.itemId(), item.name()));
            case VENDOR, RESTAURANT -> store(order).filter(store -> store.type() == type && store.id().equals(targetId));
            case DELIVERY_PARTNER -> targetId.equals(order.partnerId())
                    ? Optional.of(new Target(type, targetId, DELIVERY_PARTNER_NAME)) : Optional.empty();
        };
    }

    private static Optional<Target> store(ProjectedOrder order) {
        TargetType type = TargetType.storeOf(order.orderType());
        if (type == null) {
            return Optional.empty();
        }
        String name = order.storeName() != null ? order.storeName()
                : type == TargetType.VENDOR ? DEFAULT_VENDOR_NAME : DEFAULT_RESTAURANT_NAME;
        return Optional.of(new Target(type, order.storeId(), name));
    }

    static TargetType targetType(String value) {
        try {
            return TargetType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ReviewException.validation("targetType",
                    "must be one of PRODUCT, MENU_ITEM, VENDOR, RESTAURANT, DELIVERY_PARTNER");
        }
    }

    /** Stripped; blank = none. */
    static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
