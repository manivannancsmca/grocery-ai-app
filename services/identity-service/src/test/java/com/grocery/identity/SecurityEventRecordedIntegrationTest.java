package com.grocery.identity;

import com.grocery.identity.service.AuditService;
import com.grocery.identity.service.ClientInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every auth_audit row is also published as {@code SecurityEventRecorded} on {@code identity.events} (aggregate
 * SecurityEvent, key = the row's id), for reporting-service's central audit trail. The event carries the row's own
 * values; {@code actorUserId} is the admin who acted on the account. Row and event share one transaction.
 */
class SecurityEventRecordedIntegrationTest extends AbstractIntegrationTest {

    private static final String USER_AGENT = "Mozilla/5.0 (FreshCart test) " + "x".repeat(300);
    private static final String IP = "203.0.113.7";

    @Autowired
    DataSource dataSource;
    @Autowired
    AuditService audit;
    @Autowired
    PlatformTransactionManager transactionManager;

    /** One auth_audit row as stored. */
    private record AuditRow(String id, String userId, String email, String type, String detail, String ipAddress,
                            String userAgent, Instant occurredAt) {
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    private List<AuditRow> auditRows(String where, Object param) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(id) AS id, BIN_TO_UUID(user_id) AS user_id, email, event_type, detail,
                               ip_address, user_agent, occurred_at
                        FROM auth_audit WHERE %s ORDER BY occurred_at, id""".formatted(where))
                .param(param)
                .query((rs, n) -> new AuditRow(rs.getString("id"), rs.getString("user_id"), rs.getString("email"),
                        rs.getString("event_type"), rs.getString("detail"), rs.getString("ip_address"),
                        rs.getString("user_agent"),
                        rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .list();
    }

    /** The one SecurityEventRecorded outbox row of {@code row}: its key, topic and aggregate, and its payload. */
    private JsonNode event(AuditRow row) {
        List<JsonNode> payloads = jdbc().sql("""
                        SELECT topic, aggregate_type, payload FROM outbox_event
                        WHERE event_type = 'SecurityEventRecorded' AND aggregate_id = ?""")
                .param(row.id())
                .query((rs, n) -> {
                    assertThat(rs.getString("topic")).isEqualTo("identity.events");
                    assertThat(rs.getString("aggregate_type")).isEqualTo("SecurityEvent");
                    return json.readTree(rs.getString("payload"));
                })
                .list();
        assertThat(payloads).as("SecurityEventRecorded of %s %s", row.type(), row.id()).hasSize(1);
        return payloads.getFirst();
    }

    /** The event carries exactly the row's values, plus {@code actorUserId}. */
    private JsonNode assertEventOf(AuditRow row, String actorUserId) {
        JsonNode event = event(row);
        assertThat(event.get("recordId").asString()).isEqualTo(row.id());
        assertThat(event.get("type").asString()).isEqualTo(row.type());
        assertText(event, "userId", row.userId());
        assertText(event, "email", row.email());
        assertText(event, "actorUserId", actorUserId);
        assertText(event, "detail", row.detail());
        assertText(event, "ipAddress", row.ipAddress());
        assertText(event, "userAgent", row.userAgent());
        assertThat(Instant.parse(event.get("occurredAt").asString()))
                .isCloseTo(row.occurredAt(), within(1, ChronoUnit.MICROS));
        assertThat(event.properties()).hasSize(9);
        return event;
    }

    private static void assertText(JsonNode event, String field, String expected) {
        if (expected == null) {
            assertThat(event.get(field).isNull()).as("%s is null", field).isTrue();
        } else {
            assertThat(event.get(field).asString()).as(field).isEqualTo(expected);
        }
    }

    private MockHttpServletResponse loginFrom(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", USER_AGENT).header("X-Forwarded-For", IP + ", 10.0.0.1")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse();
    }

    @Test
    void signInsAndAnAdminSuspensionArePublishedWithTheirAuditRow() throws Exception {
        // A sign-in with an unknown e-mail: no account (userId null), the client as identity saw it
        String unknown = uniqueEmail("unknown");
        assertThat(loginFrom(unknown, PASSWORD).getStatus()).isEqualTo(401);
        List<AuditRow> failures = auditRows("email = ?", unknown);
        assertThat(failures).extracting(AuditRow::type).containsExactly(AuditService.LOGIN_FAILURE);
        AuditRow failure = failures.getFirst();
        JsonNode failed = assertEventOf(failure, null);
        assertThat(failed.get("userId").isNull()).isTrue();
        assertThat(failed.get("email").asString()).isEqualTo(unknown);
        assertThat(failed.get("detail").asString()).isEqualTo("unknown email");
        assertThat(failed.get("ipAddress").asString()).isEqualTo(IP);
        assertThat(failed.get("userAgent").asString()).as("cut to 255 like the table")
                .isEqualTo(USER_AGENT.substring(0, 255));

        // A successful sign-in of a new account
        String email = uniqueEmail("signin");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();
        assertThat(loginFrom(email, PASSWORD).getStatus()).isEqualTo(200);

        // An admin suspends the account, then reactivates it: the admin is the actor
        String admin = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        String adminId = claims(admin).get("sub").asString();
        mvc.perform(post("/api/admin/users/{id}/suspend", userId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"chargeback fraud\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/users/{id}/reactivate", userId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        List<AuditRow> rows = auditRows("user_id = UUID_TO_BIN(?)", userId);
        assertThat(rows).extracting(AuditRow::type).containsExactly(AuditService.REGISTERED,
                AuditService.LOGIN_SUCCESS, AuditService.USER_SUSPENDED, AuditService.USER_REACTIVATED);
        JsonNode registered = assertEventOf(rows.get(0), null);
        assertThat(registered.get("detail").asString()).isEqualTo("CUSTOMER");
        JsonNode signedIn = assertEventOf(rows.get(1), null);
        assertThat(signedIn.get("userId").asString()).isEqualTo(userId);
        assertThat(signedIn.get("email").asString()).isEqualTo(email);
        assertThat(signedIn.get("detail").isNull()).isTrue();
        assertThat(signedIn.get("ipAddress").asString()).isEqualTo(IP);
        JsonNode suspended = assertEventOf(rows.get(2), adminId);
        assertThat(suspended.get("userId").asString()).isEqualTo(userId);
        assertThat(suspended.get("actorUserId").asString()).isEqualTo(adminId);
        assertThat(suspended.get("detail").asString()).isEqualTo("chargeback fraud (by " + adminId + ")");
        JsonNode reactivated = assertEventOf(rows.get(3), adminId);
        assertThat(reactivated.get("detail").asString()).isEqualTo("by " + adminId);
    }

    @Test
    void theRowAndItsEventCommitOrRollBackTogether() {
        // Outside any transaction: the record opens its own
        String alone = uniqueEmail("alone");
        audit.record(AuditService.PASSWORD_RESET_IGNORED, null, alone, "unknown email", ClientInfo.system());
        List<AuditRow> committed = auditRows("email = ?", alone);
        assertThat(committed).hasSize(1);
        JsonNode event = assertEventOf(committed.getFirst(), null);
        assertThat(event.get("userAgent").asString()).isEqualTo("system");
        assertThat(event.get("ipAddress").isNull()).isTrue();

        // Inside the caller's transaction, which then rolls back: neither the row nor the event is left
        String undone = uniqueEmail("undone");
        UUID userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            audit.record(AuditService.LOGIN_FAILURE, userId, undone, "wrong password", ClientInfo.system());
            status.setRollbackOnly();
        });
        assertThat(auditRows("email = ?", undone)).isEmpty();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'SecurityEventRecorded' "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.email')) = ?")
                .param(undone).query(Long.class).single()).isZero();
    }
}
