package com.grocery.reporting.audit;

import com.grocery.reporting.audit.AuditEntry.KnownUser;
import com.grocery.reporting.facts.Sql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code audit_entry} and {@code audit_actor} (Flyway V4): writes from events, the admin search and the purge. */
@Repository
public class AuditRepository implements AuditMapper.Labels {

    /** A row as the search returns it, with what {@code audit_actor} knows about the actor. */
    public record Row(UUID id, Instant occurredAt, String category, String action, String source, UUID actorId,
                      String actorRole, String actorName, String actorEmail, String targetType, String targetId,
                      String targetLabel, String summary, String detail, String ipAddress, String userAgent) {
    }

    /**
     * The search's filters (null = any). {@code text} matches the summary, detail, target, action, the actor's name or
     * e-mail and the IP address (case-insensitive, anywhere in the text).
     */
    public record Filter(Instant from, Instant to, List<String> categories, String action, UUID actorId,
                         String targetType, String targetId, String text) {
    }

    private static final String SELECT = """
            SELECT e.id, e.occurred_at, e.category, e.action, e.source, e.actor_id, e.actor_role,
                   COALESCE(e.actor_name, a.name) AS actor_name, a.email AS actor_email, e.target_type, e.target_id,
                   e.target_label, e.summary, e.detail, e.ip_address, e.user_agent
            FROM audit_entry e LEFT JOIN audit_actor a ON a.user_id = e.actor_id""";

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the entry; false when its event was written before (the id is the event id). */
    public boolean insert(AuditEntry e, Instant now) {
        return jdbc.sql("""
                        INSERT IGNORE INTO audit_entry (id, occurred_at, category, action, source, actor_id, actor_role,
                            actor_name, target_type, target_id, target_label, summary, detail, ip_address, user_agent,
                            event_type, created_at)
                        VALUES (:id, :occurredAt, :category, :action, :source, :actorId, :actorRole, :actorName,
                            :targetType, :targetId, :targetLabel, :summary, :detail, :ip, :agent, :eventType, :now)""")
                .param("id", Sql.bytes(e.id()))
                .param("occurredAt", Sql.utc(e.occurredAt()))
                .param("category", e.category())
                .param("action", e.action())
                .param("source", e.source())
                .param("actorId", Sql.bytes(e.actorId()))
                .param("actorRole", e.actorRole())
                .param("actorName", e.actorName())
                .param("targetType", e.targetType())
                .param("targetId", e.targetId())
                .param("targetLabel", e.targetLabel())
                .param("summary", e.summary())
                .param("detail", e.detail())
                .param("ip", e.ipAddress())
                .param("agent", e.userAgent())
                .param("eventType", e.eventType())
                .param("now", Sql.utc(now))
                .update() == 1;
    }

    /** What an event told about a user; the newest event wins, and a value it does not carry is kept. */
    public void remember(KnownUser user) {
        jdbc.sql("""
                        INSERT INTO audit_actor (user_id, email, name, seen_at) VALUES (:id, :email, :name, :seenAt)
                        AS new
                        ON DUPLICATE KEY UPDATE
                            email = IF(new.seen_at >= audit_actor.seen_at AND new.email IS NOT NULL, new.email,
                                       audit_actor.email),
                            name = IF(new.seen_at >= audit_actor.seen_at AND new.name IS NOT NULL, new.name,
                                      audit_actor.name),
                            seen_at = GREATEST(audit_actor.seen_at, new.seen_at)""")
                .param("id", Sql.bytes(user.userId()))
                .param("email", user.email())
                .param("name", user.name())
                .param("seenAt", Sql.utc(user.seenAt()))
                .update();
    }

    @Override
    public String orderNumber(UUID orderId) {
        if (orderId == null) {
            return null;
        }
        return jdbc.sql("SELECT order_number FROM order_fact WHERE order_id = :id")
                .param("id", Sql.bytes(orderId)).query(String.class).optional().orElse(null);
    }

    @Override
    public String itemName(UUID itemId) {
        if (itemId == null) {
            return null;
        }
        return jdbc.sql("SELECT name FROM item_dim WHERE item_id = :id")
                .param("id", Sql.bytes(itemId)).query(String.class).optional().orElse(null);
    }

    public long count(Filter filter) {
        Where where = where(filter);
        return jdbc.sql("SELECT COUNT(*) FROM audit_entry e LEFT JOIN audit_actor a ON a.user_id = e.actor_id"
                        + where.sql())
                .params(where.params()).query(Long.class).single();
    }

    /** One page, newest first (ties: the newest event id first). */
    public List<Row> page(Filter filter, int limit, long offset) {
        Where where = where(filter);
        Map<String, Object> params = new LinkedHashMap<>(where.params());
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(SELECT + where.sql() + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .params(params).query(AuditRepository::row).list();
    }

    /** The rows after {@code (beforeTime, beforeId)} in the same order (keyset), for exports; null = from the top. */
    public List<Row> batch(Filter filter, Instant beforeTime, UUID beforeId, int limit) {
        Where where = where(filter);
        Map<String, Object> params = new LinkedHashMap<>(where.params());
        String sql = SELECT + where.sql();
        if (beforeTime != null) {
            sql += (where.sql().isEmpty() ? " WHERE " : " AND ")
                    + "(e.occurred_at < :beforeTime OR (e.occurred_at = :beforeTime AND e.id < :beforeId))";
            params.put("beforeTime", Sql.utc(beforeTime));
            params.put("beforeId", Sql.bytes(beforeId));
        }
        params.put("limit", limit);
        return jdbc.sql(sql + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit")
                .params(params).query(AuditRepository::row).list();
    }

    /** Deletes up to {@code limit} entries of {@code categories} (null = every category) older than {@code before}. */
    public int purge(Instant before, List<String> categories, boolean except, int limit) {
        String sql = "DELETE FROM audit_entry WHERE occurred_at < :before";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("before", Sql.utc(before));
        if (categories != null && !categories.isEmpty()) {
            sql += " AND category " + (except ? "NOT IN" : "IN") + " (:categories)";
            params.put("categories", categories);
        }
        params.put("limit", limit);
        return jdbc.sql(sql + " ORDER BY occurred_at LIMIT :limit").params(params).update();
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(Filter f) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.from() != null) {
            conditions.add("e.occurred_at >= :from");
            params.put("from", Sql.utc(f.from()));
        }
        if (f.to() != null) {
            conditions.add("e.occurred_at < :to");
            params.put("to", Sql.utc(f.to()));
        }
        if (f.categories() != null && !f.categories().isEmpty()) {
            conditions.add("e.category IN (:categories)");
            params.put("categories", f.categories());
        }
        if (f.action() != null) {
            conditions.add("e.action = :action");
            params.put("action", f.action());
        }
        if (f.actorId() != null) {
            conditions.add("e.actor_id = :actorId");
            params.put("actorId", Sql.bytes(f.actorId()));
        }
        if (f.targetType() != null) {
            conditions.add("e.target_type = :targetType");
            params.put("targetType", f.targetType());
        }
        if (f.targetId() != null) {
            conditions.add("e.target_id = :targetId");
            params.put("targetId", f.targetId());
        }
        if (f.text() != null) {
            conditions.add("""
                    (e.summary LIKE :text OR e.detail LIKE :text OR e.target_label LIKE :text OR e.target_id LIKE :text
                     OR e.action LIKE :text OR e.actor_name LIKE :text OR a.name LIKE :text OR a.email LIKE :text
                     OR e.ip_address LIKE :text)""");
            params.put("text", "%" + f.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(Sql.uuid(rs, "id"), Sql.instant(rs, "occurred_at"), rs.getString("category"),
                rs.getString("action"), rs.getString("source"), Sql.uuid(rs, "actor_id"), rs.getString("actor_role"),
                rs.getString("actor_name"), rs.getString("actor_email"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("target_label"), rs.getString("summary"),
                rs.getString("detail"), rs.getString("ip_address"), rs.getString("user_agent"));
    }
}
