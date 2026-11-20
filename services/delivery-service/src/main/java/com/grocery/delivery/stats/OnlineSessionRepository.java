package com.grocery.delivery.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OnlineSessionRepository extends JpaRepository<OnlineSession, UUID> {

    /** The partner's open session, if any (there is at most one). */
    Optional<OnlineSession> findFirstByPartnerIdAndEndedAtIsNullOrderByStartedAtDesc(UUID partnerId);

    /** The partner's sessions that overlap [from, to). */
    @Query("""
            select s from OnlineSession s
            where s.partnerId = :partnerId and s.startedAt < :to and coalesce(s.endedAt, s.lastSeenAt) > :from
            order by s.startedAt
            """)
    List<OnlineSession> findOverlapping(@Param("partnerId") UUID partnerId, @Param("from") Instant from,
                                        @Param("to") Instant to);
}
