package com.grocery.notification.repository;

import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code store_owner} projection. Written only by the vendor / restaurant event handlers; events of one store are
 * keyed by its id, so they arrive in order on one partition and the last one wins. A missing name never erases a
 * known one.
 */
@Repository
public class StoreOwnerRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO store_owner (store_type, store_id, owner_user_id, store_name, updated_at)
            VALUES (:storeType, :storeId, :ownerUserId, :storeName, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE owner_user_id = new.owner_user_id,
                                    store_name = COALESCE(new.store_name, store_owner.store_name),
                                    updated_at = new.updated_at
            """;
    /** Fills a missing row only (a store order event's ownerUserId); a known row is never changed by it. */
    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO store_owner (store_type, store_id, owner_user_id, store_name, updated_at)
            VALUES (:storeType, :storeId, :ownerUserId, NULL, :updatedAt) AS new
            ON DUPLICATE KEY UPDATE store_owner.store_id = store_owner.store_id
            """;
    private static final String FIND_SQL = """
            SELECT owner_user_id, store_name FROM store_owner WHERE store_type = :storeType AND store_id = :storeId
            """;

    private final JdbcClient jdbc;

    public StoreOwnerRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void upsert(StoreOwner owner, Instant now) {
        jdbc.sql(UPSERT_SQL)
                .param("storeType", owner.storeType().name())
                .param("storeId", UuidBytes.bytes(owner.storeId()))
                .param("ownerUserId", UuidBytes.bytes(owner.ownerUserId()))
                .param("storeName", blankToNull(owner.storeName()))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * A store order event (VendorOrderReceived / RestaurantOrderReceived) names the owner: remembered only when the
     * store's lifecycle events were not seen yet (they stay the source of truth and keep the name).
     */
    public void insertIfAbsent(StoreType storeType, UUID storeId, UUID ownerUserId, Instant now) {
        jdbc.sql(INSERT_IF_ABSENT_SQL)
                .param("storeType", storeType.name())
                .param("storeId", UuidBytes.bytes(storeId))
                .param("ownerUserId", UuidBytes.bytes(ownerUserId))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public Optional<StoreOwner> find(StoreType storeType, UUID storeId) {
        if (storeType == null || storeId == null) {
            return Optional.empty();
        }
        return jdbc.sql(FIND_SQL)
                .param("storeType", storeType.name())
                .param("storeId", UuidBytes.bytes(storeId))
                .query((rs, rowNum) -> new StoreOwner(storeType, storeId, UuidBytes.uuid(rs.getBytes("owner_user_id")),
                        rs.getString("store_name")))
                .optional();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
