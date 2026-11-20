package com.grocery.notification;

import com.grocery.common.export.RowSource;
import com.grocery.common.security.Roles;
import com.grocery.notification.dto.EmailDtos.EmailLogEntry;
import com.grocery.notification.repository.UuidBytes;
import com.grocery.notification.service.EmailSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The email log export ({@code GET /api/admin/notifications/emails/export}): the log's filters on every batch, its
 * order or a chosen one, never a body or link, and the recipient's address only for support and operations admins.
 * The rows are written straight into {@code email_outbox} (the shared database: every test uses its own random
 * address marker and filters by it).
 */
class EmailExportIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final UUID admin = UUID.randomUUID();
    @Autowired
    private EmailSettingsService settings;
    private String marker;
    private Instant base;

    @BeforeEach
    void marker() {
        marker = "exp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(5));
    }

    private MockHttpServletResponse export(String query, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get("/api/admin/notifications/emails/export?" + query).with(caller))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private String address(String name) {
        return name + "." + marker + "@example.com";
    }

    /** One notification with an email row per recipient, as the dispatch transaction writes them. */
    private UUID notification() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO notification (id, type, category, severity, title, body, link, data,
                                                  source_event_id, created_at)
                        VALUES (:id, 'ORDER_CONFIRMED', 'ORDER', 'INFO', 'Order confirmed', 'Your order is confirmed',
                                NULL, '{}', :eventId, :createdAt)""")
                .param("id", UuidBytes.bytes(id))
                .param("eventId", UUID.randomUUID().toString())
                .param("createdAt", utc(base))
                .update();
        return id;
    }

    private void email(UUID notificationId, String to, String type, String subject, String status, int attempts,
                       String lastError, Instant createdAt, Instant sentAt) {
        jdbc.sql("""
                        INSERT INTO email_outbox (id, notification_id, user_id, to_address, to_name, type, subject,
                                                  body, link, status, attempts, next_attempt_at, last_error,
                                                  created_at, sent_at)
                        VALUES (:id, :notificationId, :userId, :to, NULL, :type, :subject, :body, :link, :status,
                                :attempts, :nextAttemptAt, :lastError, :createdAt, :sentAt)""")
                .param("id", UuidBytes.bytes(UUID.randomUUID()))
                .param("notificationId", UuidBytes.bytes(notificationId))
                .param("userId", UuidBytes.bytes(UUID.randomUUID()))
                .param("to", to)
                .param("type", type)
                .param("subject", subject)
                .param("body", "Secret body " + marker)
                .param("link", "/reset?token=" + marker)
                .param("status", status)
                .param("attempts", attempts)
                // never due: the email tests' own sender runs claim every due PENDING row of the shared database
                .param("nextAttemptAt", utc(Instant.now().plus(Duration.ofDays(365))))
                .param("lastError", lastError)
                .param("createdAt", utc(createdAt))
                .param("sentAt", sentAt == null ? null : utc(sentAt))
                .update();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Asha (sent, oldest), Ravi (failed), Meena (pending, newest), and someone else's email. */
    private void threeEmails() {
        UUID notification = notification();
        email(notification, address("asha"), "ORDER_CONFIRMED", "Order GA-1 confirmed", "SENT", 1, null, base,
                base.plus(Duration.ofMinutes(1)));
        email(notification, address("ravi"), "REFUND_COMPLETED", "=1+1 refund", "FAILED", 4,
                "550 5.1.1 <" + address("ravi") + ">: Recipient address rejected", base.plus(Duration.ofHours(1)),
                null);
        email(notification, address("meena"), "ACCOUNT_APPROVED", "Account approved", "PENDING", 0, null,
                base.plus(Duration.ofHours(2)), null);
        email(notification, "someone.else@example.com", "ORDER_CONFIRMED", "Order GA-2 confirmed", "SENT", 1, null,
                base.plus(Duration.ofHours(3)), base.plus(Duration.ofHours(3)));
    }

    @Test
    void theExportFollowsTheLogsFiltersAndOrderWithoutBodiesOrLinks() throws Exception {
        threeEmails();

        MockHttpServletResponse response = export("format=csv&to=" + marker.toUpperCase(), as(admin, Roles.SUPPORT));
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"email-log-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo("To,Subject,Type,Status,Attempts,Last error,Created,Sent");
        assertThat(lines).hasSize(4);
        // newest first, like the list; a code becomes words, a formula-like text gets an apostrophe
        assertThat(lines.get(1)).isEqualTo(address("meena") + ",Account approved,Account approved,Pending,0,,"
                + SHOWN.format(base.plus(Duration.ofHours(2))) + ",");
        assertThat(lines.get(2)).startsWith(address("ravi") + ",'=1+1 refund,Refund completed,Failed,4,550 5.1.1 <")
                .endsWith("," + SHOWN.format(base.plus(Duration.ofHours(1))) + ",");
        assertThat(lines.get(3)).isEqualTo(address("asha") + ",Order GA-1 confirmed,Order confirmed,Sent,1,,"
                + SHOWN.format(base) + "," + SHOWN.format(base.plus(Duration.ofMinutes(1))));
        String all = String.join("\n", lines);
        assertThat(all).doesNotContain("someone.else").doesNotContain("Secret body").doesNotContain("token=");

        List<String> failed = csvLines(export("format=csv&status=failed&to=" + marker, as(admin, Roles.OPS_ADMIN)));
        assertThat(failed).hasSize(2);
        assertThat(failed.get(1)).startsWith(address("ravi") + ",");

        // a chosen order (the page's column keys), then the id
        List<String> byAttempts = csvLines(export("format=csv&sort=attempts,asc&to=" + marker,
                as(admin, Roles.SUPPORT)));
        assertThat(byAttempts.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf('.')))
                .containsExactly("meena", "asha", "ravi");
        List<String> byRecipient = csvLines(export("format=csv&sort=to,desc&to=" + marker, as(admin, Roles.SUPPORT)));
        assertThat(byRecipient.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf('.')))
                .containsExactly("ravi", "meena", "asha");
        List<String> oldestFirst = csvLines(export("format=csv&sort=when,asc&to=" + marker,
                as(admin, Roles.SUPPORT)));
        assertThat(oldestFirst.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf('.')))
                .containsExactly("asha", "ravi", "meena");
    }

    @Test
    void addressesAndErrorsReachOnlySupportAndOperationsAdmins() throws Exception {
        threeEmails();

        List<String> finance = csvLines(export("format=csv&to=" + marker, as(admin, Roles.FINANCE_ADMIN)));
        assertThat(finance.getFirst()).isEqualTo("Subject,Type,Status,Attempts,Created,Sent");
        assertThat(finance).hasSize(4);
        assertThat(finance.get(2)).startsWith("'=1+1 refund,Refund completed,Failed,4,");
        assertThat(String.join("\n", finance)).doesNotContain(marker).doesNotContain("@");

        MockHttpServletResponse pdf = export("format=pdf&to=" + marker, as(admin, Roles.SUPER_ADMIN));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MockHttpServletResponse xlsx = export("format=xlsx&to=" + marker, as(admin, Roles.DELIVERY_MANAGER));
        assertThat(xlsx.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    @Test
    void keysetBatchesReadEveryEmailOnceInEachOrder() throws Exception {
        UUID notification = notification();
        // ties on every key: two creation times, attempts, statuses and subjects are shared; the id breaks them
        email(notification, address("a"), "ORDER_CONFIRMED", "Same subject", "SENT", 1, null, base, base);
        email(notification, address("b"), "ORDER_CONFIRMED", "Same subject", "SENT", 1, null, base, base);
        email(notification, address("c"), "ORDER_DELIVERED", "Delivered", "PENDING", 0, null,
                base.plus(Duration.ofHours(1)), null);
        email(notification, address("d"), "REFUND_FAILED", "Refund failed", "FAILED", 4, "421 busy",
                base.plus(Duration.ofHours(1)), null);
        email(notification, address("e"), "ACCOUNT_APPROVED", "Approved", "FAILED", 1, "421 busy",
                base.plus(Duration.ofHours(2)), null);

        for (String sort : new String[]{null, "to", "to,desc", "subject", "subject,desc", "status", "status,desc",
                "attempts", "attempts,desc", "when", "when,desc"}) {
            List<String> whole = recipients(settings.logExport(null, marker, sort), 1000);
            assertThat(whole).as(sort).hasSize(5).doesNotHaveDuplicates();
            assertThat(recipients(settings.logExport(null, marker, sort), 1)).as(sort).isEqualTo(whole);
            assertThat(recipients(settings.logExport(null, marker, sort), 2)).as(sort).isEqualTo(whole);
        }
        List<String> newestFirst = recipients(settings.logExport(null, marker, null), 2);
        assertThat(newestFirst.getFirst()).isEqualTo("e");
        assertThat(newestFirst.subList(1, 3)).containsExactlyInAnyOrder("c", "d");
        assertThat(newestFirst.subList(3, 5)).containsExactlyInAnyOrder("a", "b");
        assertThat(recipients(settings.logExport(null, marker, "to,desc"), 1)).containsExactly("e", "d", "c", "b", "a");
        assertThat(recipients(settings.logExport("FAILED", marker, "attempts"), 1)).containsExactly("e", "d");
        assertThat(settings.logExport("failed", marker, null).count()).isEqualTo(2);
    }

    /** The recipients' names (before the first dot), batch by batch. */
    private static List<String> recipients(RowSource<EmailLogEntry> rows, int batchSize) throws Exception {
        List<String> names = new ArrayList<>();
        rows.forEachBatch(batchSize, batch -> {
            batch.forEach(entry -> names.add(entry.to().substring(0, entry.to().indexOf('.'))));
            return true;
        });
        return names;
    }

    @Test
    void wrongParametersCallersAndAnonymousAreRefused() throws Exception {
        String path = "/api/admin/notifications/emails/export";
        mvc.perform(get(path + "?format=docx").with(as(admin, Roles.SUPPORT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path).with(as(admin, Roles.SUPPORT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path + "?format=csv&sort=body").with(as(admin, Roles.SUPPORT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path + "?format=csv&status=LOST").with(as(admin, Roles.SUPPORT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path + "?format=csv").with(as(UUID.randomUUID(), Roles.CUSTOMER)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "?format=csv").with(as(UUID.randomUUID(), Roles.VENDOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "?format=csv")).andExpect(status().isUnauthorized());
    }
}
