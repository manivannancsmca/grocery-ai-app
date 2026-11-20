package com.grocery.identity.config;

import com.grocery.common.id.Ids;
import com.grocery.common.security.Roles;
import com.grocery.identity.dto.AuthDtos.RegisterRequest;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.repository.UserAccountRepository;
import com.grocery.identity.service.AuditService;
import com.grocery.identity.service.AuthService;
import com.grocery.identity.service.ClientInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Development only: creates one well-known account per stakeholder so nobody has to register by hand after
 * a fresh start. The sign-in screen of the Vite dev server lists the same accounts
 * ({@code frontend/grocery-web/src/features/auth/devAccounts.ts} — keep the two lists in step).
 *
 * <p>Runs only with {@code grocery.identity.dev-accounts.enabled=true} (env {@code DEV_SEED_ENABLED}) and never
 * with the {@code aws} profile. Accounts that already exist are left exactly as they are, password included.
 *
 * <p>Customer, vendor, restaurant-owner and delivery-partner accounts go through the normal registration
 * path, so their UserRegistered events create the profiles in the other services. The business accounts
 * start where a real registration starts: onboarding still has to be completed and approved by an admin.
 */
@Component
@Profile("!aws")
@ConditionalOnProperty(prefix = "grocery.identity.dev-accounts", name = "enabled", havingValue = "true")
public class DevAccountSeeder implements ApplicationRunner {

    /** Email, display name and the single role of a seeded account. */
    public record DevAccount(String email, String fullName, String role) {
    }

    /** Self-service roles: created like a registration. */
    public static final List<DevAccount> STAKEHOLDERS = List.of(
            new DevAccount("dev.customer@grocery.local", "Dev Customer", Roles.CUSTOMER),
            new DevAccount("dev.vendor@grocery.local", "Dev Vendor", Roles.VENDOR),
            new DevAccount("dev.restaurant@grocery.local", "Dev Restaurant Owner", Roles.RESTAURANT_OWNER),
            new DevAccount("dev.delivery@grocery.local", "Dev Delivery Partner", Roles.DELIVERY_PARTNER));

    /** Admin roles below SUPER_ADMIN (the bootstrap admin is the SUPER_ADMIN). */
    public static final List<DevAccount> ADMINS = List.of(
            new DevAccount("dev.ops@grocery.local", "Dev Operations Admin", Roles.OPS_ADMIN),
            new DevAccount("dev.finance@grocery.local", "Dev Finance Admin", Roles.FINANCE_ADMIN),
            new DevAccount("dev.support@grocery.local", "Dev Support", Roles.SUPPORT),
            new DevAccount("dev.delivery-manager@grocery.local", "Dev Delivery Manager", Roles.DELIVERY_MANAGER));

    private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

    private final UserAccountRepository users;
    private final AuthService authService;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DevAccountSeeder(UserAccountRepository users, AuthService authService, AuditService audit,
                            PasswordEncoder passwordEncoder, IdentityProperties properties,
                            TransactionTemplate transactionTemplate, Clock clock) {
        this.users = users;
        this.authService = authService;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.devAccounts().password();
        int created = 0;
        for (DevAccount account : STAKEHOLDERS) {
            created += seed(account, () -> authService.createAccount(
                    new RegisterRequest(account.email(), password, account.fullName(), null, account.role()),
                    ClientInfo.system()));
        }
        for (DevAccount account : ADMINS) {
            created += seed(account, () -> transactionTemplate.executeWithoutResult(tx -> {
                UserAccount admin = users.save(UserAccount.create(Ids.newId(), account.email(), null,
                        passwordEncoder.encode(password), account.fullName(), Set.of(account.role()), clock.instant()));
                audit.record(AuditService.ADMIN_CREATED, admin.getId(), account.email(),
                        "roles=[" + account.role() + "] by dev account seeder", ClientInfo.system());
            }));
        }
        log.warn("Development accounts are enabled ({} created, {} already there) — never enable this in a shared "
                + "environment", created, STAKEHOLDERS.size() + ADMINS.size() - created);
    }

    /** Returns 1 when the account was created, 0 when it existed or could not be created. */
    private int seed(DevAccount account, Runnable create) {
        if (users.existsByEmail(account.email())) {
            return 0;
        }
        try {
            create.run();
            return 1;
        } catch (RuntimeException e) {
            // Another replica got there first, or the row is otherwise unusable: a dev convenience must not stop startup.
            log.info("Development account {} was not created: {}", account.email(), e.getMessage());
            return 0;
        }
    }
}
