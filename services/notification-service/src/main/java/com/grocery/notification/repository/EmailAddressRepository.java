package com.grocery.notification.repository;

import com.grocery.notification.email.EmailCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 12 §5: the email address projection ({@code email_address}, {@code email_address_role}) and who receives an
 * email: active users, addressed directly or through one of their roles, whose preference for the category is on (no
 * preference row = on).
 */
@Repository
public class EmailAddressRepository {

    /** Someone an email goes to. */
    public record Recipient(UUID userId, String email, String fullName) {
    }

    static final int EMAIL_WIDTH = 254;
    static final int NAME_WIDTH = 150;
    static final int ROLE_WIDTH = 60;

    /** UserRegistered: the address and the name; a known row keeps its active flag. */
    private static final String UPSERT_SQL = """
            INSERT INTO email_address (user_id, email, full_name, active, updated_at)
            VALUES (:userId, :email, :fullName, TRUE, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE email = new.email,
                                    full_name = COALESCE(new.full_name, email_address.full_name),
                                    updated_at = new.updated_at
            """;
    private static final String ADD_ROLE_SQL = """
            INSERT IGNORE INTO email_address_role (user_id, role) VALUES (:userId, :role)
            """;
    private static final String SET_ACTIVE_SQL = """
            UPDATE email_address SET active = :active, updated_at = :updatedAt WHERE user_id = :userId
            """;
    private static final String RECIPIENTS_SQL = """
            SELECT a.user_id, a.email, a.full_name
            FROM email_address a
            LEFT JOIN email_preference p ON p.user_id = a.user_id
            WHERE a.active = TRUE AND (p.user_id IS NULL OR p.%s = TRUE) AND (%s)
            ORDER BY a.user_id
            """;

    private final JdbcClient jdbc;

    public EmailAddressRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** UserRegistered (also an old one): the address, the name and the role it registered with. */
    public void registered(UUID userId, String email, String fullName, String role, Instant now) {
        upsert(userId, email, fullName, now);
        if (role != null && !role.isBlank() && role.length() <= ROLE_WIDTH) {
            jdbc.sql(ADD_ROLE_SQL).param("userId", UuidBytes.bytes(userId)).param("role", role.strip()).update();
        }
    }

    /**
     * What the user's validated access token says (identity-service's own claims): address, name and the CURRENT
     * roles, which replace the known ones. Never changes the active flag.
     */
    public void learned(UUID userId, String email, String name, Collection<String> roles, Instant now) {
        upsert(userId, email, name, now);
        List<String> current = roles.stream().filter(r -> r != null && !r.isBlank() && r.length() <= ROLE_WIDTH)
                .distinct().toList();
        if (current.isEmpty()) {
            jdbc.sql("DELETE FROM email_address_role WHERE user_id = :userId")
                    .param("userId", UuidBytes.bytes(userId)).update();
        } else {
            jdbc.sql("DELETE FROM email_address_role WHERE user_id = :userId AND role NOT IN (:roles)")
                    .param("userId", UuidBytes.bytes(userId)).param("roles", current).update();
            for (String role : current) {
                jdbc.sql(ADD_ROLE_SQL).param("userId", UuidBytes.bytes(userId)).param("role", role).update();
            }
        }
    }

    /**
     * customer.events CustomerProfileUpdated: the name the customer now has on their profile. An unknown user changes
     * nothing (the event has no address; the user becomes known from UserRegistered or their token).
     */
    public void renamed(UUID userId, String fullName, Instant now) {
        String name = cut(fullName, NAME_WIDTH);
        if (name == null) {
            return;
        }
        jdbc.sql("UPDATE email_address SET full_name = :fullName, updated_at = :updatedAt WHERE user_id = :userId")
                .param("fullName", name)
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("userId", UuidBytes.bytes(userId))
                .update();
    }

    /** UserSuspended (false) / UserReactivated (true); an unknown user changes nothing. */
    public void setActive(UUID userId, boolean active, Instant now) {
        jdbc.sql(SET_ACTIVE_SQL)
                .param("active", active)
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("userId", UuidBytes.bytes(userId))
                .update();
    }

    /**
     * The active users among {@code users} plus every active user holding one of {@code roles}, each once, whose
     * preference for {@code category} is on.
     */
    public List<Recipient> recipients(Collection<UUID> users, Collection<String> roles, EmailCategory category) {
        List<String> conditions = new ArrayList<>();
        if (!users.isEmpty()) {
            conditions.add("a.user_id IN (:users)");
        }
        if (!roles.isEmpty()) {
            conditions.add("a.user_id IN (SELECT r.user_id FROM email_address_role r WHERE r.role IN (:roles))");
        }
        if (conditions.isEmpty()) {
            return List.of();
        }
        // the column comes from the enum, never from input
        JdbcClient.StatementSpec statement = jdbc.sql(RECIPIENTS_SQL.formatted(category.column(),
                String.join(" OR ", conditions)));
        if (!users.isEmpty()) {
            statement = statement.param("users", users.stream().map(UuidBytes::bytes).toList());
        }
        if (!roles.isEmpty()) {
            statement = statement.param("roles", List.copyOf(roles));
        }
        return statement.query((rs, rowNum) -> new Recipient(UuidBytes.uuid(rs.getBytes("user_id")),
                rs.getString("email"), rs.getString("full_name"))).list();
    }

    /** The known row of a user ({@code email}, {@code full_name}, {@code active}), if any. */
    public Optional<Map<String, Object>> row(UUID userId) {
        return jdbc.sql("SELECT email, full_name, active FROM email_address WHERE user_id = :userId")
                .param("userId", UuidBytes.bytes(userId))
                .query().listOfRows().stream().findFirst();
    }

    /** The roles known for a user, sorted. */
    public List<String> roles(UUID userId) {
        return jdbc.sql("SELECT role FROM email_address_role WHERE user_id = :userId ORDER BY role")
                .param("userId", UuidBytes.bytes(userId))
                .query(String.class).list();
    }

    private void upsert(UUID userId, String email, String fullName, Instant now) {
        jdbc.sql(UPSERT_SQL)
                .param("userId", UuidBytes.bytes(userId))
                .param("email", email.strip())
                .param("fullName", cut(fullName, NAME_WIDTH))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** A usable address: one {@code @}, something on both sides, no whitespace or angle brackets, ≤ 254. */
    public static boolean usable(String email) {
        if (email == null) {
            return false;
        }
        String value = email.strip();
        int at = value.indexOf('@');
        return value.length() <= EMAIL_WIDTH && at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>' || c == ','
                || c == '"' || Character.isISOControl(c));
    }

    private static String cut(String text, int width) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        return value.length() > width ? value.substring(0, width) : value;
    }
}
