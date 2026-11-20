package com.grocery.delivery.dispatch.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.Metrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Redis GEO set of online partners, key {@value #KEY}, member = partnerId: {@code GEOADD} on every stored location
 * report (and when going online with a known location), {@code ZREM} when going offline, {@code GEOSEARCH FROMLONLAT …
 * BYRADIUS … ASC} to find the nearest ones. It is only an index: whether a partner is available (approved, online,
 * free, location fresh) is always decided from the database, so a stale or missing member never gets an offer, and a
 * lost Redis only delays assignment until the next location reports.
 *
 * <p>Writes are best effort (failures are logged; the database already holds the location). {@link #nearby} throws
 * {@link GeoIndexUnavailableException} when Redis cannot be reached, so a search is retried rather than declared
 * failed.
 */
@Component
public class PartnerGeoIndex {

    public static final String KEY = "delivery:partners:geo";
    /** Upper bound of members one search returns (the nearest ones). */
    static final int MAX_RESULTS = 200;
    /** Redis' earth radius differs slightly from ours: search a bit wider, the caller filters by its own haversine. */
    static final double RADIUS_MARGIN = 1.01;
    private static final Logger log = LoggerFactory.getLogger(PartnerGeoIndex.class);

    private final StringRedisTemplate redis;

    public PartnerGeoIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** GEOADD: the partner's current position. Best effort. */
    public void put(UUID partnerId, double latitude, double longitude) {
        try {
            redis.opsForGeo().add(KEY, new Point(longitude, latitude), partnerId.toString());
        } catch (RuntimeException e) {
            log.warn("Could not store the location of partner {} in Redis: {}", partnerId, e.toString());
        }
    }

    /** ZREM: the partner went offline. Best effort. */
    public void remove(UUID partnerId) {
        try {
            redis.opsForGeo().remove(KEY, partnerId.toString());
        } catch (RuntimeException e) {
            log.warn("Could not remove partner {} from the Redis GEO set: {}", partnerId, e.toString());
        }
    }

    /**
     * {@code GEOSEARCH key FROMLONLAT lng lat BYRADIUS r km ASC COUNT n}: the partners within (about) {@code radiusKm}
     * of {@code point}, nearest first.
     *
     * @throws GeoIndexUnavailableException Redis could not be reached
     */
    public List<UUID> nearby(GeoPoint point, double radiusKm) {
        GeoResults<GeoLocation<String>> results;
        try {
            results = redis.opsForGeo().search(KEY,
                    GeoReference.fromCoordinate(point.longitude(), point.latitude()),
                    new Distance(radiusKm * RADIUS_MARGIN, Metrics.KILOMETERS),
                    GeoSearchCommandArgs.newGeoSearchArgs().sortAscending().limit(MAX_RESULTS));
        } catch (RuntimeException e) {
            throw new GeoIndexUnavailableException("Redis GEOSEARCH failed: " + e, e);
        }
        List<UUID> partners = new ArrayList<>();
        if (results == null) {
            return partners;
        }
        for (GeoResult<GeoLocation<String>> result : results) {
            try {
                partners.add(UUID.fromString(result.getContent().getName()));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring GEO member '{}' that is not a partner id", result.getContent().getName());
            }
        }
        return partners;
    }

    /** Redis could not be reached during a search. */
    public static class GeoIndexUnavailableException extends RuntimeException {
        public GeoIndexUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
