package com.grocery.ai.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<ApprovalRequest, UUID>,
        JpaSpecificationExecutor<ApprovalRequest> {

    /** Duplicate detection: a pending request of the same requester with the same action and body hash. */
    Optional<ApprovalRequest> findFirstByRequestedByAndActionAndBodySha256AndStatus(
            UUID requestedBy, ApprovalAction action, String bodySha256, ApprovalStatus status);

    List<ApprovalRequest> findByMessageIdInOrderByCreatedAt(List<UUID> messageIds);

    /**
     * The expiry poller's claim: pending requests past {@code expires_at}, oldest first, locked with
     * {@code FOR UPDATE SKIP LOCKED} so replicas (and a decision in flight) never take the same row.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM approval_request
            WHERE status = 'PENDING_APPROVAL' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimOverdue(@Param("now") Instant now, @Param("limit") int limit);

    /** Recovery's claim: requests left EXECUTING since before {@code before} (a crash mid-execution). */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM approval_request
            WHERE status = 'EXECUTING' AND executing_since <= :before
            ORDER BY executing_since
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimInterrupted(@Param("before") Instant before, @Param("limit") int limit);
}
