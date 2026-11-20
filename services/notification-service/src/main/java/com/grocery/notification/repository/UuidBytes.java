package com.grocery.notification.repository;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUID ↔ {@code BINARY(16)} in the UUID's own byte order (no swapping), so UUIDv7 notification ids compare in time
 * order in MySQL ({@code id > :lastEventId}).
 */
public final class UuidBytes {

    private UuidBytes() {
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
}
