package com.grocery.identity.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.common.web.PageResponse;
import com.grocery.identity.dto.AuthDtos.AdminUserView;
import com.grocery.identity.dto.AuthDtos.CreateAdminRequest;
import com.grocery.identity.dto.UserExports;
import com.grocery.identity.entity.RefreshToken;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.entity.UserStatus;
import com.grocery.identity.event.IdentityEvents;
import com.grocery.identity.exception.IdentityExceptions;
import com.grocery.identity.mapper.UserMapper;
import com.grocery.identity.repository.RefreshTokenRepository;
import com.grocery.identity.repository.UserAccountRepository;
import com.grocery.identity.security.TokenRevocationService;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Admin user management: search, create admin accounts, suspend/reactivate any account. */
@Service
public class UserAdminService {

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService revocation;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final ExportQueries exports;
    private final Clock clock;

    public UserAdminService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
                            PasswordEncoder passwordEncoder, TokenRevocationService revocation,
                            OutboxPublisher outbox, AuditService audit, ExportQueries exports, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.revocation = revocation;
        this.outbox = outbox;
        this.audit = audit;
        this.exports = exports;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserView> search(String query, String role, String status, Integer page, Integer size) {
        SearchFilter filter = SearchFilter.of(query, role, status);
        return Pages.toResponse(
                users.search(filter.like(), filter.role(), filter.status(),
                        Pages.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                UserMapper::toAdminView);
    }

    /**
     * {@code GET /api/admin/users/export}: the search's filters on every page, newest first or by {@code sort}, in
     * keyset batches. No transaction here: each batch has its own.
     */
    public RowSource<AdminUserView> searchExport(String query, String role, String status, String sort) {
        SearchFilter filter = SearchFilter.of(query, role, status);
        return exports.jpql(UserAccount.class, "distinct u", UserAccountRepository.ADMIN_SEARCH)
                .param("query", filter.like())
                .param("role", filter.role())
                .param("status", filter.status())
                .orderBy(UserExports.SORT.resolve(sort))
                .rows(UserMapper::toAdminView);
    }

    @Transactional
    public AdminUserView createAdmin(CreateAdminRequest request, CurrentUser actor, ClientInfo client) {
        String email = AuthService.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw IdentityExceptions.emailAlreadyRegistered();
        }
        UserAccount admin = users.save(UserAccount.create(Ids.newId(), email, request.phone(),
                passwordEncoder.encode(request.password()), request.fullName().trim(), request.roles(), clock.instant()));
        audit.record(AuditService.ADMIN_CREATED, admin.getId(), email, actor.userId(),
                "roles=" + request.roles() + " by " + actor.userId(), client);
        return UserMapper.toAdminView(admin);
    }

    @Transactional
    public AdminUserView suspend(UUID userId, String reason, CurrentUser actor, ClientInfo client) {
        if (userId.equals(actor.userId())) {
            throw IdentityExceptions.cannotSuspendSelf();
        }
        UserAccount user = users.findById(userId).orElseThrow(IdentityExceptions::userNotFound);
        if (user.getRoles().contains(Roles.SUPER_ADMIN) && !actor.hasRole(Roles.SUPER_ADMIN)) {
            throw new ForbiddenOperationException("FORBIDDEN", "Only a SUPER_ADMIN can suspend a SUPER_ADMIN");
        }
        if (user.isSuspended()) {
            return UserMapper.toAdminView(user);
        }
        Instant now = clock.instant();
        user.suspend(reason, now);
        refreshTokens.revokeAllForUser(userId, RefreshToken.USER_SUSPENDED, now);
        revocation.revokeAccessTokensIssuedBeforeNow(userId);
        outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.AGGREGATE, userId.toString(),
                "UserSuspended", new IdentityEvents.UserSuspended(userId, reason, actor.userId())));
        audit.record(AuditService.USER_SUSPENDED, userId, user.getEmail(), actor.userId(),
                reason + " (by " + actor.userId() + ")", client);
        return UserMapper.toAdminView(user);
    }

    @Transactional
    public AdminUserView reactivate(UUID userId, CurrentUser actor, ClientInfo client) {
        UserAccount user = users.findById(userId).orElseThrow(IdentityExceptions::userNotFound);
        if (!user.isSuspended()) {
            return UserMapper.toAdminView(user);
        }
        user.reactivate(clock.instant());
        outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.AGGREGATE, userId.toString(),
                "UserReactivated", new IdentityEvents.UserReactivated(userId, actor.userId())));
        audit.record(AuditService.USER_REACTIVATED, userId, user.getEmail(), actor.userId(), "by " + actor.userId(),
                client);
        return UserMapper.toAdminView(user);
    }

    /** The search's parameters as the query binds them: {@code like} lower-cased with % wildcards; null = any. */
    private record SearchFilter(String like, String role, UserStatus status) {

        static SearchFilter of(String query, String role, String status) {
            return new SearchFilter(
                    query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%",
                    role == null || role.isBlank() ? null : role,
                    BadRequestException.parseEnum(UserStatus.class, "status", status));
        }
    }
}
