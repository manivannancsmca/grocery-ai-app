package com.grocery.review.service;

import com.grocery.common.web.ApiException;
import com.grocery.review.entity.ReviewStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The review errors of contract §1. Unlike grocery-common's fixed-status exceptions they may carry problem
 * properties ({@code reason}, {@code currentStatus}, {@code reviewId}, {@code errors}), which grocery-common's
 * {@code GlobalExceptionHandler} adds to the ProblemDetail body.
 */
public class ReviewException extends ApiException {

    /** Why a target cannot be reviewed ({@code reason} of 422 REVIEW_NOT_ELIGIBLE). */
    public enum NotEligible {
        /** Unknown order, another customer's order, or not delivered (yet). */
        NOT_DELIVERED,
        /** The target is not a product / dish, the store or the delivery partner of that order. */
        NOT_IN_ORDER,
        /** More than the review window (30 days) since delivery. */
        WINDOW_OVER
    }

    public ReviewException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    public static ReviewException notEligible(NotEligible reason) {
        String message = switch (reason) {
            case NOT_DELIVERED -> "Only delivered orders can be reviewed";
            case NOT_IN_ORDER -> "This order does not contain what you want to review";
            case WINDOW_OVER -> "The time to review this order is over";
        };
        return new ReviewException(HttpStatus.UNPROCESSABLE_CONTENT, "REVIEW_NOT_ELIGIBLE", message,
                Map.of("reason", reason.name()));
    }

    public static ReviewException alreadyReviewed(UUID reviewId) {
        return new ReviewException(HttpStatus.CONFLICT, "ALREADY_REVIEWED",
                "You have already reviewed this for this order", Map.of("reviewId", reviewId.toString()));
    }

    /** A concurrent submission won the unique key (its id is not known here). */
    public static ReviewException alreadyReviewed() {
        return new ReviewException(HttpStatus.CONFLICT, "ALREADY_REVIEWED",
                "You have already reviewed this for this order", Map.of());
    }

    public static ReviewException editWindowOver() {
        return new ReviewException(HttpStatus.UNPROCESSABLE_CONTENT, "REVIEW_EDIT_WINDOW_OVER",
                "A review can only be edited for 7 days after it was written", Map.of());
    }

    /** Unknown review, or one the caller may not see (another customer's, another store's, not published). */
    public static ReviewException notFound() {
        return new ReviewException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review not found", Map.of());
    }

    public static ReviewException notModeratable(ReviewStatus current, String action) {
        return new ReviewException(HttpStatus.CONFLICT, "REVIEW_NOT_MODERATABLE",
                "A " + current + " review cannot be " + action, Map.of("currentStatus", current.name()));
    }

    /** 400 VALIDATION_FAILED with one field error, shaped like bean-validation errors. */
    public static ReviewException validation(String field, String message) {
        return new ReviewException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
