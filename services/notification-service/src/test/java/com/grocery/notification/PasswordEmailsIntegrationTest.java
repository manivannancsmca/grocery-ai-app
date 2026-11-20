package com.grocery.notification;

import com.grocery.notification.email.EmailProperties;
import com.grocery.notification.email.EmailRenderer;
import com.grocery.notification.email.EmailSender;
import com.grocery.notification.repository.EmailAddressRepository;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.OutboxEmail;
import com.grocery.notification.repository.UuidBytes;
import com.grocery.notification.service.NotificationMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password management: the security emails (PasswordResetRequested, PasswordChanged) go to the address in the event,
 * whatever the preferences, and appear in no inbox; CustomerProfileUpdated renames the customer in the address
 * projection.
 */
class PasswordEmailsIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "https://shop.grocery.test";

    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    EmailAddressRepository addresses;
    @Autowired
    JavaMailSender mailSender;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    NotificationMetrics metrics;

    /** A notification row of the event, whatever its audiences (security emails have none). */
    private record Row(UUID id, String type, String link, String data, long audiences) {
    }

    private List<Row> rowsOf(ConsumerRecord<String, String> record) {
        return jdbc.sql("""
                        SELECT n.id, n.type, n.link, n.data,
                               (SELECT COUNT(*) FROM notification_audience a WHERE a.notification_id = n.id) AS audiences
                        FROM notification n WHERE n.source_event_id = :eventId ORDER BY n.id""")
                .param("eventId", TestEvents.eventId(record))
                .query((rs, rowNum) -> new Row(UuidBytes.uuid(rs.getBytes("id")), rs.getString("type"),
                        rs.getString("link"), rs.getString("data"), rs.getLong("audiences")))
                .list();
    }

    /** The one email of the event. */
    private OutboxEmail emailOf(ConsumerRecord<String, String> record) {
        List<Row> rows = rowsOf(record);
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.audiences()).as("no inbox shows it").isZero();
        assertThat(row.link()).as("the stored notification keeps no link").isNull();
        assertThat(row.data()).isEqualTo("{}");
        List<OutboxEmail> emails = outbox.forNotification(row.id());
        assertThat(emails).hasSize(1);
        return emails.getFirst();
    }

    private static ConsumerRecord<String, String> resetRequested(UUID userId, String email, String name, String token,
                                                                 Instant expiresAt) {
        return TestEvents.event("identity.events", "PasswordResetRequested", userId, TestEvents.map("userId", userId,
                "email", email, "fullName", name, "token", token, "expiresAt", expiresAt.toString()));
    }

    private static ConsumerRecord<String, String> passwordChanged(UUID userId, String email, String name,
                                                                  String method) {
        return TestEvents.event("identity.events", "PasswordChanged", userId, TestEvents.map("userId", userId,
                "email", email, "fullName", name, "method", method, "changedAt", Instant.now().toString()));
    }

    private static ConsumerRecord<String, String> profileUpdated(UUID customerId, String fullName) {
        return TestEvents.event("customer.events", "CustomerProfileUpdated", customerId, TestEvents.map(
                "customerId", customerId, "fullName", fullName, "phone", null, "updatedAt", Instant.now().toString()));
    }

    private static String address(String prefix, UUID userId) {
        return prefix + "-" + userId.toString().substring(0, 8) + "@example.com";
    }

    /** A sender of its own (a batch large enough for every due row of the shared database). */
    private EmailSender sender() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setBatchSize(1000);
        properties.setAppBaseUrl(BASE + "/");
        return new EmailSender(outbox, mailSender, new EmailRenderer(properties), properties, transactionManager,
                metrics, Clock.systemUTC());
    }

    @Test
    void theResetLinkGoesToTheEventsAddressWhateverThePreferencesAndAppearsInNoInbox() throws Exception {
        UUID user = UUID.randomUUID();
        // Known from their token (another address) and with account emails turned off: the link comes anyway
        mvc.perform(put("/api/notifications/preferences").with(as(user, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":{\"account\":false}}"))
                .andExpect(status().isOk());
        String email = address("reset", user);
        String token = "Zm9yZ290LXBhc3N3b3JkLXRlc3QtdG9rZW4tMDEyMzQ";
        ConsumerRecord<String, String> requested = resetRequested(user, email, "Asha Rao", token,
                Instant.now().plus(Duration.ofMinutes(30)));

        dispatch(requested);
        dispatch(requested);   // redelivery: one email

        OutboxEmail queued = emailOf(requested);
        assertThat(queued.toAddress()).isEqualTo(email);
        assertThat(queued.toName()).isEqualTo("Asha Rao");
        assertThat(queued.type()).isEqualTo("PASSWORD_RESET_REQUESTED");
        assertThat(queued.subject()).isEqualTo("Reset your Grocery password");
        assertThat(queued.link()).isEqualTo("/reset-password?token=" + token);
        assertThat(queued.body()).startsWith("Hi Asha Rao,")
                .contains("(" + email + ")")
                .contains("It works once and expires in 30 minutes, at ")
                .contains("If you did not ask for this")
                .doesNotContain(token);
        assertThat(inbox(user, "CUSTOMER")).isEmpty();
        assertThat(recorder.matching(RealtimeRecorder.mentioning(user))).as("no signal, no stream").isEmpty();

        sender().sendDue();
        List<JsonNode> caught = mailpit().await(email, 1, WAIT);
        assertThat(caught).hasSize(1);
        assertThat(caught.getFirst().get("Subject").asString()).isEqualTo("Reset your Grocery password");
        JsonNode message = mailpit().message(caught.getFirst().get("ID").asString());
        String link = BASE + "/reset-password?token=" + token;
        assertThat(message.get("Text").asString())
                .contains("Choose a new password: " + link)
                .contains("This is a security email")
                .doesNotContain("turn it off");
        assertThat(message.get("HTML").asString()).contains("href=\"" + link + "\"").contains(">Choose a new password<");
    }

    @Test
    void aChangedPasswordIsReportedWithALinkToResetIt() {
        UUID user = UUID.randomUUID();
        String email = address("changed", user);
        ConsumerRecord<String, String> changed = passwordChanged(user, email, "Ravi Kumar", "CHANGE");

        dispatch(changed);

        OutboxEmail queued = emailOf(changed);
        assertThat(queued.toAddress()).isEqualTo(email);
        assertThat(queued.type()).isEqualTo("PASSWORD_CHANGED");
        assertThat(queued.subject()).isEqualTo("Your Grocery password was changed");
        assertThat(queued.link()).isEqualTo("/forgot-password");
        assertThat(queued.body()).startsWith("Hi Ravi Kumar,")
                .contains("was changed in your account settings on ")
                .contains("Your other devices were signed out.")
                .contains("If this wasn't you, reset your password now");

        ConsumerRecord<String, String> reset = passwordChanged(user, email, "Ravi Kumar", "RESET");
        dispatch(reset);
        assertThat(emailOf(reset).body()).contains("was changed with a password reset link on ")
                .contains("Every device was signed out.");

        sender().sendDue();
        List<JsonNode> caught = mailpit().await(email, 2, WAIT);
        assertThat(caught).hasSize(2);
        JsonNode message = mailpit().message(caught.getFirst().get("ID").asString());
        assertThat(message.get("Text").asString()).contains("Reset your password: " + BASE + "/forgot-password");
    }

    @Test
    void oldEventsExpiredLinksAndUnusableAddressesSendNothing() {
        UUID user = UUID.randomUUID();
        String email = address("nothing", user);
        String token = "b2xkLWV2ZW50LXRva2VuLXRoYXQtaXMtbm90LXNlbnQtMDE";

        ConsumerRecord<String, String> old = TestEvents.old(resetRequested(user, email, "Old", token,
                Instant.now().plus(Duration.ofMinutes(30))));
        ConsumerRecord<String, String> expired = resetRequested(user, email, "Late", token,
                Instant.now().minusSeconds(1));
        ConsumerRecord<String, String> noAddress = passwordChanged(user, "not an address", "Nobody", "CHANGE");
        for (ConsumerRecord<String, String> record : List.of(old, expired, noAddress)) {
            dispatch(record);
            assertThat(processed(record)).isTrue();
            assertThat(rowsOf(record)).as(TestEvents.eventId(record)).isEmpty();
        }
    }

    @Test
    void aCustomersNewProfileNameIsTheNameOnTheirEmails() {
        UUID customer = UUID.randomUUID();
        dispatch(TestEvents.userRegistered(customer, address("renamed", customer), "Asha Sharma", "CUSTOMER"));

        dispatch(profileUpdated(customer, "Asha Rao"));
        assertThat(addresses.row(customer).orElseThrow().get("full_name")).isEqualTo("Asha Rao");

        dispatch(TestEvents.old(profileUpdated(customer, "Asha R.")));   // a projection: old events too
        assertThat(addresses.row(customer).orElseThrow().get("full_name")).isEqualTo("Asha R.");

        UUID stranger = UUID.randomUUID();
        dispatch(profileUpdated(stranger, "Nobody Known"));
        assertThat(addresses.row(stranger)).isEmpty();
    }
}
