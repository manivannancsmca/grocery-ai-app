package com.grocery.ai.approval;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** The approval errors of contract §4; conflicts carry {@code currentStatus}. */
public class ApprovalException extends ApiException {

    public ApprovalException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown, or not visible to the caller (they never learn that it exists). */
    public static ApprovalException notFound() {
        return new ApprovalException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "Approval request not found",
                Map.of());
    }

    public static ApprovalException forbidden() {
        return new ApprovalException(HttpStatus.FORBIDDEN, "APPROVAL_FORBIDDEN",
                "You are not allowed to decide this request", Map.of());
    }

    public static ApprovalException fourEyesRequired() {
        return new ApprovalException(HttpStatus.FORBIDDEN, "FOUR_EYES_REQUIRED",
                "This request needs a second person: you proposed it, so someone else must decide it", Map.of());
    }

    public static ApprovalException notPending(ApprovalStatus status) {
        return new ApprovalException(HttpStatus.CONFLICT, "APPROVAL_NOT_PENDING",
                status == ApprovalStatus.EXPIRED ? "This request has expired"
                        : "This request is already " + status.name().toLowerCase().replace('_', ' '),
                Map.of("currentStatus", status.name()));
    }

    /** 400 VALIDATION_FAILED shaped like bean-validation errors. */
    public static ApprovalException validation(String field, String message) {
        return new ApprovalException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    public static ApprovalException invalidParameter(String message) {
        return new ApprovalException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message, Map.of());
    }
}
