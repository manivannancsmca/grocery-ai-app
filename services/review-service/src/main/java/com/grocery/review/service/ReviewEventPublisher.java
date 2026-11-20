package com.grocery.review.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.review.entity.Review;
import com.grocery.review.event.ReviewEvents;
import com.grocery.review.event.ReviewEvents.RatingSummaryUpdated;
import com.grocery.review.event.ReviewEvents.ReviewModerated;
import com.grocery.review.event.ReviewEvents.ReviewReplied;
import com.grocery.review.event.ReviewEvents.ReviewSubmitted;
import com.grocery.review.repository.RatingSummaries.Summary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes review.events to the outbox, always inside the transaction of the change they describe. */
@Component
public class ReviewEventPublisher {

    private final OutboxPublisher outbox;

    public ReviewEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void submitted(Review r) {
        review(ReviewEvents.REVIEW_SUBMITTED, r, new ReviewSubmitted(r.getId(), r.getTargetType().name(),
                r.getTargetId(), r.getStoreId(), r.getCustomerId(), r.getOrderId(), r.getRating(),
                r.getStatus().name(), r.getFlagReason(), r.isEdited(), r.getSubmittedAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void moderated(Review r) {
        review(ReviewEvents.REVIEW_MODERATED, r, new ReviewModerated(r.getId(), r.getTargetType().name(),
                r.getTargetId(), r.getStoreId(), r.getCustomerId(), r.getStatus().name(), r.getModerationReason(),
                r.getModeratedBy(), r.getModeratedAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replied(Review r) {
        review(ReviewEvents.REVIEW_REPLIED, r, new ReviewReplied(r.getId(), r.getTargetType().name(),
                r.getTargetId(), r.getStoreId(), r.getCustomerId(), r.getReplyBy(), r.getReplyUpdatedAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ratingSummaryUpdated(Summary s) {
        outbox.publish(OutboxEvent.of(ReviewEvents.TOPIC, ReviewEvents.RATING_SUMMARY, s.targetId().toString(),
                ReviewEvents.RATING_SUMMARY_UPDATED, new RatingSummaryUpdated(s.targetType().name(), s.targetId(),
                        s.averageRating(), s.ratingCount(), s.distribution(), s.version(), s.updatedAt())));
    }

    private void review(String eventType, Review review, Object payload) {
        outbox.publish(OutboxEvent.of(ReviewEvents.TOPIC, ReviewEvents.REVIEW, review.getId().toString(), eventType,
                payload));
    }
}
