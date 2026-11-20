package com.grocery.ai.chat;

import com.grocery.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The chat errors as ProblemDetail (like grocery-common's handler), always {@code application/problem+json} even when
 * the client asked for {@code text/event-stream}, and 429 AI_RATE_LIMITED with a {@code Retry-After} header (seconds).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ChatExceptionAdvice {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ProblemDetail> chat(ChatException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setProperty("errorCode", ex.errorCode());
        ex.properties().forEach(problem::setProperty);
        if (CorrelationId.current() != null) {
            problem.setProperty("correlationId", CorrelationId.current());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (ex.properties().get("retryAfterSeconds") instanceof Number seconds) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return response.body(problem);
    }
}
