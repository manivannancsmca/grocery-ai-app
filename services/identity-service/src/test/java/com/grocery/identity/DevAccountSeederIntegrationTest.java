package com.grocery.identity;

import com.grocery.identity.config.DevAccountSeeder;
import com.grocery.identity.config.DevAccountSeeder.DevAccount;
import com.grocery.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Development accounts ({@code grocery.identity.dev-accounts.enabled=true}): one sign-in per stakeholder,
 * created on startup, with the normal registration event for the self-service roles.
 */
@TestPropertySource(properties = "grocery.identity.dev-accounts.enabled=true")
class DevAccountSeederIntegrationTest extends AbstractIntegrationTest {

    private static final String DEV_PASSWORD = "Dev@12345";

    @Autowired
    DevAccountSeeder seeder;
    @Autowired
    UserAccountRepository users;
    @Autowired
    DataSource dataSource;

    private static List<DevAccount> allAccounts() {
        return Stream.concat(DevAccountSeeder.STAKEHOLDERS.stream(), DevAccountSeeder.ADMINS.stream()).toList();
    }

    @Test
    void everyDevAccountSignsInWithItsRole() throws Exception {
        assertThat(allAccounts()).hasSize(8);
        for (DevAccount account : allAccounts()) {
            MockHttpServletResponse response = login(account.email(), DEV_PASSWORD);

            assertThat(response.getStatus()).as(account.email()).isEqualTo(200);
            assertThat(body(response).at("/user/roles/0").asString()).isEqualTo(account.role());
            assertThat(body(response).at("/user/fullName").asString()).isEqualTo(account.fullName());
        }
    }

    @Test
    void selfServiceAccountsPublishUserRegisteredAndAdminsDoNot() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        for (DevAccount account : allAccounts()) {
            String userId = users.findByEmail(account.email()).orElseThrow().getId().toString();
            long events = jdbc.sql("select count(*) from outbox_event where event_type = 'UserRegistered' and aggregate_id = ?")
                    .param(userId).query(Long.class).single();

            assertThat(events).as(account.email()).isEqualTo(DevAccountSeeder.STAKEHOLDERS.contains(account) ? 1 : 0);
        }
    }

    @Test
    void runningTheSeederAgainChangesNothing() {
        long before = users.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(users.count()).isEqualTo(before);
    }
}
