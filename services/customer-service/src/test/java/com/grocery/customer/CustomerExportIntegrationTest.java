package com.grocery.customer;

import com.grocery.common.export.ExportProperties;
import com.grocery.common.id.Ids;
import com.grocery.customer.service.CustomerProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer directory export: the search's filter on every page (read in small keyset batches here), newest first
 * or by a page column, e-mail and phone only for support and operations admins, admins only.
 */
class CustomerExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CustomerProfileService profiles;
    @Autowired
    DataSource dataSource;
    @Autowired
    ExportProperties exportProperties;

    private final UUID admin = UUID.randomUUID();
    private int batchSize;

    @BeforeEach
    void rememberBatchSize() {
        batchSize = exportProperties.getBatchSize();
    }

    @AfterEach
    void restoreBatchSize() {
        exportProperties.setBatchSize(batchSize);
    }

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** A registered customer whose profile was created (and last changed) at {@code createdAtUtc}. */
    private UUID customer(String fullName, String email, String phone, String createdAtUtc) {
        UUID id = UUID.randomUUID();
        profiles.onCustomerUserRegistered(Ids.newId().toString(), id, fullName, email, phone);
        JdbcClient.create(dataSource)
                .sql("UPDATE customer SET created_at = ?, updated_at = ? WHERE id = UUID_TO_BIN(?)")
                .params(createdAtUtc, createdAtUtc, id.toString()).update();
        return id;
    }

    @Test
    void theExportFollowsTheSearchAndTheChosenSortAndHidesContactsFromFinance() throws Exception {
        String marker = "zx" + UUID.randomUUID().toString().substring(0, 6);
        UUID anil = customer("Anil " + marker, "anil." + marker + "@example.com", "+919800000001",
                "2026-11-01 04:30:00");
        UUID chitra = customer("Chitra " + marker, "chitra." + marker + "@example.com", null,
                "2026-11-14 18:45:00");
        UUID bina = customer("Bina Shah", marker + "@example.com", "9800000002",
                "2026-11-10 09:00:00");
        customer("Someone Else", "else-" + marker.substring(2) + "@example.com", null, "2026-11-15 05:00:00");
        exportProperties.setBatchSize(2); // three matches: two keyset batches

        MockHttpServletResponse response = export("/api/admin/customers/export?format=csv&query="
                + marker.toUpperCase(), as(admin, "SUPPORT"));
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"customers-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> support = csvLines(response);
        assertThat(support).containsExactly(
                "Name,Email,Phone,Customer ID,Joined,Profile updated",
                // newest first, like the list; India time
                "Chitra " + marker + ",chitra." + marker + "@example.com,," + chitra
                        + ",2026-11-15 00:15,2026-11-15 00:15",
                "Bina Shah," + marker + "@example.com,9800000002," + bina + ",2026-11-10 14:30,2026-11-10 14:30",
                // a value starting with + gets an apostrophe, so a spreadsheet never runs it as a formula
                "Anil " + marker + ",anil." + marker + "@example.com,'+919800000001," + anil
                        + ",2026-11-01 10:00,2026-11-01 10:00");

        List<String> byName = csvLines(export("/api/admin/customers/export?format=csv&sort=name,asc&query=" + marker,
                as(admin, "OPS_ADMIN")));
        assertThat(byName).hasSize(4);
        assertThat(byName.get(1)).startsWith("Anil ");
        assertThat(byName.get(2)).startsWith("Bina Shah,");
        assertThat(byName.get(3)).startsWith("Chitra ");
        List<String> oldestFirst = csvLines(export("/api/admin/customers/export?format=csv&sort=joined,asc&query="
                + marker, as(admin, "SUPER_ADMIN")));
        assertThat(oldestFirst.get(1)).startsWith("Anil ");
        assertThat(oldestFirst.get(3)).startsWith("Chitra ");

        List<String> finance = csvLines(export("/api/admin/customers/export?format=csv&query=" + marker,
                as(admin, "FINANCE_ADMIN")));
        assertThat(finance.getFirst()).isEqualTo("Name,Customer ID,Joined,Profile updated");
        assertThat(finance).hasSize(4);
        assertThat(String.join("\n", finance)).doesNotContain("@example.com").doesNotContain("9800000002");

        MockHttpServletResponse pdf = export("/api/admin/customers/export?format=pdf&query=" + marker,
                as(admin, "DELIVERY_MANAGER"));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void onlyAdminsExportAndBadParametersAreRejected() throws Exception {
        mvc.perform(get("/api/admin/customers/export?format=docx").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        // the phone may be empty: not a sort key; e-mail is no page column
        for (String sort : new String[]{"phone", "email", "name,sideways"}) {
            mvc.perform(get("/api/admin/customers/export?format=csv&sort=" + sort).with(as(admin, "SUPPORT")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/admin/customers/export?format=csv").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/customers/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/customers/export?format=csv")).andExpect(status().isUnauthorized());
    }
}
