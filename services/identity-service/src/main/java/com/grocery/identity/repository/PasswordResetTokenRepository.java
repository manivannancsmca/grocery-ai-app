package com.grocery.identity.repository;

import com.grocery.identity.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /** Row lock (SELECT … FOR UPDATE): two resets with the same link are serialised, so exactly one of them uses it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :hash")
    Optional<PasswordResetToken> findByHashForUpdate(@Param("hash") String hash);

    /** Links requested since {@code since} (the per-account hourly limit counts every request, used or not). */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Ends the account's unused links now: a newer link, a new password or a reset replaces them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.expiresAt = :now
            where t.userId = :userId and t.usedAt is null and t.expiresAt > :now
            """)
    int expireUnused(@Param("userId") UUID userId, @Param("now") Instant now);
}
