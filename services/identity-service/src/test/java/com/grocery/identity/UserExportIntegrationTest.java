package com.grocery.identity;

import com.grocery.common.export.ExportProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin user export: the list's filters on every page (read in small keyset batches here, each user once however
 * many roles they have), newest first or by a page column, for exactly the roles that may see the list, and never a
 * password hash.
 */
class UserExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;
    @Autowired
    ExportProperties exportProperties;

    private int batchSize;

    @BeforeEach
    void rememberBatchSize() {
        batchSize = exportProperties.getBatchSize();
    }

    @AfterEach
    void restoreBatchSize() {
        exportProperties.setBatchSize(batchSize);
    }

    /** A caller holding {@code role} (Spring Security's test JWT: the real chain and {@code @PreAuthorize} run). */
    private static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("name", "Admin " + role)
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** A self-registered user, created at {@code createdAtUtc} and never signed in since. */
    private String user(String fullName, String email, String phone, String role, String createdAtUtc)
            throws Exception {
        MockHttpServletResponse registered = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","password":"%s","fullName":"%s","phone":"%s","role":"%s"}
                                """.formatted(email, PASSWORD, fullName, phone, role)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String id = body(registered).at("/user/id").asString();
        jdbc().sql("UPDATE user_account SET created_at = ?, last_login_at = NULL WHERE id = UUID_TO_BIN(?)")
                .params(createdAtUtc, id).update();
        return id;
    }

    @Test
    void theExportFollowsTheFiltersAndTheChosenSortForTheListsRolesOnly() throws Exception {
        String marker = "zx" + UUID.randomUUID().toString().substring(0, 6);
        String asha = user("Asha " + marker, "asha-" + marker + "@example.com", "+919800000001", "CUSTOMER",
                "2026-11-01 04:30:00");
        String bala = user("Bala " + marker, "bala-" + marker + "@example.com", "9800000002", "VENDOR",
                "2026-11-12 10:00:00");
        String chandra = user("Chandra " + marker, "chandra-" + marker + "@example.com", "9800000003",
                "DELIVERY_PARTNER", "2026-11-14 18:45:00");
        // two roles: two rows in the role join, one user in the export
        jdbc().sql("INSERT INTO user_role (user_id, role) VALUES (UUID_TO_BIN(?), 'CUSTOMER')").param(bala).update();
        jdbc().sql("UPDATE user_account SET status = 'SUSPENDED', suspension_reason = 'Fraud check' "
                + "WHERE id = UUID_TO_BIN(?)").param(chandra).update();
        jdbc().sql("UPDATE user_account SET last_login_at = '2026-11-15 03:00:00' WHERE id = UUID_TO_BIN(?)")
                .param(asha).update();
        exportProperties.setBatchSize(2); // three users (four role rows): two keyset batches

        MockHttpServletResponse response = export("/api/admin/users/export?format=csv&query=" + marker.toUpperCase(),
                as("SUPPORT"));
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"users-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> lines = csvLines(response);
        assertThat(lines).containsExactly(
                "Name,Email,Phone,Roles,Status,Suspension reason,User ID,Joined,Last sign-in",
                // newest first, like the list; India time; codes as words
                "Chandra " + marker + ",chandra-" + marker + "@example.com,9800000003,Delivery partner,Suspended,"
                        + "Fraud check," + chandra + ",2026-11-15 00:15,",
                "Bala " + marker + ",bala-" + marker + "@example.com,9800000002,\"Customer, Vendor\",Active,,"
                        + bala + ",2026-11-12 15:30,",
                // a value starting with + gets an apostrophe, so a spreadsheet never runs it as a formula
                "Asha " + marker + ",asha-" + marker + "@example.com,'+919800000001,Customer,Active,," + asha
                        + ",2026-11-01 10:00,2026-11-15 08:30");
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .doesNotContain("{bcrypt}").doesNotContain("$2a$");

        List<String> vendors = csvLines(export("/api/admin/users/export?format=csv&role=VENDOR&query=" + marker,
                as("OPS_ADMIN")));
        assertThat(vendors).hasSize(2);
        assertThat(vendors.get(1)).startsWith("Bala " + marker + ",");
        List<String> suspended = csvLines(export("/api/admin/users/export?format=csv&status=SUSPENDED&query="
                + marker, as("SUPER_ADMIN")));
        assertThat(suspended).hasSize(2);
        assertThat(suspended.get(1)).startsWith("Chandra " + marker + ",");

        List<String> byName = csvLines(export("/api/admin/users/export?format=csv&sort=user,desc&query=" + marker,
                as("SUPPORT")));
        assertThat(byName).hasSize(4);
        assertThat(byName.get(1)).startsWith("Chandra ");
        assertThat(byName.get(2)).startsWith("Bala ");
        assertThat(byName.get(3)).startsWith("Asha ");
        List<String> byStatus = csvLines(export("/api/admin/users/export?format=csv&sort=status,desc&query="
                + marker, as("SUPPORT")));
        assertThat(byStatus.get(1)).startsWith("Chandra ").contains(",Suspended,");
        assertThat(byStatus.subList(2, 4)).allMatch(line -> line.contains(",Active,"));
        List<String> oldestFirst = csvLines(export("/api/admin/users/export?format=csv&sort=created,asc&query="
                + marker, as("SUPPORT")));
        assertThat(oldestFirst.get(1)).startsWith("Asha ");
        assertThat(oldestFirst.get(3)).startsWith("Chandra ");
    }

    @Test
    void onlyTheListsRolesExportAndBadParametersAreRejected() throws Exception {
        mvc.perform(get("/api/admin/users/export?format=docx").with(as("SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        // the last sign-in may be empty and the roles are a collection: not sort keys
        for (String sort : new String[]{"lastLogin", "roles", "passwordHash"}) {
            mvc.perform(get("/api/admin/users/export?format=csv&sort=" + sort).with(as("SUPPORT")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/admin/users/export?format=csv&status=DELETED").with(as("SUPPORT")))
                .andExpect(status().isBadRequest());

        // the list's own rule: finance and delivery admins do not see users, so they do not export them either
        for (String role : new String[]{"FINANCE_ADMIN", "DELIVERY_MANAGER", "CUSTOMER", "VENDOR"}) {
            mvc.perform(get("/api/admin/users/export?format=csv").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/users/export?format=csv")).andExpect(status().isUnauthorized());
    }
}
