package com.grocery.identity;

import com.grocery.identity.config.DevAccountSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Development accounts are opt-in: without {@code grocery.identity.dev-accounts.enabled} the seeder does not exist. */
class DevAccountSeederDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ObjectProvider<DevAccountSeeder> seeder;

    @Test
    void theSeederIsOffByDefault() {
        assertThat(seeder.getIfAvailable()).isNull();
    }
}
