package com.grocery.notification.repository;

import com.grocery.notification.dto.NotificationDtos.NotificationView;
import com.grocery.notification.entity.NotificationType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The inbox tables ({@code notification}, {@code notification_audience}, {@code notification_read}) in plain JDBC.
 *
 * <p>"The caller's notifications" are those with an audience row for one of the caller's audience keys
 * ({@code user:<sub>} and {@code role:<ROLE>} of every role in the token). Every query selects them through
 * {@code idx_notification_audience_key} with a semi-join, so a notification addressed to two of the caller's roles is
 * listed and counted once. Read state is a {@code (user_id, notification_id)} row per reader.
 */
@Repository
public class NotificationRepository {

    /** A notification to store (one row + one audience row per audience key). */
    public record NewNotification(UUID id, NotificationType type, String title, String body, String link,
                                  String dataJson, String sourceEventId, Instant createdAt,
                                  Collection<String> audiences) {
    }

    private static final String INSERT_SQL = """
            INSERT INTO notification (id, type, category, severity, title, body, link, data, source_event_id, created_at)
            VALUES (:id, :type, :category, :severity, :title, :body, :link, :data, :sourceEventId, :createdAt)
            """;
    private static final String INSERT_AUDIENCE_SQL = """
            INSERT INTO notification_audience (notification_id, audience_key) VALUES (:id, :audience)
            """;
    private static final String VIEW_SELECT = """
            SELECT n.id, n.type, n.category, n.severity, n.title, n.body, n.link, n.data, n.created_at,
                   r.notification_id IS NOT NULL AS is_read
            FROM notification n
            LEFT JOIN notification_read r ON r.user_id = :userId AND r.notification_id = n.id
            WHERE n.id IN (SELECT a.notification_id FROM notification_audience a WHERE a.audience_key IN (:audiences))
            """;
    private static final String COUNT_SELECT = """
            SELECT COUNT(*)
            FROM notification n
            LEFT JOIN notification_read r ON r.user_id = :userId AND r.notification_id = n.id
            WHERE n.id IN (SELECT a.notification_id FROM notification_audience a WHERE a.audience_key IN (:audiences))
            """;
    private static final String UNREAD = " AND r.notification_id IS NULL";
    private static final String VISIBLE_SQL = """
            SELECT COUNT(*) FROM notification_audience
            WHERE notification_id = :id AND audience_key IN (:audiences)
            """;
    private static final String MARK_READ_SQL = """
            INSERT IGNORE INTO notification_read (user_id, notification_id, read_at) VALUES (:userId, :id, :readAt)
            """;
    private static final String MARK_ALL_READ_SQL = """
            INSERT IGNORE INTO notification_read (user_id, notification_id, read_at)
            SELECT DISTINCT :userId, a.notification_id, :readAt
            FROM notification_audience a
            WHERE a.audience_key IN (:audiences)
              AND NOT EXISTS (SELECT 1 FROM notification_read r
                              WHERE r.user_id = :userId AND r.notification_id = a.notification_id)
            """;
    private static final String DELETE_OLDER_SQL = """
            DELETE FROM notification WHERE created_at < :cutoff ORDER BY created_at LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<NotificationView> viewMapper;

    public NotificationRepository(DataSource dataSource, JsonMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.viewMapper = (rs, rowNum) -> new NotificationView(
                UuidBytes.uuid(rs.getBytes("id")),
                rs.getString("type"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("link"),
                this.json.readTree(rs.getString("data")),
                rs.getBoolean("is_read"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /** Inserts the notification and its audience rows (inside the caller's transaction). */
    public void insert(NewNotification notification) {
        byte[] id = UuidBytes.bytes(notification.id());
        jdbc.sql(INSERT_SQL)
                .param("id", id)
                .param("type", notification.type().name())
                .param("category", notification.type().category().name())
                .param("severity", notification.type().severity().name())
                .param("title", notification.title())
                .param("body", notification.body())
                .param("link", notification.link())
                .param("data", notification.dataJson())
                .param("sourceEventId", notification.sourceEventId())
                .param("createdAt", utc(notification.createdAt()))
                .update();
        for (String audience : notification.audiences()) {
            jdbc.sql(INSERT_AUDIENCE_SQL).param("id", id).param("audience", audience).update();
        }
    }

    /** One page of the caller's notifications, newest first. */
    public List<NotificationView> page(UUID userId, Collection<String> audiences, boolean unreadOnly, int limit,
                                       long offset) {
        return jdbc.sql(VIEW_SELECT + (unreadOnly ? UNREAD : "") + " ORDER BY n.id DESC LIMIT :limit OFFSET :offset")
                .param("userId", UuidBytes.bytes(userId))
                .param("audiences", audiences)
                .param("limit", limit)
                .param("offset", offset)
                .query(viewMapper)
                .list();
    }

    public long count(UUID userId, Collection<String> audiences, boolean unreadOnly) {
        return jdbc.sql(COUNT_SELECT + (unreadOnly ? UNREAD : ""))
                .param("userId", UuidBytes.bytes(userId))
                .param("audiences", audiences)
                .query(Long.class)
                .single();
    }

    /** The caller's notifications with an id greater than {@code lastId}, oldest first (the SSE replay). */
    public List<NotificationView> newerThan(UUID userId, Collection<String> audiences, UUID lastId, int limit) {
        return jdbc.sql(VIEW_SELECT + " AND n.id > :lastId ORDER BY n.id ASC LIMIT :limit")
                .param("userId", UuidBytes.bytes(userId))
                .param("audiences", audiences)
                .param("lastId", UuidBytes.bytes(lastId))
                .param("limit", limit)
                .query(viewMapper)
                .list();
    }

    /** Whether the notification exists and is addressed to one of these audiences. */
    public boolean isVisible(UUID id, Collection<String> audiences) {
        return jdbc.sql(VISIBLE_SQL)
                .param("id", UuidBytes.bytes(id))
                .param("audiences", audiences)
                .query(Long.class)
                .single() > 0;
    }

    /** Marks one notification read for this user; a second call changes nothing. */
    public void markRead(UUID userId, UUID id, Instant readAt) {
        jdbc.sql(MARK_READ_SQL)
                .param("userId", UuidBytes.bytes(userId))
                .param("id", UuidBytes.bytes(id))
                .param("readAt", utc(readAt))
                .update();
    }

    /** Marks every unread notification of the caller read; returns how many were unread. */
    public int markAllRead(UUID userId, Collection<String> audiences, Instant readAt) {
        return jdbc.sql(MARK_ALL_READ_SQL)
                .param("userId", UuidBytes.bytes(userId))
                .param("audiences", audiences)
                .param("readAt", utc(readAt))
                .update();
    }

    /** Deletes up to {@code limit} notifications created before {@code cutoff} (audiences and reads cascade). */
    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbc.sql(DELETE_OLDER_SQL)
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .update();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
