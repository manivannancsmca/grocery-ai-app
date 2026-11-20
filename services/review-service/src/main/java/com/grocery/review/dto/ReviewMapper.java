package com.grocery.review.dto;

import com.grocery.review.config.ReviewProperties;
import com.grocery.review.dto.ReviewDtos.PublicReview;
import com.grocery.review.dto.ReviewDtos.RatingSummaryView;
import com.grocery.review.dto.ReviewDtos.ReplyView;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.entity.Review;
import com.grocery.review.repository.RatingSummaries.Summary;
import org.springframework.stereotype.Component;

@Component
public class ReviewMapper {

    private final ReviewProperties properties;

    public ReviewMapper(ReviewProperties properties) {
        this.properties = properties;
    }

    public ReviewView view(Review r) {
        return new ReviewView(r.getId(), r.getOrderId(), r.getOrderNumber(), r.getTargetType().name(),
                r.getTargetId(), r.getTargetName(), r.getStoreId(), r.getStoreName(), r.getCustomerId(),
                r.getAuthorName(),
                r.getRating(), r.getTitle(), r.getComment(), r.getStatus().name(), r.getFlagReason(),
                r.getModerationReason(), r.getModeratedAt(), r.isEdited(), reply(r), r.getCreatedAt(),
                r.getSubmittedAt(), r.getUpdatedAt(), r.getCreatedAt().plus(properties.editWindow()));
    }

    public PublicReview publicView(Review r) {
        return new PublicReview(r.getId(), r.getTargetType().name(), r.getTargetId(), r.getAuthorName(),
                r.getRating(), r.getTitle(), r.getComment(), r.isEdited(), reply(r), r.getCreatedAt(),
                r.getSubmittedAt());
    }

    public RatingSummaryView summary(Summary s) {
        return new RatingSummaryView(s.targetType().name(), s.targetId(), s.averageRating(), s.ratingCount(),
                s.distribution());
    }

    private static ReplyView reply(Review r) {
        return r.getReplyText() == null ? null
                : new ReplyView(r.getReplyText(), r.getRepliedAt(), r.getReplyUpdatedAt());
    }
}
