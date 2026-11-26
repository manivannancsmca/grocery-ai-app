package com.grocery.ai.knowledge.document;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A document as the admin API shows it (never the entity, the file's hash, the text or any vector).
 *
 * @param contentVersion files uploaded for it (1 = the first)
 * @param searchable     the assistants can use it now (a version of it is indexed and it is not deleted). While a new
 *                       version is PROCESSING or FAILED, the previous one is still used
 * @param chunkCount     chunks of the version in use
 * @param failureReason  why the last indexing failed (FAILED), or why it is being tried again (UPLOADED)
 */
public record DocumentView(UUID id, String title, KnowledgeCategory category, Set<KnowledgeAudience> audiences,
                           String fileName, DocumentFormat format, long sizeBytes, int contentVersion,
                           DocumentStatus status, boolean searchable, int chunkCount, String embeddingModel,
                           String failureReason, Person createdBy, Person updatedBy, Instant createdAt,
                           Instant updatedAt, Instant processedAt) {

    public record Person(UUID id, String name) {
    }

    public static DocumentView of(KnowledgeDocument document) {
        return new DocumentView(document.getId(), document.getTitle(), document.getCategory(),
                document.getAudiences(), document.getFileName(), document.getFormat(), document.getSizeBytes(),
                document.getContentVersion(), document.getStatus(), document.isSearchable(), document.getChunkCount(),
                document.getEmbeddingModel(), document.getFailureReason(),
                new Person(document.getCreatedBy(), document.getCreatedByName()),
                new Person(document.getUpdatedBy(), document.getUpdatedByName()), document.getCreatedAt(),
                document.getUpdatedAt(), document.getProcessedAt());
    }
}
