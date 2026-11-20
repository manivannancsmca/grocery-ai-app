package com.grocery.common.web;

import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.export.ExportStreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Turns every error into an RFC 9457 ProblemDetail response with a stable {@code errorCode} and
 * the request's {@code correlationId}. Internal details (stack traces, SQL) never leave the service.
 *
 * <p>Services get this automatically; to customise, declare your own bean of this type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setProperty("errorCode", ex.errorCode());
        ex.properties().forEach(problem::setProperty);
        addCorrelationId(problem);
        return ResponseEntity.status(ex.status()).body(problem);
    }

    /**
     * 0.10.0: an export that failed after part of the file was sent. No error body can be written any more, so the
     * exception is rethrown on purpose: it reaches the servlet container, which closes the connection without ending
     * the response, and the client's download fails instead of keeping a truncated file that looks complete.
     */
    @ExceptionHandler(ExportStreamException.class)
    public void handleExportStreamFailure(ExportStreamException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
        problem.setProperty("errorCode", "INTERNAL_ERROR");
        addCorrelationId(problem);
        return ResponseEntity.internalServerError().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Adds the correlation ID to the ProblemDetail bodies produced by Spring MVC's own handlers. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            addCorrelationId(problem);
            if (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode")) {
                problem.setProperty("errorCode", "REQUEST_ERROR");
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static void addCorrelationId(ProblemDetail problem) {
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
