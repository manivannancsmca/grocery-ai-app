package com.grocery.ai.knowledge.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    long countByDocumentIdAndGeneration(UUID documentId, int generation);

    @Modifying
    @Query("delete from KnowledgeChunk c where c.documentId = :documentId and c.generation <> :keep")
    int deleteOtherGenerations(@Param("documentId") UUID documentId, @Param("keep") int keep);

    @Modifying
    @Query("delete from KnowledgeChunk c where c.documentId = :documentId")
    int deleteByDocument(@Param("documentId") UUID documentId);
}
