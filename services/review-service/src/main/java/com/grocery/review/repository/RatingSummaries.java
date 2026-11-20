package com.grocery.review.repository;

import com.grocery.review.entity.TargetType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code rating_summary}: the published-rating summary of each reviewed target, and the aggregate it is recomputed
 * from. {@link #lock} creates the row if needed and locks it; everything that changes a target's published reviews
 * calls it before recomputing, so the recomputations of one target are serialised.
 */
@Repository
public class RatingSummaries {

    /**
     * @param distribution published reviews per star, index 0 = 1 star … index 4 = 5 stars
     * @param version      0 = never published
     */
    public record Summary(TargetType targetType, UUID targetId, BigDecimal averageRating, int ratingCount,
                          List<Integer> distribution, long version, Instant updatedAt) {
    }

    private static final String COLUMNS = """
            target_type, BIN_TO_UUID(target_id) AS target_id, average_rating, rating_count,
            rating_1, rating_2, rating_3, rating_4, rating_5, summary_version, updated_at""";

    private final JdbcClient jdbc;

    public RatingSummaries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Creates the target's row (version 0, all zero) unless it exists, and locks it until the transaction ends.
     * {@code INSERT … ON DUPLICATE KEY UPDATE} takes an exclusive lock on an existing row right away (an
     * {@code INSERT IGNORE} would take a shared one, and two of those upgrading to exclusive deadlock).
     */
    public Summary lock(TargetType targetType, UUID targetId, Instant now) {
        jdbc.sql("""
                        INSERT INTO rating_summary (target_type, target_id, average_rating, rating_count, rating_1,
                                                    rating_2, rating_3, rating_4, rating_5, summary_version, updated_at)
                        VALUES (:targetType, UUID_TO_BIN(:targetId), 0.00, 0, 0, 0, 0, 0, 0, 0, :now)
                        ON DUPLICATE KEY UPDATE summary_version = summary_version""")
                .param("targetType", targetType.name())
                .param("targetId", targetId.toString())
                .param("now", utc(now))
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                         FROM rating_summary
                        WHERE target_type = :targetType AND target_id = UUID_TO_BIN(:targetId) FOR UPDATE""")
                .param("targetType", targetType.name())
                .param("targetId", targetId.toString())
                .query(RatingSummaries::summary)
                .single();
    }

    /** Published reviews of the target per star (index 0 = 1 star), as committed now (READ COMMITTED). */
    public int[] publishedDistribution(TargetType targetType, UUID targetId) {
        int[] counts = new int[5];
        jdbc.sql("""
                        SELECT rating, COUNT(*) AS n FROM review
                        WHERE target_type = :targetType AND target_id = UUID_TO_BIN(:targetId) AND status = 'PUBLISHED'
                        GROUP BY rating""")
                .param("targetType", targetType.name())
                .param("targetId", targetId.toString())
                .query((rs, n) -> {
                    int rating = rs.getInt("rating");
                    if (rating >= 1 && rating <= 5) {
                        counts[rating - 1] = rs.getInt("n");
                    }
                    return null;
                })
                .list();
        return counts;
    }

    public void update(Summary summary) {
        List<Integer> d = summary.distribution();
        jdbc.sql("""
                        UPDATE rating_summary
                        SET average_rating = :average, rating_count = :count, rating_1 = :r1, rating_2 = :r2,
                            rating_3 = :r3, rating_4 = :r4, rating_5 = :r5, summary_version = :version,
                            updated_at = :updatedAt
                        WHERE target_type = :targetType AND target_id = UUID_TO_BIN(:targetId)""")
                .param("average", summary.averageRating())
                .param("count", summary.ratingCount())
                .param("r1", d.get(0))
                .param("r2", d.get(1))
                .param("r3", d.get(2))
                .param("r4", d.get(3))
                .param("r5", d.get(4))
                .param("version", summary.version())
                .param("updatedAt", utc(summary.updatedAt()))
                .param("targetType", summary.targetType().name())
                .param("targetId", summary.targetId().toString())
                .update();
    }

    public Optional<Summary> find(TargetType targetType, UUID targetId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                         FROM rating_summary WHERE target_type = :targetType AND target_id = UUID_TO_BIN(:targetId)""")
                .param("targetType", targetType.name())
                .param("targetId", targetId.toString())
                .query(RatingSummaries::summary)
                .optional();
    }

    /**
     * The next page of published summaries (version &gt; 0) in key order, after ({@code afterType},
     * {@code afterId}); both null for the first page.
     */
    public List<Summary> publishedAfter(TargetType afterType, UUID afterId, int limit) {
        if (afterType == null) {
            return jdbc.sql("SELECT " + COLUMNS + " " + """
                             FROM rating_summary WHERE summary_version > 0
                            ORDER BY target_type, target_id LIMIT :limit""")
                    .param("limit", limit)
                    .query(RatingSummaries::summary)
                    .list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                         FROM rating_summary
                        WHERE summary_version > 0
                          AND (target_type > :afterType
                               OR (target_type = :afterType AND target_id > UUID_TO_BIN(:afterId)))
                        ORDER BY target_type, target_id LIMIT :limit""")
                .param("afterType", afterType.name())
                .param("afterId", afterId.toString())
                .param("limit", limit)
                .query(RatingSummaries::summary)
                .list();
    }

    private static Summary summary(ResultSet rs, int rowNum) throws SQLException {
        return new Summary(
                TargetType.valueOf(rs.getString("target_type")),
                UUID.fromString(rs.getString("target_id")),
                rs.getBigDecimal("average_rating"),
                rs.getInt("rating_count"),
                List.of(rs.getInt("rating_1"), rs.getInt("rating_2"), rs.getInt("rating_3"), rs.getInt("rating_4"),
                        rs.getInt("rating_5")),
                rs.getLong("summary_version"),
                rs.getTimestamp("updated_at").toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
