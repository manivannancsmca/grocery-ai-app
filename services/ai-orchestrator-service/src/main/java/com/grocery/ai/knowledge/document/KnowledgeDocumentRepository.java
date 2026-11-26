package com.grocery.ai.knowledge.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID>,
        JpaSpecificationExecutor<KnowledgeDocument> {

    /** How many documents have each status: rows of [status, count]. */
    @Query("select d.status, count(d) from KnowledgeDocument d group by d.status")
    List<Object[]> countByStatus();

    /** {@link #countByStatus()} as a map: status name → documents (statuses without documents are absent). */
    default Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new TreeMap<>();
        for (Object[] row : countByStatus()) {
            counts.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Duplicate detection: a document (not deleted) whose file had exactly these bytes. */
    Optional<KnowledgeDocument> findFirstByContentSha256AndDeletedAtIsNullOrderByCreatedAt(String contentSha256);

    /**
     * The ingestion worker's claim: the oldest UPLOADED document, locked with {@code FOR UPDATE SKIP LOCKED} so two
     * replicas never index the same document.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM knowledge_document
            WHERE status = 'UPLOADED'
            ORDER BY updated_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimNextUploaded();

    /** Documents left PROCESSING since before {@code before} (the replica stopped mid-way). */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM knowledge_document
            WHERE status = 'PROCESSING' AND processing_since <= :before
            ORDER BY processing_since
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimInterrupted(@Param("before") Instant before, @Param("limit") int limit);

    /** Deleted documents whose chunks are still in the index (their removal failed: tried again). */
    @Query(value = """
            SELECT BIN_TO_UUID(d.id) FROM knowledge_document d
            WHERE d.deleted_at IS NOT NULL
              AND EXISTS (SELECT 1 FROM knowledge_chunk c WHERE c.document_id = d.id)
            LIMIT :limit""", nativeQuery = true)
    List<String> deletedWithChunks(@Param("limit") int limit);
}
