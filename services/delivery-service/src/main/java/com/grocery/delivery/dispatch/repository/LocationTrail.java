package com.grocery.delivery.dispatch.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The sampled trail of active deliveries ({@code location_sample}): at most one row per delivery per
 * {@code grocery.delivery.trail-sample-interval}. Plain JDBC (append-only, never read back as entities). Callers hold
 * the reporting partner's row lock, so two reports of one delivery never race.
 */
@Repository
public class LocationTrail {

    private final JdbcClient jdbc;

    public LocationTrail(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Appends a sample unless the delivery's last one is less than {@code interval} old. Returns whether it did. */
    public boolean sample(UUID deliveryId, UUID partnerId, double latitude, double longitude, Double heading,
                          Double speedKmh, Double accuracyMeters, Instant at, Duration interval) {
        List<LocalDateTime> lastRow = jdbc.sql(
                        "SELECT MAX(recorded_at) FROM location_sample WHERE delivery_id = :deliveryId")
                .param("deliveryId", bytes(deliveryId))
                .query(LocalDateTime.class).list();
        LocalDateTime last = lastRow.isEmpty() ? null : lastRow.getFirst();
        if (last != null && last.toInstant(ZoneOffset.UTC).plus(interval).isAfter(at)) {
            return false;
        }
        jdbc.sql("""
                        INSERT INTO location_sample
                            (delivery_id, partner_id, latitude, longitude, heading, speed_kmh, accuracy_meters, recorded_at)
                        VALUES (:deliveryId, :partnerId, :latitude, :longitude, :heading, :speedKmh, :accuracy, :at)""")
                .param("deliveryId", bytes(deliveryId))
                .param("partnerId", bytes(partnerId))
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("heading", heading)
                .param("speedKmh", speedKmh)
                .param("accuracy", accuracyMeters)
                .param("at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
        return true;
    }

    /**
     * Phase 10 housekeeping: deletes at most {@code limit} samples recorded before {@code cutoff}, oldest first, in
     * one short statement of its own (served by {@code idx_location_sample_recorded}). Returns how many.
     */
    public int deleteRecordedBefore(Instant cutoff, int limit) {
        return jdbc.sql("DELETE FROM location_sample WHERE recorded_at < :cutoff ORDER BY recorded_at LIMIT :limit")
                .param("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }

    /** A UUID as BINARY(16) in the order Hibernate and UUID_TO_BIN use (most significant bits first). */
    static byte[] bytes(UUID id) {
        byte[] bytes = new byte[16];
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
