package com.grocery.ai.knowledge.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The bookkeeping of one chunk in the knowledge index (table {@code knowledge_chunk}); {@code id} is its
 * Elasticsearch document id. The chunk's text and vector are only in the index.
 */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private int generation;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    @Column(nullable = false, updatable = false)
    private int characters;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(UUID id, UUID documentId, int generation, int chunkIndex, int characters, Instant now) {
        this.id = id;
        this.documentId = documentId;
        this.generation = generation;
        this.chunkIndex = chunkIndex;
        this.characters = characters;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getGeneration() {
        return generation;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getCharacters() {
        return characters;
    }
}
