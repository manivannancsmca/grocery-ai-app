package com.grocery.ai.knowledge.document;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One help-center document (table {@code knowledge_document}). Its text is in {@link KnowledgeDocumentText}, its
 * chunks in the knowledge index. See {@link DocumentStatus} for the life cycle and V9__knowledge_base.sql for
 * {@code contentVersion} (files uploaded), {@code generation} (times indexed) and {@code indexedGeneration} (the one
 * searches may return).
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeCategory category;

    @Column(nullable = false, length = 100)
    private String audiences;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentFormat format;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(nullable = false)
    private int generation;

    @Column(name = "indexed_generation")
    private Integer indexedGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DocumentStatus status;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "processing_since")
    private Instant processingSince;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_name", nullable = false, updatable = false, length = 200)
    private String createdByName;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_by_name", nullable = false, length = 200)
    private String updatedByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected KnowledgeDocument() {
    }

    /** Who changed a document (the admin, or "System" for the starter articles). */
    public record Editor(UUID id, String name) {
    }

    /** The file of an upload, already checked and read. */
    public record UploadedFile(String fileName, DocumentFormat format, long sizeBytes, String sha256) {
    }

    /** A newly uploaded document, waiting to be indexed (generation 1). */
    public static KnowledgeDocument upload(UUID id, String title, KnowledgeCategory category,
                                           Set<KnowledgeAudience> audiences, UploadedFile file, Editor by,
                                           Instant now) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.id = id;
        document.title = title;
        document.category = category;
        document.audiences = join(audiences);
        document.fileName = file.fileName();
        document.format = file.format();
        document.sizeBytes = file.sizeBytes();
        document.contentSha256 = file.sha256();
        document.contentVersion = 1;
        document.generation = 1;
        document.status = DocumentStatus.UPLOADED;
        document.createdBy = by.id();
        document.createdByName = by.name();
        document.updatedBy = by.id();
        document.updatedByName = by.name();
        document.createdAt = now;
        document.updatedAt = now;
        return document;
    }

    /**
     * A new file (and possibly new details) for this document: a new content version, indexed again. The previous
     * version stays searchable until the new one is indexed.
     */
    public void replaceFile(UploadedFile file, String newTitle, KnowledgeCategory newCategory,
                            Set<KnowledgeAudience> newAudiences, Editor by, Instant now) {
        fileName = file.fileName();
        format = file.format();
        sizeBytes = file.sizeBytes();
        contentSha256 = file.sha256();
        contentVersion++;
        changeDetails(newTitle, newCategory, newAudiences, by, now);
    }

    /** New title, category or audiences: they are stored with every chunk, so the document is indexed again. */
    public void changeDetails(String newTitle, KnowledgeCategory newCategory, Set<KnowledgeAudience> newAudiences,
                              Editor by, Instant now) {
        title = newTitle;
        category = newCategory;
        audiences = join(newAudiences);
        requestIngestion(by, now);
    }

    /** Whether these are the document's current title, category and audiences. */
    public boolean hasDetails(String otherTitle, KnowledgeCategory otherCategory,
                              Set<KnowledgeAudience> otherAudiences) {
        return title.equals(otherTitle) && category == otherCategory && getAudiences().equals(otherAudiences);
    }

    /** Index again (a new generation): after a new file, new details, or a reindex request. */
    public void requestIngestion(Editor by, Instant now) {
        generation++;
        status = DocumentStatus.UPLOADED;
        attempts = 0;
        failureReason = null;
        processingSince = null;
        updatedBy = by.id();
        updatedByName = by.name();
        updatedAt = now;
    }

    /** The ingestion worker took it. */
    public void startProcessing(Instant now) {
        status = DocumentStatus.PROCESSING;
        processingSince = now;
        attempts++;
        updatedAt = now;
    }

    /** Generation {@code completedGeneration} is indexed: from now on searches return it. */
    public void completed(int completedGeneration, int chunks, String model, Instant now) {
        indexedGeneration = completedGeneration;
        chunkCount = chunks;
        embeddingModel = model;
        status = DocumentStatus.COMPLETED;
        failureReason = null;
        processingSince = null;
        processedAt = now;
        updatedAt = now;
    }

    /** Indexing failed for good; a previous generation (if any) stays searchable. */
    public void failed(String reason, Instant now) {
        status = DocumentStatus.FAILED;
        failureReason = reason.length() > 500 ? reason.substring(0, 497) + "…" : reason;
        processingSince = null;
        updatedAt = now;
    }

    /** Processing was interrupted (a restart, an outage): it waits to be tried again. */
    public void retryLater(String reason, Instant now) {
        status = DocumentStatus.UPLOADED;
        failureReason = reason;
        processingSince = null;
        updatedAt = now;
    }

    /** Deleted: unsearchable at once; its chunks are removed from the index right after. */
    public void delete(Editor by, Instant now) {
        status = DocumentStatus.DELETED;
        indexedGeneration = null;
        chunkCount = 0;
        processingSince = null;
        deletedAt = now;
        updatedBy = by.id();
        updatedByName = by.name();
        updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Searches may return this document's chunks (of {@link #getIndexedGeneration()}). */
    public boolean isSearchable() {
        return deletedAt == null && indexedGeneration != null;
    }

    public Set<KnowledgeAudience> getAudiences() {
        Set<KnowledgeAudience> set = EnumSet.noneOf(KnowledgeAudience.class);
        Arrays.stream(audiences.split(",")).filter(s -> !s.isBlank())
                .forEach(s -> set.add(KnowledgeAudience.valueOf(s.strip())));
        return set;
    }

    private static String join(Set<KnowledgeAudience> audiences) {
        return audiences.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public KnowledgeCategory getCategory() {
        return category;
    }

    public String getFileName() {
        return fileName;
    }

    public DocumentFormat getFormat() {
        return format;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public int getGeneration() {
        return generation;
    }

    public Integer getIndexedGeneration() {
        return indexedGeneration;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
