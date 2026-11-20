package com.grocery.reporting.facts;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Column conversions shared by the fact writers and the report queries: UUID ↔ {@code BINARY(16)} in the UUID's own
 * byte order (no swapping), {@link Instant} ↔ UTC {@code DATETIME(6)} (the connection runs with
 * {@code connectionTimeZone=UTC}).
 */
public final class Sql {

    private Sql() {
    }

    public static byte[] bytes(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }

    public static UUID uuid(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return uuid(rs.getBytes(column));
    }

    public static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
