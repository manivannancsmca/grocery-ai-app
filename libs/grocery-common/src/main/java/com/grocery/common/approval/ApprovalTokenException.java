package com.grocery.common.approval;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A refused approval token, answered by {@link ApprovalTokenFilter} as a ProblemDetail (contract Phase 11 §5):
 * <ul>
 *   <li>403 {@code APPROVAL_TOKEN_INVALID} with {@code reason} {@link Reason};</li>
 *   <li>403 {@code APPROVAL_BODY_MISMATCH};</li>
 *   <li>409 {@code APPROVAL_ALREADY_USED};</li>
 *   <li>503 {@code APPROVAL_KEYS_UNAVAILABLE}.</li>
 * </ul>
 * The message is the public {@code detail}; {@link #logDetail()} says precisely what failed and is only logged.
 */
public class ApprovalTokenException extends ApiException {

    public static final String TOKEN_INVALID = "APPROVAL_TOKEN_INVALID";
    public static final String BODY_MISMATCH = "APPROVAL_BODY_MISMATCH";
    public static final String ALREADY_USED = "APPROVAL_ALREADY_USED";
    public static final String KEYS_UNAVAILABLE = "APPROVAL_KEYS_UNAVAILABLE";

    /** Which check an {@code APPROVAL_TOKEN_INVALID} token failed. */
    public enum Reason {
        /** Not a well-formed RS256 {@code approval+jwt} JWS verifiable with a key of the JWKS. */
        SIGNATURE("The approval token could not be verified."),
        /** Expired (beyond the clock skew), or issued in the future. */
        EXPIRED("The approval token has expired."),
        /** {@code aud} is not exactly this service, or {@code iss} is not the orchestrator. */
        AUDIENCE("The approval token is not meant for this service."),
        /** {@code htm} / {@code htu} differ from this request's method and path. */
        METHOD_OR_PATH("The approval token was issued for a different request."),
        /** {@code sub} differs from the authenticated user. */
        SUBJECT("The approval token was issued to another user.");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }
    }

    private final Reason reason;
    private final String logDetail;

    private ApprovalTokenException(HttpStatus status, String errorCode, String detail, Reason reason,
                                   String logDetail, Throwable cause) {
        super(status, errorCode, detail, reason == null ? Map.of() : Map.of("reason", reason.name()));
        this.reason = reason;
        this.logDetail = logDetail;
        if (cause != null) {
            initCause(cause);
        }
    }

    public static ApprovalTokenException invalid(Reason reason, String logDetail) {
        return new ApprovalTokenException(HttpStatus.FORBIDDEN, TOKEN_INVALID, reason.detail, reason, logDetail, null);
    }

    public static ApprovalTokenException bodyMismatch(String logDetail) {
        return new ApprovalTokenException(HttpStatus.FORBIDDEN, BODY_MISMATCH,
                "The request body differs from the approved one.", null, logDetail, null);
    }

    public static ApprovalTokenException alreadyUsed() {
        return new ApprovalTokenException(HttpStatus.CONFLICT, ALREADY_USED, "This approval has already been used.",
                null, "jti already in consumed_approval", null);
    }

    public static ApprovalTokenException keysUnavailable(String logDetail, Throwable cause) {
        return new ApprovalTokenException(HttpStatus.SERVICE_UNAVAILABLE, KEYS_UNAVAILABLE,
                "The approval keys cannot be loaded right now. Please try again later.", null, logDetail, cause);
    }

    /** The failed check of an {@code APPROVAL_TOKEN_INVALID}, otherwise {@code null}. */
    public Reason reason() {
        return reason;
    }

    /** What exactly failed, for the log only (never sent to the client). */
    public String logDetail() {
        return logDetail;
    }
}
