package com.grocery.identity.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.identity.entity.AuthAudit;
import com.grocery.identity.event.IdentityEvents;
import com.grocery.identity.event.IdentityEvents.SecurityEventRecorded;
import com.grocery.identity.repository.AuthAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes security audit records ({@code auth_audit}) and publishes each one as {@code SecurityEventRecorded} on
 * {@code identity.events}, for the central audit trail. The row and its event share one transaction: the caller's when
 * there is one (they commit or roll back with it), else one of their own.
 */
@Service
public class AuditService {

    public static final String REGISTERED = "REGISTERED";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String LOGIN_SUSPENDED = "LOGIN_SUSPENDED";
    public static final String REFRESH_REUSE_DETECTED = "REFRESH_REUSE_DETECTED";
    public static final String LOGOUT = "LOGOUT";
    public static final String USER_SUSPENDED = "USER_SUSPENDED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    public static final String ADMIN_CREATED = "ADMIN_CREATED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_CHANGE_FAILURE = "PASSWORD_CHANGE_FAILURE";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    /** A forgot request that sent nothing: unknown email, suspended account or hourly limit (in the detail). */
    public static final String PASSWORD_RESET_IGNORED = "PASSWORD_RESET_IGNORED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PASSWORD_RESET_FAILURE = "PASSWORD_RESET_FAILURE";

    /** auth_audit.user_agent is VARCHAR(255); the event carries the same text. */
    static final int USER_AGENT_LENGTH = 255;

    private final AuthAuditRepository repository;
    private final OutboxPublisher outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AuditService(AuthAuditRepository repository, OutboxPublisher outbox,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.clock = clock;
    }

    /** Records what the account holder did, or what happened without anyone signed in. */
    public void record(String eventType, UUID userId, String email, String detail, ClientInfo client) {
        record(eventType, userId, email, null, detail, client);
    }

    /**
     * Records an event and publishes it.
     *
     * @param actorUserId the admin who acted on the account; null when the account holder or nobody acted
     * @param detail      short detail; never a password or token
     */
    public void record(String eventType, UUID userId, String email, UUID actorUserId, String detail,
                       ClientInfo client) {
        UUID id = Ids.newId();
        Instant now = clock.instant();
        String userAgent = truncate(client.userAgent(), USER_AGENT_LENGTH);
        transaction.executeWithoutResult(status -> {
            repository.save(new AuthAudit(id, userId, email, eventType, detail, client.ipAddress(), userAgent, now));
            outbox.publish(OutboxEvent.of(IdentityEvents.TOPIC, IdentityEvents.SECURITY_EVENT_AGGREGATE,
                    id.toString(), IdentityEvents.SECURITY_EVENT_RECORDED, new SecurityEventRecorded(id, eventType,
                            userId, email, actorUserId, detail, client.ipAddress(), userAgent, now)));
        });
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
