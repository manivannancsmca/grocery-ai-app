package com.grocery.notification.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Phase 12 §5: {@code email_preference} — one row per user who changed a preference; no row = every category on. */
@Repository
public class EmailPreferenceRepository {

    /** The three categories of {@code GET/PUT /api/notifications/preferences}. */
    public record EmailPreferences(boolean orders, boolean account, boolean approvals) {

        public static final EmailPreferences DEFAULT = new EmailPreferences(true, true, true);
    }

    private static final String UPSERT_SQL = """
            INSERT INTO email_preference (user_id, orders, account, approvals, updated_at)
            VALUES (:userId, :orders, :account, :approvals, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE orders = new.orders, account = new.account, approvals = new.approvals,
                                    updated_at = new.updated_at
            """;

    private final JdbcClient jdbc;

    public EmailPreferenceRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<EmailPreferences> find(UUID userId) {
        return jdbc.sql("SELECT orders, account, approvals FROM email_preference WHERE user_id = :userId")
                .param("userId", UuidBytes.bytes(userId))
                .query((rs, rowNum) -> new EmailPreferences(rs.getBoolean("orders"), rs.getBoolean("account"),
                        rs.getBoolean("approvals")))
                .optional();
    }

    public void save(UUID userId, EmailPreferences preferences, Instant now) {
        jdbc.sql(UPSERT_SQL)
                .param("userId", UuidBytes.bytes(userId))
                .param("orders", preferences.orders())
                .param("account", preferences.account())
                .param("approvals", preferences.approvals())
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
