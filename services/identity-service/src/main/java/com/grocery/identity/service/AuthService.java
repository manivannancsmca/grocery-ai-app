package com.grocery.identity.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.identity.config.IdentityProperties;
import com.grocery.identity.dto.AuthDtos.LoginRequest;
import com.grocery.identity.dto.AuthDtos.RegisterRequest;
import com.grocery.identity.dto.AuthDtos.UserView;
import com.grocery.identity.entity.BusinessMembership;
import com.grocery.identity.entity.RefreshToken;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.event.IdentityEvents;
import com.grocery.identity.exception.IdentityExceptions;
import com.grocery.identity.exception.IdentityExceptions.AccountSuspendedException;
import com.grocery.identity.exception.IdentityExceptions.InvalidCredentialsException;
import com.grocery.identity.exception.IdentityExceptions.RefreshRaceException;
import com.grocery.identity.exception.IdentityExceptions.RefreshTokenInvalidException;
import com.grocery.identity.mapper.UserMapper;
import com.grocery.identity.repository.BusinessMembershipRepository;
import com.grocery.identity.repository.RefreshTokenRepository;
import com.grocery.identity.repository.UserAccountRepository;
import com.grocery.identity.security.AccessTokenService;
import com.grocery.identity.security.AccessTokenService.IssuedAccessToken;
import com.grocery.identity.security.RefreshTokens;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Refresh rotation: every refresh returns a NEW refresh token and revokes the old one. Presenting
 * an already-rotated token means it was copied — the whole session family is revoked — except within a
 * short grace window, where it is just two tabs refreshing at the same time.
 */
@Service
public class AuthService {

    /** Hash compared against when the email is unknown, so response time doesn't reveal accounts. */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z3QAhE4tTSsnzGIcUOeXJCiO";

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final BusinessMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final IdentityProperties properties;
    private final Clock clock;

    public AuthService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
                       BusinessMembershipRepository memberships, PasswordEncoder passwordEncoder,
                       AccessTokenService accessTokenService, OutboxPublisher outbox, AuditService audit,
                       IdentityProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.outbox = outbox;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Session register(RegisterRequest request, ClientInfo client) {
        UserAccount user = createAccount(request, client);
        return openSession(user, null, user.getCreatedAt());
    }

    /**
     * Creates the account and its UserRegistered event without signing anyone in. Registration uses it,
     * and so does the development account seeder, so seeded accounts get their profiles like real ones.
     */
    @Transactional
    public UserAccount createAccount(RegisterRequest request, ClientInfo client) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw IdentityExceptions.emailAlreadyRegistered();
        }
        Instant now = clock.instant();
        UserAccount user = users.saveAndFlush(UserAccount.create(Ids.newId(), email, blankToNull(request.phone()),
                passwordEncoder.encode(request.password()), request.fullName().trim(), Set.of(request.role()), now));

        // Same transaction: the account and its UserRegistered event commit together.
        outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.AGGREGATE, user.getId().toString(),
                "UserRegistered", new IdentityEvents.UserRegistered(user.getId(), email, user.getFullName(),
                        user.getPhone(), request.role(), now)));
        audit.record(AuditService.REGISTERED, user.getId(), email, request.role(), client);
        return user;
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountSuspendedException.class})
    public Session login(LoginRequest request, ClientInfo client) {
        String email = normalizeEmail(request.email());
        Instant now = clock.instant();
        UserAccount user = users.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            audit.record(AuditService.LOGIN_FAILURE, null, email, "unknown email", client);
            throw new InvalidCredentialsException();
        }
        if (user.isLocked(now)) {
            audit.record(AuditService.LOGIN_LOCKED, user.getId(), email, null, client);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin(properties.lockout().maxFailedAttempts(), properties.lockout().duration(), now);
            audit.record(AuditService.LOGIN_FAILURE, user.getId(), email, "wrong password", client);
            throw new InvalidCredentialsException();   // noRollbackFor: the failure counter is saved
        }
        if (user.isSuspended()) {
            audit.record(AuditService.LOGIN_SUSPENDED, user.getId(), email, null, client);
            throw new AccountSuspendedException();
        }
        user.recordSuccessfulLogin(now);
        audit.record(AuditService.LOGIN_SUCCESS, user.getId(), email, null, client);
        return openSession(user, null, now);
    }

    @Transactional(noRollbackFor = {RefreshTokenInvalidException.class, AccountSuspendedException.class})
    public Session refresh(String rawRefreshToken, ClientInfo client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new RefreshTokenInvalidException();
        }
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByHashForUpdate(RefreshTokens.hash(rawRefreshToken))
                .orElseThrow(RefreshTokenInvalidException::new);

        if (current.isRevoked()) {
            if (current.wasRotatedWithin(properties.refreshReuseGrace(), now)) {
                throw new RefreshRaceException();
            }
            refreshTokens.revokeFamily(current.getFamilyId(), RefreshToken.REUSE_DETECTED, now);
            audit.record(AuditService.REFRESH_REUSE_DETECTED, current.getUserId(), null,
                    "family " + current.getFamilyId(), client);
            throw new RefreshTokenInvalidException();
        }
        if (current.isExpired(now)) {
            throw new RefreshTokenInvalidException();
        }
        UserAccount user = users.findById(current.getUserId()).orElseThrow(RefreshTokenInvalidException::new);
        if (user.isSuspended()) {
            refreshTokens.revokeFamily(current.getFamilyId(), RefreshToken.USER_SUSPENDED, now);
            throw new AccountSuspendedException();
        }
        Session session = openSession(user, current.getFamilyId(), now);
        current.rotateTo(session.refreshTokenId(), now);
        return session;
    }

    @Transactional
    public void logout(String rawRefreshToken, ClientInfo client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(RefreshTokens.hash(rawRefreshToken)).ifPresent(token -> {
            refreshTokens.revokeFamily(token.getFamilyId(), RefreshToken.LOGOUT, clock.instant());
            audit.record(AuditService.LOGOUT, token.getUserId(), null, null, client);
        });
    }

    @Transactional(readOnly = true)
    public UserView currentUser(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(IdentityExceptions::userNotFound);
        return UserMapper.toView(user, memberships.findByUserId(userId));
    }

    private Session openSession(UserAccount user, UUID familyId, Instant now) {
        List<BusinessMembership> userMemberships = memberships.findByUserId(user.getId());
        IssuedAccessToken accessToken = accessTokenService.issue(user, userMemberships);
        String rawRefreshToken = RefreshTokens.newRawToken();
        RefreshToken refreshToken = refreshTokens.save(new RefreshToken(Ids.newId(), user.getId(),
                familyId != null ? familyId : Ids.newId(), RefreshTokens.hash(rawRefreshToken),
                now, now.plus(properties.refreshTokenTtl())));
        return new Session(accessToken, rawRefreshToken, refreshToken.getId(),
                UserMapper.toView(user, userMemberships));
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Result of register/login/refresh: the controller turns it into a body + refresh cookie. */
    public record Session(IssuedAccessToken accessToken, String rawRefreshToken, UUID refreshTokenId, UserView user) {
    }
}
