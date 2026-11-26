package com.grocery.ai.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Which help-center documents a signed-in user may be shown, from the roles in their token. */
class KnowledgeAudienceTest {

    @Test
    void eachRoleReadsItsOwnAudience() {
        assertThat(KnowledgeAudience.of(Set.of("CUSTOMER"))).containsExactly(KnowledgeAudience.CUSTOMER);
        assertThat(KnowledgeAudience.of(Set.of("VENDOR"))).containsExactly(KnowledgeAudience.VENDOR);
        assertThat(KnowledgeAudience.of(Set.of("RESTAURANT_OWNER")))
                .containsExactly(KnowledgeAudience.RESTAURANT_OWNER);
        assertThat(KnowledgeAudience.of(Set.of("DELIVERY_PARTNER")))
                .containsExactly(KnowledgeAudience.DELIVERY_PARTNER);
    }

    @Test
    void everyAdminRoleIsStaff() {
        for (String role : Set.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            assertThat(KnowledgeAudience.of(Set.of(role))).containsExactly(KnowledgeAudience.STAFF);
        }
    }

    @Test
    void aCustomerIsNeverStaffAndUnknownRolesReadNothing() {
        assertThat(KnowledgeAudience.of(Set.of("CUSTOMER"))).doesNotContain(KnowledgeAudience.STAFF,
                KnowledgeAudience.VENDOR);
        assertThat(KnowledgeAudience.of(Set.of("SOMETHING_ELSE"))).isEmpty();
        assertThat(KnowledgeAudience.of(Set.of())).isEmpty();
    }
}
