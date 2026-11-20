package com.grocery.identity.config;

import com.grocery.common.id.Ids;
import com.grocery.common.security.Roles;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;

/**
 * Creates the first SUPER_ADMIN when the platform has none, from {@code grocery.identity.bootstrap-admin.*}
 * (env vars in Docker). Afterwards admins are created through the admin API.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BootstrapAdminInitializer(UserAccountRepository users, PasswordEncoder passwordEncoder,
                                     IdentityProperties properties, TransactionTemplate transactionTemplate, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        IdentityProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (admin.email() == null || admin.email().isBlank() || admin.password() == null || admin.password().isBlank()) {
            return;
        }
        if (users.existsWithRole(Roles.SUPER_ADMIN)) {
            return;
        }
        String email = admin.email().trim().toLowerCase(Locale.ROOT);
        try {
            transactionTemplate.executeWithoutResult(tx -> users.save(UserAccount.create(Ids.newId(), email, null,
                    passwordEncoder.encode(admin.password()), admin.fullName(), Set.of(Roles.SUPER_ADMIN), clock.instant())));
            log.warn("Created bootstrap SUPER_ADMIN {} — change its password for any shared environment", email);
        } catch (DataIntegrityViolationException e) {
            log.info("Bootstrap admin already created by another replica");
        }
    }
}
