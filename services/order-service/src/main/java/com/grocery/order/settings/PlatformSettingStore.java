package com.grocery.order.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.event.OrderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin overrides of this service's business settings ({@code platform_setting}, Flyway V10;
 * docs/api/platform-settings.md). A key is {@code <area>.<field>}. A row with a value overrides the field's default
 * from application.yml / env; no row, or a NULL value (the admin reset it), means the default.
 *
 * <p><b>Reads.</b> Business code asks for the effective value each time it uses one, so reads come from an in-memory
 * snapshot of the table (a few rows), reloaded once it is older than {@code grocery.settings.cache-ttl} (15 s). Every
 * replica picks up a change within that time; a save refreshes this instance at once.
 *
 * <p><b>Saves</b> are optimistic. The area's version is the sum of its keys' versions (each goes up by one per change),
 * and a save made from another version is refused with 409 SETTINGS_CHANGED. Each changed key is also written with a
 * version check of its own, so two saves overlapping in time can never both change the same key. A save is one
 * transaction, with one audit row ({@code platform_setting_change}) per changed key and, with each row, a
 * {@code PlatformSettingChanged} event on this service's topic (through the outbox, key = the setting key) for
 * the central audit trail.
 */
@Component
public class PlatformSettingStore {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingStore.class);

    /** Published for every changed key of a save; the key is the setting key. */
    public static final String SETTING_CHANGED = "PlatformSettingChanged";
    public static final String AGGREGATE = "PlatformSetting";
    private static final String TOPIC = OrderEvents.TOPIC;

    /** An area as stored: the overridden fields (field → stored text), its version and its last change. */
    public record AreaState(Map<String, String> overrides, long version, Instant updatedAt, String updatedBy) {

        /**
         * The field's override read with {@code parser}, else {@code fallback} (the config value). A stored value the
         * parser refuses (edited by hand in the database) counts as no override.
         */
        public <T> T value(String field, Function<String, T> parser, T fallback) {
            String text = overrides.get(field);
            if (text == null) {
                return fallback;
            }
            try {
                return parser.apply(text);
            } catch (RuntimeException unreadable) {
                log.warn("Platform setting {} has an unreadable value '{}', using the default: {}", field, text,
                        unreadable.toString());
                return fallback;
            }
        }
    }

    /** One audit row: a field's stored text before and after a save (null = the default) and who saved it. */
    public record Change(String field, String oldValue, String newValue, Instant changedAt, UUID changedBy,
                         String changedByName) {
    }

    /**
     * The {@code PlatformSettingChanged} payload (grocery-event-schemas 1.8.0): one changed key of a save, the
     * values as stored (null = the default). Every key of one save has the same {@code changedAt}.
     */
    public record PlatformSettingChanged(String settingKey, String area, String field, String oldValue,
                                         String newValue, UUID changedBy, String changedByName,
                                         Instant changedAt) {
    }

    /** One stored key; {@code value} null = reset to the default. */
    private record Row(String key, String value, String updatedBy, Instant updatedAt, long version) {
    }

    private record Snapshot(Map<String, Row> rows, long loadedAtNanos) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final OutboxPublisher outbox;
    private final Clock clock;
    private final Duration cacheTtl;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /** Goes up with every save of this instance, so a read that started before it is not kept. */
    private final AtomicLong generation = new AtomicLong();
    private volatile Snapshot snapshot;

    public PlatformSettingStore(DataSource dataSource, PlatformTransactionManager transactionManager,
                                OutboxPublisher outbox, Clock clock,
                                @Value("${grocery.settings.cache-ttl:15s}") Duration cacheTtl) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    /** The area as this instance currently knows it (at most {@code cache-ttl} old). */
    public AreaState area(String area) {
        return state(area, snapshot().rows().values());
    }

    /**
     * Saves every field of {@code area} at once: field → text, null = back to the default. Fields whose value does
     * not change are left alone (no new version, no audit row).
     *
     * @throws SettingsException 409 SETTINGS_CHANGED when the area is no longer at {@code expectedVersion}
     */
    public void save(String area, Map<String, String> values, long expectedVersion, CurrentUser user) {
        transaction.executeWithoutResult(status -> write(area, values, expectedVersion, user));
        invalidate();   // after the commit: this instance reads the new values at once
    }

    /** Forgets the snapshot: the next read loads the table again. */
    public void invalidate() {
        generation.incrementAndGet();
        snapshot = null;
    }

    /**
     * The audit trail of {@code area}, newest first, values as stored. Read from the database on every call: only the
     * admin page asks, never business code. The rows of one save share their {@code changed_at}; the id (UUIDv7,
     * time-ordered) breaks the tie, so the order is the order of writing, reversed, and stable from page to page.
     */
    public PageResponse<Change> history(String area, Pageable pageable) {
        String prefix = area + ".";
        long total = jdbc.sql("SELECT COUNT(*) FROM platform_setting_change WHERE setting_key LIKE :keys")
                .param("keys", prefix + "%")
                .query(Long.class).single();
        List<Change> changes = jdbc.sql("""
                        SELECT setting_key, old_value, new_value, BIN_TO_UUID(changed_by) AS changed_by,
                               changed_by_name, changed_at
                        FROM platform_setting_change
                        WHERE setting_key LIKE :keys
                        ORDER BY changed_at DESC, id DESC
                        LIMIT :limit OFFSET :offset""")
                .param("keys", prefix + "%")
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query((rs, rowNum) -> new Change(rs.getString("setting_key").substring(prefix.length()),
                        rs.getString("old_value"), rs.getString("new_value"),
                        rs.getObject("changed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        UUID.fromString(rs.getString("changed_by")), rs.getString("changed_by_name")))
                .list();
        return Pages.toResponse(new PageImpl<>(changes, pageable, total), Function.identity());
    }

    private void write(String area, Map<String, String> values, long expectedVersion, CurrentUser user) {
        Map<String, Row> stored = jdbc.sql(SELECT + " WHERE setting_key LIKE :prefix")
                .param("prefix", area + ".%")
                .query(PlatformSettingStore::row).list().stream()
                .collect(Collectors.toMap(Row::key, Function.identity()));
        if (state(area, stored.values()).version() != expectedVersion) {
            throw SettingsException.changed();
        }
        Instant now = clock.instant();
        String name = user.name() != null && !user.name().isBlank() ? user.name() : user.email();
        values.forEach((field, value) -> {
            String key = area + "." + field;
            Row row = stored.get(key);
            String oldValue = row == null ? null : row.value();
            if (Objects.equals(oldValue, value)) {
                return;
            }
            if (row == null) {
                insert(key, value, user, name, now);
            } else {
                update(key, value, user, name, now, row.version());
            }
            jdbc.sql("""
                            INSERT INTO platform_setting_change
                                (id, setting_key, old_value, new_value, changed_by, changed_by_name, changed_at)
                            VALUES (UUID_TO_BIN(:id), :key, :oldValue, :newValue, UUID_TO_BIN(:by), :name, :at)""")
                    .param("id", Ids.newId().toString())
                    .param("key", key)
                    .param("oldValue", oldValue)
                    .param("newValue", value)
                    .param("by", user.userId().toString())
                    .param("name", name)
                    .param("at", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                    .update();
            outbox.publish(OutboxEvent.of(TOPIC, AGGREGATE, key, SETTING_CHANGED,
                    new PlatformSettingChanged(key, area, field, oldValue, value, user.userId(), name, now)));
        });
    }

    private void insert(String key, String value, CurrentUser user, String name, Instant now) {
        try {
            jdbc.sql("""
                            INSERT INTO platform_setting
                                (setting_key, setting_value, updated_by, updated_by_name, updated_at, version)
                            VALUES (:key, :value, UUID_TO_BIN(:by), :name, :at, 1)""")
                    .param("key", key)
                    .param("value", value)
                    .param("by", user.userId().toString())
                    .param("name", name)
                    .param("at", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            throw SettingsException.changed();      // another save created the key a moment ago
        }
    }

    private void update(String key, String value, CurrentUser user, String name, Instant now, long version) {
        int updated = jdbc.sql("""
                        UPDATE platform_setting
                        SET setting_value = :value, updated_by = UUID_TO_BIN(:by), updated_by_name = :name,
                            updated_at = :at, version = version + 1
                        WHERE setting_key = :key AND version = :version""")
                .param("value", value)
                .param("by", user.userId().toString())
                .param("name", name)
                .param("at", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("key", key)
                .param("version", version)
                .update();
        if (updated == 0) {
            throw SettingsException.changed();      // another save changed the key a moment ago
        }
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (isFresh(current)) {
            return current;
        }
        // One thread reads the table again while the others keep the values they know. Without any (the first read,
        // or right after a save) each caller reads for itself: a few rows, no lock held while waiting for the database.
        if (current != null && !refreshing.compareAndSet(false, true)) {
            return current;
        }
        try {
            return load(current);
        } finally {
            if (current != null) {
                refreshing.set(false);
            }
        }
    }

    private Snapshot load(Snapshot current) {
        long generationAtStart = generation.get();
        Map<String, Row> rows;
        try {
            rows = jdbc.sql(SELECT).query(PlatformSettingStore::row).list().stream()
                    .collect(Collectors.toMap(Row::key, Function.identity()));
        } catch (DataAccessException e) {
            // Keep what was read last (the defaults before the first read) instead of failing the business call that
            // asked; the next attempt is after another cache-ttl.
            log.warn("Could not read the platform settings, using the last known ones: {}", e.toString());
            rows = current == null ? Map.of() : current.rows();
        }
        Snapshot loaded = new Snapshot(rows, System.nanoTime());
        // A save in the meantime may be newer than what was read: use it for this call, but do not keep it
        if (generation.get() == generationAtStart) {
            snapshot = loaded;
        }
        return loaded;
    }

    private boolean isFresh(Snapshot candidate) {
        return candidate != null && System.nanoTime() - candidate.loadedAtNanos() < cacheTtl.toNanos();
    }

    private static AreaState state(String area, Collection<Row> rows) {
        String prefix = area + ".";
        List<Row> ofArea = rows.stream().filter(row -> row.key().startsWith(prefix)).toList();
        Map<String, String> overrides = new LinkedHashMap<>();
        ofArea.stream().filter(row -> row.value() != null)
                .forEach(row -> overrides.put(row.key().substring(prefix.length()), row.value()));
        long version = ofArea.stream().mapToLong(Row::version).sum();
        Row latest = ofArea.stream().max(Comparator.comparing(Row::updatedAt)).orElse(null);
        return new AreaState(Map.copyOf(overrides), version, latest == null ? null : latest.updatedAt(),
                latest == null ? null : latest.updatedBy());
    }

    private static final String SELECT =
            "SELECT setting_key, setting_value, updated_by_name, updated_at, version FROM platform_setting";

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("setting_key"), rs.getString("setting_value"), rs.getString("updated_by_name"),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getLong("version"));
    }
}
