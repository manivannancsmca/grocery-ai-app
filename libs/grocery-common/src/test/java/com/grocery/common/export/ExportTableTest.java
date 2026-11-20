package com.grocery.common.export;

import com.grocery.common.security.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who gets people's contact details in an export. */
class ExportTableTest {

    record Partner(String name, String email, String phone, String city) {
    }

    static final ExportTable<Partner> PARTNERS = ExportTable.<Partner>named("delivery-partners", "Delivery partners")
            .text("Name", Partner::name)
            .text("Email", Partner::email).personal()
            .text("Phone", Partner::phone).personal()
            .text("City", Partner::city)
            .build();

    static CurrentUser user(String... roles) {
        return new CurrentUser(UUID.randomUUID(), "a@grocery.local", "A", Set.of(roles), null, null);
    }

    @Test
    void supportAndOperationsGetContactDetails() {
        for (String role : new String[] {"SUPER_ADMIN", "OPS_ADMIN", "SUPPORT"}) {
            assertThat(PARTNERS.visibleTo(user(role))).isSameAs(PARTNERS);
        }
    }

    @Test
    void otherAdminsGetTheSameExportWithoutThem() {
        ExportTable<Partner> finance = PARTNERS.visibleTo(user("FINANCE_ADMIN"));
        assertThat(finance.columns()).extracting(ExportColumn::header).containsExactly("Name", "City");
        assertThat(finance.values(new Partner("Ravi", "ravi@example.com", "98400", "Chennai")))
                .containsExactly("Ravi", "Chennai");
        assertThat(PARTNERS.visibleTo(user("DELIVERY_MANAGER")).columns()).hasSize(2);
    }

    @Test
    void aTableWithoutPersonalColumnsIsTheSameForEveryone() {
        ExportTable<Partner> cities = ExportTable.<Partner>named("cities", "Cities").text("City", Partner::city).build();
        assertThat(cities.visibleTo(user("FINANCE_ADMIN"))).isSameAs(cities);
    }

    @Test
    void namesAreSafeFileNames() {
        assertThatThrownBy(() -> ExportTable.named("../etc/passwd", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTable.named("Orders", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
