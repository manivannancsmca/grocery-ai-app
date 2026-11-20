package com.grocery.ai.voice;

import com.grocery.ai.config.AiProperties;
import com.grocery.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Voice errors as ProblemDetail, always {@code application/problem+json} (the speech endpoint answers audio, so the
 * client's Accept header would otherwise reject the error), with {@code Retry-After} for VOICE_RATE_LIMITED. A
 * recording above the servlet's multipart limit is AUDIO_TOO_LARGE like one above the service's own limit.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class VoiceExceptionAdvice {

    private final long maxAudioBytes;

    public VoiceExceptionAdvice(AiProperties properties) {
        this.maxAudioBytes = properties.voice().maxAudioBytes();
    }

    @ExceptionHandler(VoiceException.class)
    public ResponseEntity<ProblemDetail> voice(VoiceException ex) {
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException ex) {
        return voice(VoiceException.tooLarge(maxAudioBytes));
    }
}
