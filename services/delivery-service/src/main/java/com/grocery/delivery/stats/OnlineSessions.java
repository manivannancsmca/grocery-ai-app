package com.grocery.delivery.stats;

import com.grocery.common.id.Ids;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Records when a partner is online, for the dashboard's "online time". Called by AvailabilityService inside its
 * transactions (the partner row is locked, so a partner's sessions never race):
 * <ul>
 *   <li>going online opens a session; going offline closes it;</li>
 *   <li>a stored location report keeps it alive ({@code last_seen_at}, written at most every 30 s);</li>
 *   <li>a report after more than {@link #QUIET_GAP} of silence (the app was closed while "online") ends the old
 *       session at its last report and starts a new one, so the silence is not counted.</li>
 * </ul>
 */
@Component
public class OnlineSessions {

    /** Longer without a report than this, and the partner was not really online in between. */
    static final Duration QUIET_GAP = Duration.ofMinutes(5);
    /** last_seen_at is precise to this much (fewer writes than one per report). */
    static final Duration SEEN_PRECISION = Duration.ofSeconds(30);

    private final OnlineSessionRepository sessions;

    public OnlineSessions(OnlineSessionRepository sessions) {
        this.sessions = sessions;
    }

    public void wentOnline(UUID partnerId, Instant now) {
        sessions.findFirstByPartnerIdAndEndedAtIsNullOrderByStartedAtDesc(partnerId)
                .ifPresent(open -> open.end(open.getLastSeenAt()));
        sessions.save(new OnlineSession(Ids.newId(), partnerId, now));
    }

    public void wentOffline(UUID partnerId, Instant now) {
        sessions.findFirstByPartnerIdAndEndedAtIsNullOrderByStartedAtDesc(partnerId).ifPresent(open -> open.end(now));
    }

    /** A stored location report of an online partner. */
    public void seen(UUID partnerId, Instant now) {
        OnlineSession open = sessions.findFirstByPartnerIdAndEndedAtIsNullOrderByStartedAtDesc(partnerId).orElse(null);
        if (open == null) {
            // Online since before sessions were recorded (or a closed session after a quiet gap): start one now.
            sessions.save(new OnlineSession(Ids.newId(), partnerId, now));
        } else if (Duration.between(open.getLastSeenAt(), now).compareTo(QUIET_GAP) > 0) {
            open.end(open.getLastSeenAt());
            sessions.save(new OnlineSession(Ids.newId(), partnerId, now));
        } else if (Duration.between(open.getLastSeenAt(), now).compareTo(SEEN_PRECISION) >= 0) {
            open.seen(now);
        }
    }
}
