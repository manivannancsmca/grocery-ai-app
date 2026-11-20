package com.grocery.common.export;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** The export errors a client can act on (ProblemDetail with {@code errorCode}). */
public final class ExportException extends ApiException {

    private ExportException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** 429 EXPORT_BUSY: too many exports running (this user's, or the instance's). */
    static ExportException busy(boolean yours) {
        return new ExportException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_BUSY", yours
                ? "Your other exports are still running. Wait for one to finish, then try again."
                : "Many exports are running right now. Try again in a minute.", Map.of("retryAfterSeconds", 30));
    }

    /** 422 EXPORT_TOO_LARGE: more rows than the format (or a client-chosen order) allows. */
    static ExportException tooLarge(long rows, long limit, String hint) {
        return new ExportException(HttpStatus.UNPROCESSABLE_CONTENT, "EXPORT_TOO_LARGE",
                String.format("This export has %,d rows; the limit is %,d. %s", rows, limit, hint),
                Map.of("rows", rows, "limit", limit));
    }

    /** 404 EXPORT_DISABLED: {@code grocery.export.enabled=false}. */
    static ExportException disabled() {
        return new ExportException(HttpStatus.NOT_FOUND, "EXPORT_DISABLED", "Exports are switched off", Map.of());
    }
}
