package com.grocery.ai.voice;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.knowledge.KnowledgeException;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.common.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
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

    private static final String KNOWLEDGE_PATH = "/api/admin/ai/knowledge";

    private final long maxAudioBytes;
    private final long maxDocumentBytes;

    public VoiceExceptionAdvice(AiProperties properties, KnowledgeProperties knowledge) {
        this.maxAudioBytes = properties.voice().maxAudioBytes();
        this.maxDocumentBytes = knowledge.upload().maxFileSize().toBytes();
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

    /**
     * The servlet refused a multipart body above its limit before any controller ran: a knowledge base upload is
     * DOCUMENT_TOO_LARGE (its own limit is lower, so this only happens far above it), anything else a recording.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (request.getRequestURI().startsWith(KNOWLEDGE_PATH)) {
            KnowledgeException tooLarge = KnowledgeException.tooLarge(maxDocumentBytes);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(tooLarge.status(), tooLarge.getMessage());
            problem.setProperty("errorCode", tooLarge.errorCode());
            tooLarge.properties().forEach(problem::setProperty);
            return ResponseEntity.status(tooLarge.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        return voice(VoiceException.tooLarge(maxAudioBytes));
    }
}
