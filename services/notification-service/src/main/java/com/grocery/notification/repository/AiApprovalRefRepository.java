package com.grocery.notification.repository;

import com.grocery.notification.entity.AiApprovalRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 11: the {@code ai_approval_ref} projection — who decides an assistant's approval request. Written by
 * ApprovalRequested (a redelivery or replay overwrites it with the same values), read by the request's later events.
 * Role names are stored comma-separated; the caller passes well-formed names only (see
 * {@link com.grocery.notification.dispatch.Audiences#wellFormedRoles}).
 */
@Repository
public class AiApprovalRefRepository {

    /** Width of {@code approver_roles}: roles that would not fit are left out (never happens with real role lists). */
    static final int ROLES_WIDTH = 1000;

    private static final String UPSERT_SQL = """
            INSERT INTO ai_approval_ref (approval_id, approver_user_id, approver_roles, updated_at)
            VALUES (:approvalId, :approverUserId, :approverRoles, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE approver_user_id = new.approver_user_id,
                                    approver_roles = new.approver_roles,
                                    updated_at = new.updated_at
            """;
    private static final String FIND_SQL = """
            SELECT approver_user_id, approver_roles FROM ai_approval_ref WHERE approval_id = :approvalId
            """;
    private static final String DELETE_OLDER_SQL = """
            DELETE FROM ai_approval_ref WHERE updated_at < :cutoff ORDER BY updated_at LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public AiApprovalRefRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void upsert(AiApprovalRef approval, Instant now) {
        jdbc.sql(UPSERT_SQL)
                .param("approvalId", UuidBytes.bytes(approval.approvalId()))
                .param("approverUserId", UuidBytes.bytes(approval.approverUserId()))
                .param("approverRoles", join(approval.approverRoles()))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public Optional<AiApprovalRef> find(UUID approvalId) {
        return jdbc.sql(FIND_SQL)
                .param("approvalId", UuidBytes.bytes(approvalId))
                .query((rs, rowNum) -> new AiApprovalRef(approvalId, UuidBytes.uuid(rs.getBytes("approver_user_id")),
                        split(rs.getString("approver_roles"))))
                .optional();
    }

    /** @return how many rows were deleted (at most {@code limit}) */
    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbc.sql(DELETE_OLDER_SQL)
                .param("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }

    static String join(List<String> roles) {
        StringBuilder joined = new StringBuilder();
        for (String role : roles) {
            int length = joined.length() + (joined.isEmpty() ? 0 : 1) + role.length();
            if (length > ROLES_WIDTH) {
                break;
            }
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(role);
        }
        return joined.toString();
    }

    static List<String> split(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return List.of(roles.split(","));
    }
}
