package com.grocery.notification.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 12: the {@code item_name} projection — productId / menuItemId → name, from the order lines of OrderCreated and
 * OrderConfirmed, so review notifications can name the product or dish (review events carry only its id). The latest
 * name wins; a blank name is never stored.
 */
@Repository
public class ItemNameRepository {

    /** {@code item_name.name} is VARCHAR(200). */
    static final int NAME_WIDTH = 200;

    private static final String UPSERT_SQL = """
            INSERT INTO item_name (item_id, item_type, name, updated_at)
            VALUES (:itemId, :itemType, :name, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE item_type = COALESCE(new.item_type, item_name.item_type),
                                    name = new.name,
                                    updated_at = new.updated_at
            """;

    private final JdbcClient jdbc;

    public ItemNameRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void upsert(UUID itemId, String itemType, String name, Instant now) {
        if (itemId == null || name == null || name.isBlank()) {
            return;
        }
        String stripped = name.strip();
        jdbc.sql(UPSERT_SQL)
                .param("itemId", UuidBytes.bytes(itemId))
                .param("itemType", itemType == null || itemType.isBlank() || itemType.length() > 12 ? null : itemType)
                .param("name", stripped.length() > NAME_WIDTH ? stripped.substring(0, NAME_WIDTH) : stripped)
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public Optional<String> find(UUID itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT name FROM item_name WHERE item_id = :itemId")
                .param("itemId", UuidBytes.bytes(itemId))
                .query(String.class)
                .optional();
    }
}
