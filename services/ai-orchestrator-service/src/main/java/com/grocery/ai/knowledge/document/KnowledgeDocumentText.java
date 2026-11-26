package com.grocery.ai.knowledge.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The text extracted from a document's latest file (table {@code knowledge_document_text}): what the ingestion
 * worker chunks, and what a reindex uses, so the original file is never kept.
 */
@Entity
@Table(name = "knowledge_document_text")
public class KnowledgeDocumentText {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    protected KnowledgeDocumentText() {
    }

    public KnowledgeDocumentText(UUID documentId, int contentVersion, String content) {
        this.documentId = documentId;
        this.contentVersion = contentVersion;
        this.content = content;
    }

    public void replace(int newContentVersion, String newContent) {
        contentVersion = newContentVersion;
        content = newContent;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public String getContent() {
        return content;
    }
}
