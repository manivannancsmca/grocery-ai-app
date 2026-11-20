package com.grocery.identity.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.identity.config.IdentityProperties;
import com.grocery.identity.dto.AuthDtos.ChangePasswordRequest;
import com.grocery.identity.dto.AuthDtos.ResetPasswordRequest;
import com.grocery.identity.entity.PasswordResetToken;
import com.grocery.identity.entity.RefreshToken;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.event.IdentityEvents;
import com.grocery.identity.exception.IdentityExceptions;
import com.grocery.identity.exception.IdentityExceptions.AccountSuspendedException;
import com.grocery.identity.exception.IdentityExceptions.CurrentPasswordIncorrectException;
import com.grocery.identity.exception.IdentityExceptions.ResetTokenInvalidException;
import com.grocery.identity.repository.PasswordResetTokenRepository;
import com.grocery.identity.repository.RefreshTokenRepository;
import com.grocery.identity.repository.UserAccountRepository;
import com.grocery.identity.security.RefreshTokens;
import com.grocery.identity.security.TokenRevocationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Password management: change it while signed in, ask for a reset link (forgot), and reset it with the link.
 *
 * <p>Every new password ends the account's other sessions at once: their refresh-token families are revoked, and the
 * gateway's "revoked-before" marker rejects the access tokens already issued. A change keeps the caller's own
 * session (its refresh cookie still works, so the client just refreshes); a reset keeps none and signs nobody in.
 * Each new password is published as {@code PasswordChanged}, so notification-service can tell the owner.
 */
@Service
public class PasswordService {

    /** {@code PasswordChanged.method}. */
    public static final String CHANGE = "CHANGE";
    public static final String RESET = "RESET";

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService revocation;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final IdentityProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PasswordService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
                           PasswordResetTokenRepository resetTokens, PasswordEncoder passwordEncoder,
                           TokenRevocationService revocation, OutboxPublisher outbox, AuditService audit,
                           IdentityProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.revocation = revocation;
        this.outbox = outbox;
        this.audit = audit;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * The signed-in user changes their password. A wrong current password counts towards the lockout like a failed
     * sign-in; while the account is locked even the right one is refused (the same answer, as at sign-in).
     *
     * @param rawRefreshToken the caller's refresh cookie, if sent: its session is the one that stays signed in
     */
    @Transactional(noRollbackFor = CurrentPasswordIncorrectException.class)
    public void change(UUID userId, String rawRefreshToken, ChangePasswordRequest request, ClientInfo client) {
        Instant now = clock.instant();
        UserAccount user = users.findById(userId).orElseThrow(IdentityExceptions::userNotFound);
        if (user.isSuspended()) {
            throw new AccountSuspendedException();
        }
        if (user.isLocked(now)) {
            audit.record(AuditService.PASSWORD_CHANGE_FAILURE, userId, user.getEmail(), "account locked", client);
            throw new CurrentPasswordIncorrectException();
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            user.recordFailedLogin(properties.lockout().maxFailedAttempts(), properties.lockout().duration(), now);
            audit.record(AuditService.PASSWORD_CHANGE_FAILURE, userId, user.getEmail(), "wrong current password",
                    client);
            throw new CurrentPasswordIncorrectException();   // noRollbackFor: the failure counter is saved
        }
        // The current password was just verified, so comparing the two texts is enough (no second hash).
        if (request.newPassword().equals(request.currentPassword())) {
            throw IdentityExceptions.passwordUnchanged();
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        UUID callerFamily = sessionFamily(userId, rawRefreshToken);
        if (callerFamily == null) {
            refreshTokens.revokeAllForUser(userId, RefreshToken.PASSWORD_CHANGED, now);
        } else {
            refreshTokens.revokeOtherFamilies(userId, callerFamily, RefreshToken.PASSWORD_CHANGED, now);
        }
        resetTokens.expireUnused(userId, now);   // a link asked for before the change must not undo it
        passwordChanged(user, CHANGE, now, AuditService.PASSWORD_CHANGED, client);
    }

    /**
     * "Forgot password": emails a reset link if {@code email} belongs to an active account that has not asked for
     * {@code max-requests-per-hour} links in the last hour. The caller always gets the same answer, and the call always
     * takes at least {@code min-response-time}, so neither the answer nor its timing tells whether the account exists.
     */
    public void requestReset(String email, ClientInfo client) {
        long started = System.nanoTime();
        try {
            transaction.executeWithoutResult(status -> issueResetLink(AuthService.normalizeEmail(email), client));
        } finally {
            waitUntil(started, properties.passwordReset().minResponseTime());
        }
    }

    private void issueResetLink(String email, ClientInfo client) {
        Instant now = clock.instant();
        // Row lock first: concurrent requests for one account are counted one after the other, so the limit holds.
        UserAccount user = users.findByEmailForUpdate(email).orElse(null);
        if (user == null) {
            audit.record(AuditService.PASSWORD_RESET_IGNORED, null, email, "unknown email", client);
            return;
        }
        UUID userId = user.getId();
        if (user.isSuspended()) {
            audit.record(AuditService.PASSWORD_RESET_IGNORED, userId, email, "account suspended", client);
            return;
        }
        IdentityProperties.PasswordReset settings = properties.passwordReset();
        long lastHour = resetTokens.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofHours(1)));
        if (lastHour >= settings.maxRequestsPerHour()) {
            audit.record(AuditService.PASSWORD_RESET_IGNORED, userId, email,
                    "hourly limit of " + settings.maxRequestsPerHour() + " reached", client);
            return;
        }
        resetTokens.expireUnused(userId, now);   // only the newest link works
        // The same recipe as refresh tokens: 256 random bits for the email, only the SHA-256 hash in the database.
        String rawToken = RefreshTokens.newRawToken();
        Instant expiresAt = now.plus(settings.ttl());
        resetTokens.save(new PasswordResetToken(Ids.newId(), userId, RefreshTokens.hash(rawToken), now, expiresAt,
                client.ipAddress()));
        outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.AGGREGATE, userId.toString(),
                "PasswordResetRequested", new IdentityEvents.PasswordResetRequested(userId, user.getEmail(),
                        user.getFullName(), rawToken, expiresAt)));
        audit.record(AuditService.PASSWORD_RESET_REQUESTED, userId, email, "expires " + expiresAt, client);
    }

    /**
     * Sets a new password with the token of an emailed link: it must exist, be unused and unexpired, and belong to an
     * account that is still active. The link is used up, a lockout ends, and every session of the account is signed
     * out. Nobody is signed in: the user signs in with the new password.
     */
    @Transactional(noRollbackFor = ResetTokenInvalidException.class)
    public void reset(ResetPasswordRequest request, ClientInfo client) {
        Instant now = clock.instant();
        PasswordResetToken token = resetTokens.findByHashForUpdate(RefreshTokens.hash(request.token())).orElse(null);
        if (token == null) {
            audit.record(AuditService.PASSWORD_RESET_FAILURE, null, null, "unknown token", client);
            throw new ResetTokenInvalidException();
        }
        UserAccount user = users.findById(token.getUserId()).orElse(null);
        String problem = null;
        if (token.getUsedAt() != null) {
            problem = "token already used";
        } else if (!token.isUsable(now)) {
            problem = "token expired or replaced";
        } else if (user == null || user.isSuspended()) {
            problem = "account not active";
        }
        if (problem != null) {
            audit.record(AuditService.PASSWORD_RESET_FAILURE, token.getUserId(), null, problem, client);
            throw new ResetTokenInvalidException();
        }
        UUID userId = user.getId();
        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        token.markUsed(now);
        // Bulk updates last: they flush the changes above and detach the entities.
        resetTokens.expireUnused(userId, now);
        refreshTokens.revokeAllForUser(userId, RefreshToken.PASSWORD_RESET, now);
        passwordChanged(user, RESET, now, AuditService.PASSWORD_RESET, client);
    }

    /** What every new password does besides the sessions: old access tokens die, the owner is told, it is audited. */
    private void passwordChanged(UserAccount user, String method, Instant now, String auditType, ClientInfo client) {
        revocation.revokeAccessTokensIssuedBeforeNow(user.getId());
        outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.AGGREGATE, user.getId().toString(),
                "PasswordChanged", new IdentityEvents.PasswordChanged(user.getId(), user.getEmail(),
                        user.getFullName(), method, now)));
        audit.record(auditType, user.getId(), user.getEmail(), null, client);
    }

    /** The session (refresh-token family) of the caller's cookie, if it is one of the user's. */
    private UUID sessionFamily(UUID userId, String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return null;
        }
        return refreshTokens.findByTokenHash(RefreshTokens.hash(rawRefreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .map(RefreshToken::getFamilyId)
                .orElse(null);
    }

    /** Sleeps until {@code minimum} has passed since {@code startedNanos} (outside any transaction). */
    private static void waitUntil(long startedNanos, Duration minimum) {
        long remaining = minimum.toNanos() - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofNanos(remaining));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
