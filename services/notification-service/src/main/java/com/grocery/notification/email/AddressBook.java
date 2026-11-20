package com.grocery.notification.email;

import com.grocery.common.security.CurrentUser;
import com.grocery.notification.repository.EmailAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps the address projection up to date from the callers' validated access tokens (Phase 12 §5). identity-service
 * publishes {@code UserRegistered} only for self-registered users; admins created with {@code POST /api/admin/users}
 * and the bootstrap SUPER_ADMIN appear in no event. Their token, issued and signed by identity-service, carries
 * {@code email}, {@code name} and the current {@code roles}, so every user who opens the stream (the bell does on every
 * page) or uses the preferences API becomes known — with exactly the roles identity gives them now, which the role
 * audiences of the email channel need.
 *
 * <p>Writes only when something changed since this replica last saw the user (a small LRU per replica), in a short
 * transaction of its own; a failure is logged and never breaks the caller's request.
 */
@Component
public class AddressBook {

    static final int CACHE_SIZE = 10_000;
    private static final Logger log = LoggerFactory.getLogger(AddressBook.class);

    private final EmailAddressRepository addresses;
    private final TransactionTemplate transaction;
    private final Clock clock;
    /** userId → what was last written for it (email|name|roles). */
    private final Map<UUID, String> seen = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public AddressBook(EmailAddressRepository addresses, PlatformTransactionManager transactionManager, Clock clock) {
        this.addresses = addresses;
        this.transaction = new TransactionTemplate(transactionManager);
        // its own short transaction, even when a caller already has one (which may be read-only)
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Remembers the caller's address, name and roles (never throws). */
    public void learn(CurrentUser user) {
        if (user == null || user.userId() == null || !EmailAddressRepository.usable(user.email())) {
            return;
        }
        List<String> roles = user.roles().stream().sorted().toList();
        String fingerprint = user.email().strip() + "|" + Objects.toString(user.name(), "") + "|" + roles;
        synchronized (seen) {
            if (fingerprint.equals(seen.get(user.userId()))) {
                return;
            }
        }
        try {
            transaction.executeWithoutResult(status -> addresses.learned(user.userId(), user.email(), user.name(),
                    roles, clock.instant()));
            synchronized (seen) {
                seen.put(user.userId(), fingerprint);
            }
        } catch (DataAccessException e) {
            log.warn("Could not remember the email address of user {}: {}", user.userId(), e.toString());
        }
    }

    /** Forgets what this replica wrote (tests). */
    void forget() {
        synchronized (seen) {
            seen.clear();
        }
    }
}
