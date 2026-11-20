package com.grocery.review.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Admin moderation (contract §1): FLAGGED → PUBLISHED or REJECTED, PUBLISHED → REMOVED; anything else is 409
 * REVIEW_NOT_MODERATABLE with {@code currentStatus}. Each decision publishes {@code ReviewModerated}; publishing and
 * removing change the target's rating summary.
 */
@Service
public class ModerationService {

    static final int MAX_QUERY = 100;

    private final ReviewRepository reviews;
    private final RatingSummaryService summaries;
    private final ReviewEventPublisher events;
    private final ReviewMapper mapper;
    private final ExportQueries exports;
    private final Clock clock;

    public ModerationService(ReviewRepository reviews, RatingSummaryService summaries, ReviewEventPublisher events,
                             ReviewMapper mapper, ExportQueries exports, Clock clock) {
        this.reviews = reviews;
        this.summaries = summaries;
        this.events = events;
        this.mapper = mapper;
        this.exports = exports;
        this.clock = clock;
    }

    /**
     * Reviews with one status (default FLAGGED), optionally one target type and a text found in the title, comment,
     * author, target name or order number; the most recently submitted first.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewView> list(String status, String targetType, String q, Integer page, Integer size) {
        return Pages.toResponse(reviews.findForModeration(wantedStatus(status), targetType(targetType), like(q),
                        Pages.request(page, size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")))),
                mapper::view);
    }

    /**
     * {@code GET /api/admin/reviews/export}: the list's filters on every page, the most recently submitted first, in
     * keyset batches. No transaction here: each batch has its own.
     */
    public RowSource<ReviewView> listExport(String status, String targetType, String q) {
        return exports.jpql(Review.class, "r", ReviewRepository.MODERATION)
                .param("status", wantedStatus(status))
                .param("targetType", targetType(targetType))
                .param("q", like(q))
                .orderBy(ReviewExports.RECENTLY_SUBMITTED)
                .rows(mapper::view);
    }

    /** The list's status: {@code status} in any case, FLAGGED when blank; 400 INVALID_PARAMETER when unknown. */
    public static ReviewStatus wantedStatus(String status) {
        return status == null || status.isBlank() ? ReviewStatus.FLAGGED
                : BadRequestException.parseEnum(ReviewStatus.class, "status", status.strip().toUpperCase(Locale.ROOT));
    }

    /** The list's optional target type, in any case; 400 INVALID_PARAMETER when unknown. */
    private static TargetType targetType(String targetType) {
        return BadRequestException.parseEnum(TargetType.class, "targetType",
                targetType == null ? null : targetType.strip().toUpperCase(Locale.ROOT));
    }

    @Transactional
    public ReviewView publish(CurrentUser admin, UUID reviewId) {
        Review review = locked(reviewId);
        if (review.getStatus() != ReviewStatus.FLAGGED) {
            throw ReviewException.notModeratable(review.getStatus(), "published");
        }
        return decide(review, ReviewStatus.PUBLISHED, null, admin);
    }

    @Transactional
    public ReviewView reject(CurrentUser admin, UUID reviewId, String reason) {
        Review review = locked(reviewId);
        if (review.getStatus() != ReviewStatus.FLAGGED) {
            throw ReviewException.notModeratable(review.getStatus(), "rejected");
        }
        return decide(review, ReviewStatus.REJECTED, reason.strip(), admin);
    }

    @Transactional
    public ReviewView remove(CurrentUser admin, UUID reviewId, String reason) {
        Review review = locked(reviewId);
        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw ReviewException.notModeratable(review.getStatus(), "removed");
        }
        return decide(review, ReviewStatus.REMOVED, reason.strip(), admin);
    }

    private ReviewView decide(Review review, ReviewStatus decision, String reason, CurrentUser admin) {
        boolean wasPublished = review.getStatus() == ReviewStatus.PUBLISHED;
        review.moderate(decision, reason, admin.userId(), clock.instant());
        reviews.saveAndFlush(review);           // flushed: the summary below is recomputed with SQL
        events.moderated(review);
        if (wasPublished || decision == ReviewStatus.PUBLISHED) {
            summaries.recompute(review.getTargetType(), review.getTargetId());
        }
        return mapper.view(review);
    }

    private Review locked(UUID reviewId) {
        return reviews.findByIdForUpdate(reviewId).orElseThrow(ReviewException::notFound);
    }

    /** {@code %text%}, lower-cased, LIKE wildcards escaped; null when blank. */
    static String like(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String text = q.strip().toLowerCase(Locale.ROOT);
        if (text.length() > MAX_QUERY) {
            text = text.substring(0, MAX_QUERY);
        }
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
