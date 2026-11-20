package com.grocery.notification.service;

import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.dispatch.Audiences;
import com.grocery.notification.dispatch.SessionReason;
import com.grocery.notification.dispatch.SignalType;
import com.grocery.notification.dto.NotificationDtos.NotificationView;
import com.grocery.notification.dto.NotificationDtos.Signal;
import com.grocery.notification.realtime.ConnectionRegistry;
import com.grocery.notification.realtime.SseFrame;
import com.grocery.notification.realtime.StreamConnection;
import com.grocery.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opens {@code GET /api/notifications/stream} (contract §2):
 * <ol>
 *   <li>audiences from the validated token: {@code user:<sub>}, {@code role:<R>} per role, {@code public};</li>
 *   <li>{@code retry: 3000} first;</li>
 *   <li>register the connection (live frames are held from now on);</li>
 *   <li>replay from {@code Last-Event-ID}: the caller's newer notifications, oldest first, at most
 *       {@code replay-limit}, then {@code signal RESYNC} if more were missed; an unknown or malformed id gets only
 *       {@code RESYNC}; no header, no replay;</li>
 *   <li>send the held live frames, skipping notifications the replay already sent.</li>
 * </ol>
 * The emitter's timeout is set explicitly (the token's remaining lifetime + a grace minute; the TOKEN_EXPIRED close
 * normally comes first), so the servlet container's default async timeout never cuts a stream.
 */
@Service
public class StreamService {

    static final long RETRY_MILLIS = 3_000;
    /** Safety margin after the token's {@code exp}: the scheduled TOKEN_EXPIRED close normally ends the stream. */
    static final Duration TIMEOUT_GRACE = Duration.ofMinutes(1);
    /** Timeout of a stream whose token has no {@code exp} (identity always sets one). */
    static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    private final ConnectionRegistry registry;
    private final NotificationRepository notifications;
    private final JsonMapper json;
    private final Clock clock;
    private final int replayLimit;

    public StreamService(ConnectionRegistry registry, NotificationRepository notifications, JsonMapper json,
                         Clock clock, NotificationProperties properties) {
        this.registry = registry;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
        this.replayLimit = Math.max(1, properties.getReplayLimit());
    }

    public SseEmitter open(Jwt jwt, String lastEventId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        Set<String> inbox = Audiences.inbox(userId, roles == null ? List.of() : roles);
        Set<String> audiences = Audiences.stream(userId, roles == null ? List.of() : roles);
        Instant now = clock.instant();
        Instant expiresAt = jwt.getExpiresAt();
        long timeout = expiresAt == null ? DEFAULT_TIMEOUT.toMillis()
                : Math.max(0, Duration.between(now, expiresAt).toMillis()) + TIMEOUT_GRACE.toMillis();

        SseEmitter emitter = new SseEmitter(timeout);
        StreamConnection connection = registry.newConnection(userId, audiences, expiresAt, emitter);
        emitter.onCompletion(connection::terminate);
        emitter.onTimeout(connection::complete);
        emitter.onError(error -> connection.terminate());

        connection.send(SseFrame.retry(RETRY_MILLIS));
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            // accepted within the JWT clock skew, but already expired: tell the client to refresh
            connection.close(SessionReason.TOKEN_EXPIRED);
            return emitter;
        }
        registry.register(connection);
        try {
            replay(connection, userId, inbox, lastEventId);
        } catch (RuntimeException e) {
            connection.terminate();
            throw e;
        }
        connection.finishReplay();
        return emitter;
    }

    private void replay(StreamConnection connection, UUID userId, Set<String> inbox, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return;
        }
        UUID lastId = parse(lastEventId.strip());
        if (lastId == null || !notifications.isVisible(lastId, inbox)) {
            log.debug("Stream {} of user {}: unknown Last-Event-ID {}, RESYNC", connection.sequence(), userId,
                    lastEventId);
            connection.send(resync());
            return;
        }
        List<NotificationView> newer = notifications.newerThan(userId, inbox, lastId, replayLimit + 1);
        int count = Math.min(newer.size(), replayLimit);
        for (NotificationView view : newer.subList(0, count)) {
            connection.sendReplayed(SseFrame.notification(view.id(), json.writeValueAsString(view)));
        }
        if (newer.size() > replayLimit) {
            connection.send(resync());
        }
        log.debug("Stream {} of user {}: replayed {} notifications after {}{}", connection.sequence(), userId, count,
                lastId, newer.size() > replayLimit ? " + RESYNC" : "");
    }

    private SseFrame resync() {
        Signal signal = new Signal(SignalType.RESYNC, Map.of(), clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return SseFrame.signal(json.writeValueAsString(signal));
    }

    private static UUID parse(String id) {
        try {
            UUID uuid = UUID.fromString(id);
            return uuid.toString().equalsIgnoreCase(id) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
