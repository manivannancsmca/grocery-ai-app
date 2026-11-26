package com.grocery.ai.knowledge;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The knowledge base's errors, as problem+json with a stable {@code errorCode} (docs/rag/RAG-API-REFERENCE.md). */
public class KnowledgeException extends ApiException {

    private KnowledgeException(HttpStatus status, String errorCode, String message) {
        super(status, errorCode, message);
    }

    private KnowledgeException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    public static KnowledgeException disabled() {
        return new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "KNOWLEDGE_BASE_DISABLED",
                "The knowledge base is switched off (grocery.ai.knowledge.enabled).");
    }

    /** Elasticsearch or the embedding model could not be reached; the reason is logged, not shown. */
    public static KnowledgeException unavailable() {
        return new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "KNOWLEDGE_BASE_UNAVAILABLE",
                "The knowledge base cannot be reached right now. Try again in a minute.");
    }

    public static KnowledgeException notFound(UUID id) {
        return new KnowledgeException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "No document " + id + ".");
    }

    public static KnowledgeException unsupportedType() {
        return new KnowledgeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_DOCUMENT_TYPE",
                "Upload a PDF, Word (.docx), text (.txt), Markdown (.md) or HTML (.html) file.");
    }

    public static KnowledgeException tooLarge(long maxBytes) {
        return new KnowledgeException(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE",
                "The file is larger than " + maxBytes / (1024 * 1024) + " MB.", Map.of("maxBytes", maxBytes));
    }

    /** The file is not what its name says, is damaged, encrypted or has macros. */
    public static KnowledgeException invalid(String why) {
        return new KnowledgeException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", why);
    }

    public static KnowledgeException empty() {
        return new KnowledgeException(HttpStatus.BAD_REQUEST, "EMPTY_DOCUMENT",
                "The document has no text. A scanned PDF needs text recognition (OCR) before it is uploaded.");
    }

    public static KnowledgeException tooLong(int maxCharacters) {
        return new KnowledgeException(HttpStatus.BAD_REQUEST, "DOCUMENT_TOO_LONG",
                "The document has more than " + maxCharacters + " characters of text. Split it into smaller "
                        + "documents.", Map.of("maxCharacters", maxCharacters));
    }

    public static KnowledgeException validation(String field, String message) {
        return new KnowledgeException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    /** The document is being deleted, or changed while the request was handled. */
    public static KnowledgeException conflict(String message) {
        return new KnowledgeException(HttpStatus.CONFLICT, "DOCUMENT_CHANGED", message);
    }
}
