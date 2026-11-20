package com.grocery.notification;

import com.grocery.common.security.Roles;
import com.grocery.notification.TestEvents.Approval;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.dispatch.NotificationDraft;
import com.grocery.notification.email.EmailOutbox;
import com.grocery.notification.email.EmailProperties;
import com.grocery.notification.email.EmailRenderer;
import com.grocery.notification.email.EmailSender;
import com.grocery.notification.service.NotificationMetrics;
import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.repository.EmailAddressRepository;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.OutboxEmail;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §5, the email channel: the {@code email_outbox} row written with the notification (per eligible type and
 * recipient, honouring the preferences, role audiences to every active user of the role, idempotent), the address
 * projection (UserRegistered, suspensions, the callers' tokens — how admins become known), the preferences API, the
 * sender against a real SMTP server (Mailpit: From, To, Subject, text and escaped HTML with absolute links), its retries
 * after 1, 5 and 30 minutes then FAILED with SMTP down (in-app delivery untouched), SKIP LOCKED claiming, and the admin
 * email log.
 */
class EmailIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "https://shop.grocery.test";

    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    EmailAddressRepository addresses;
    @Autowired
    EmailOutbox emailOutbox;
    @Autowired
    JavaMailSender mailSender;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    NotificationMetrics metrics;
    @Autowired
    MeterRegistry meterRegistry;

    /** Phase 13: {@code grocery_emails_total{status}}. */
    private double emails(String status) {
        return meterRegistry.get(NotificationMetrics.EMAILS).tag("status", status).counter().count();
    }

    /** A self-registered customer known from UserRegistered; the address equals what their token says. */
    private record Person(UUID id, String email, String name) {
    }

    // ---------------------------------------------------------------- the outbox row

    @Test
    void anEmailedNotificationGetsOneOutboxRowPerRecipientInItsTransaction() {
        Person asha = registered("Asha Sharma");
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(asha.id(), vendorId);
        knownOrder(order);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 2);

        dispatch(confirmed);

        Stored notification = notificationOf(confirmed, "ORDER_CONFIRMED");
        List<OutboxEmail> emails = outbox.forNotification(notification.id());
        assertThat(emails).singleElement().satisfies(email -> {
            assertThat(email.userId()).isEqualTo(asha.id());
            assertThat(email.toAddress()).isEqualTo(asha.email());
            assertThat(email.toName()).isEqualTo("Asha Sharma");
            assertThat(email.type()).isEqualTo("ORDER_CONFIRMED");
            assertThat(email.subject()).isEqualTo(notification.title());
            assertThat(email.body()).isEqualTo(notification.body());
            assertThat(email.link()).isEqualTo("/orders/" + order.orderId());
            assertThat(email.status()).isEqualTo("PENDING");
            assertThat(email.attempts()).isZero();
            assertThat(email.sentAt()).isNull();
            assertThat(email.lastError()).isNull();
        });

        // idempotent: a redelivered event, and the same notification offered twice, add nothing
        dispatch(confirmed);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> emailOutbox.enqueue(
                notification.id(), new NotificationDraft(NotificationType.ORDER_CONFIRMED, notification.title(),
                        notification.body(), notification.link(), Map.of()),
                Set.of("user:" + asha.id()), Instant.now()));
        assertThat(outbox.forNotification(notification.id())).hasSize(1);

        // in-app only types are not emailed
        ConsumerRecord<String, String> accepted = TestEvents.orderAccepted(order, 20);
        dispatch(accepted);
        assertThat(outbox.forNotification(notificationOf(accepted, "ORDER_ACCEPTED").id())).isEmpty();

        // a customer the address projection does not know cannot be emailed
        Order stranger = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(stranger);
        ConsumerRecord<String, String> strangerConfirmed = TestEvents.orderConfirmed(stranger, 1);
        dispatch(strangerConfirmed);
        assertThat(outbox.forNotification(notificationOf(strangerConfirmed, "ORDER_CONFIRMED").id())).isEmpty();
    }

    @Test
    void aRoleAudienceIsEmailedToEveryActiveUserOfTheRoleOnce() throws Exception {
        // admins appear in no identity event: their tokens make them known (stream, inbox, preferences calls)
        UUID superAdmin = UUID.randomUUID();
        UUID financeAndSuper = UUID.randomUUID();
        UUID ops = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID optedOut = UUID.randomUUID();
        preferences(superAdmin, Roles.SUPER_ADMIN);
        preferences(financeAndSuper, Roles.FINANCE_ADMIN, Roles.SUPER_ADMIN);
        preferences(ops, Roles.OPS_ADMIN);
        preferences(suspended, Roles.FINANCE_ADMIN);
        mvc.perform(put("/api/notifications/preferences").with(as(optedOut, Roles.FINANCE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":{\"orders\":false}}"))
                .andExpect(status().isOk());
        dispatch(TestEvents.user("UserSuspended", suspended));
        assertThat(addresses.row(financeAndSuper)).hasValueSatisfying(row -> {
            assertThat(row.get("email")).isEqualTo(tokenEmail(financeAndSuper));
            assertThat(row.get("full_name")).isEqualTo(tokenName(financeAndSuper));
        });
        assertThat(addresses.roles(financeAndSuper)).containsExactly("FINANCE_ADMIN", "SUPER_ADMIN");

        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> failed = TestEvents.refundFailed(order.orderId(), new BigDecimal("95.00"),
                "ORDER_CANCELLED", "GATEWAY_ERROR");
        dispatch(failed);

        List<UUID> recipients = recipientsOf(failed, "REFUND_FAILED");
        assertThat(recipients).contains(superAdmin, financeAndSuper).doesNotContain(ops, suspended, optedOut)
                .doesNotHaveDuplicates();

        // reinstated, and new roles in a newer token: emailed again
        dispatch(TestEvents.user("UserReactivated", suspended));
        preferences(ops, Roles.OPS_ADMIN, Roles.FINANCE_ADMIN);
        ConsumerRecord<String, String> again = TestEvents.refundFailed(order.orderId(), new BigDecimal("95.00"),
                "ORDER_CANCELLED", "GATEWAY_ERROR");
        dispatch(again);
        assertThat(recipientsOf(again, "REFUND_FAILED")).contains(suspended, ops).doesNotContain(optedOut);
    }

    @Test
    void userRegisteredFillsTheAddressProjectionAndASuspensionStopsTheEmails() {
        Person ravi = registered("Ravi Kumar");
        assertThat(addresses.row(ravi.id())).hasValueSatisfying(row -> {
            assertThat(row.get("email")).isEqualTo(ravi.email());
            assertThat(row.get("full_name")).isEqualTo("Ravi Kumar");
            assertThat(row.get("active")).isEqualTo(true);
        });
        assertThat(addresses.roles(ravi.id())).containsExactly("CUSTOMER");

        dispatch(TestEvents.user("UserSuspended", ravi.id()));
        assertThat(addresses.row(ravi.id()).orElseThrow().get("active")).isEqualTo(false);
        // a replayed registration keeps the suspension
        dispatch(TestEvents.old(TestEvents.userRegistered(ravi.id(), ravi.email(), ravi.name(), "CUSTOMER")));
        assertThat(addresses.row(ravi.id()).orElseThrow().get("active")).isEqualTo(false);

        Order order = TestEvents.groceryOrder(ravi.id(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);
        assertThat(outbox.forNotification(notificationOf(confirmed, "ORDER_CONFIRMED").id())).isEmpty();

        // an unusable address is not stored (and not dead-lettered)
        UUID broken = UUID.randomUUID();
        ConsumerRecord<String, String> noAddress = TestEvents.userRegistered(broken, "not an address", "X",
                "CUSTOMER");
        dispatch(noAddress);
        assertThat(processed(noAddress)).isTrue();
        assertThat(addresses.row(broken)).isEmpty();
    }

    // ---------------------------------------------------------------- preferences

    @Test
    void preferencesDefaultToOnAndAreChangedOneCategoryAtATime() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/notifications/preferences").with(as(user, Roles.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.orders").value(true))
                .andExpect(jsonPath("$.email.account").value(true))
                .andExpect(jsonPath("$.email.approvals").value(true));
        mvc.perform(put("/api/notifications/preferences").with(as(user, Roles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":{\"orders\":false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.orders").value(false))
                .andExpect(jsonPath("$.email.account").value(true))
                .andExpect(jsonPath("$.email.approvals").value(true));
        mvc.perform(put("/api/notifications/preferences").with(as(user, Roles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":{\"approvals\":false,\"account\":true}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notifications/preferences").with(as(user, Roles.CUSTOMER)))
                .andExpect(jsonPath("$.email.orders").value(false))
                .andExpect(jsonPath("$.email.account").value(true))
                .andExpect(jsonPath("$.email.approvals").value(false));
        // another user is unaffected
        mvc.perform(get("/api/notifications/preferences").with(as(UUID.randomUUID(), Roles.CUSTOMER)))
                .andExpect(jsonPath("$.email.orders").value(true));

        mvc.perform(put("/api/notifications/preferences").with(as(user, Roles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notifications/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":{\"orders\":true}}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCategoryThatIsOffIsNotEmailedTheOthersAre() throws Exception {
        UUID owner = UUID.randomUUID();
        // the vendor's token made them known; orders off, account on
        mvc.perform(put("/api/notifications/preferences").with(as(owner, Roles.VENDOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":{\"orders\":false}}"))
                .andExpect(status().isOk());

        ConsumerRecord<String, String> approved = TestEvents.vendor("VendorApproved", UUID.randomUUID(), owner,
                "Fresh Farms", null);
        dispatch(approved);
        assertThat(recipientsOf(approved, "ACCOUNT_APPROVED")).containsExactly(owner);

        Order bought = TestEvents.groceryOrder(owner, UUID.randomUUID());
        knownOrder(bought);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(bought, 1);
        dispatch(confirmed);
        assertThat(recipientsOf(confirmed, "ORDER_CONFIRMED")).isEmpty();

        // approvals: on → emailed; off → not
        Approval first = TestEvents.customerOrder(owner);
        ConsumerRecord<String, String> asked = TestEvents.approvalRequested(first, Instant.now().plusSeconds(600));
        dispatch(asked);
        assertThat(recipientsOf(asked, "AI_APPROVAL_REQUESTED")).containsExactly(owner);
        mvc.perform(put("/api/notifications/preferences").with(as(owner, Roles.VENDOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":{\"approvals\":false}}"))
                .andExpect(status().isOk());
        ConsumerRecord<String, String> askedAgain = TestEvents.approvalRequested(TestEvents.customerOrder(owner),
                Instant.now().plusSeconds(600));
        dispatch(askedAgain);
        assertThat(recipientsOf(askedAgain, "AI_APPROVAL_REQUESTED")).isEmpty();
    }

    // ---------------------------------------------------------------- the sender

    @Test
    void theSenderSendsOverSmtpWithTextAndEscapedHtmlAndAbsoluteLinksOnce() {
        Person asha = registered("Asha Sharma");
        UUID vendorId = UUID.randomUUID();
        String storeName = "Green & <b>Basket</b>";
        knownVendor(vendorId, storeName);
        Order order = new Order(UUID.randomUUID(), TestEvents.orderNumber(), "GROCERY", asha.id(), vendorId,
                storeName, new BigDecimal("245.00"));
        knownOrder(order);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);
        Stored notification = notificationOf(confirmed, "ORDER_CONFIRMED");
        assertThat(notification.body()).isEqualTo("Paid ₹245.00. Green & <b>Basket</b> will start preparing it soon.");

        EmailSender sender = sender(mailSender, Clock.systemUTC());
        double sentBefore = emails("SENT");
        double failedBefore = emails("FAILED");
        EmailSender.Result result = sender.sendDue();
        assertThat(result.sent()).isPositive();
        assertThat(emails("SENT") - sentBefore).isEqualTo(result.sent());
        assertThat(emails("FAILED") - failedBefore).isEqualTo(result.retried() + result.failed());

        OutboxEmail email = outbox.forNotification(notification.id()).getFirst();
        assertThat(email.status()).isEqualTo("SENT");
        assertThat(email.attempts()).isEqualTo(1);
        assertThat(email.sentAt()).isNotNull();
        assertThat(email.lastError()).isNull();

        List<JsonNode> caught = mailpit().await(asha.email(), 1, WAIT);
        assertThat(caught).hasSize(1);
        JsonNode summary = caught.getFirst();
        assertThat(summary.at("/From/Address").asString()).isEqualTo("no-reply@grocery.local");
        assertThat(summary.at("/From/Name").asString()).isEqualTo("Grocery");
        assertThat(summary.at("/To/0/Name").asString()).isEqualTo("Asha Sharma");
        assertThat(summary.get("Subject").asString()).isEqualTo("Order " + order.orderNumber() + " confirmed");
        JsonNode message = mailpit().message(summary.get("ID").asString());
        String link = BASE + "/orders/" + order.orderId();
        assertThat(message.get("Text").asString())
                .contains("Order " + order.orderNumber() + " confirmed")
                .contains(notification.body())
                .contains("Open in Grocery: " + link);
        assertThat(message.get("HTML").asString())
                .contains("href=\"" + link + "\"")
                .contains("Green &amp; &lt;b&gt;Basket&lt;/b&gt; will start preparing it soon.")
                .doesNotContain("<b>Basket</b>");

        // sent once: later runs do not send it again
        sender.sendDue();
        sender.sendDue();
        RealtimeRecorder.sleep(300);
        assertThat(mailpit().messagesTo(asha.email())).hasSize(1);
    }

    @Test
    void peoplesTextNeverBecomesHtml() {
        Person asha = registered("Asha <Sharma>");
        Order order = TestEvents.groceryOrder(asha.id(), UUID.randomUUID());
        String comment = "<script>alert('x')</script> Seal \"opened\" & <a href=\"https://evil.test\">used</a>";
        ConsumerRecord<String, String> rejected = TestEvents.returnRejected(order, UUID.randomUUID(),
                TestEvents.returnNumber(), comment);
        dispatch(rejected);
        assertThat(recipientsOf(rejected, "RETURN_REJECTED")).containsExactly(asha.id());

        sender(mailSender, Clock.systemUTC()).sendDue();

        List<JsonNode> caught = mailpit().await(asha.email(), 1, WAIT);
        assertThat(caught).hasSize(1);
        JsonNode message = mailpit().message(caught.getFirst().get("ID").asString());
        assertThat(message.get("HTML").asString())
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; Seal &quot;opened&quot; &amp; "
                        + "&lt;a href=&quot;https://evil.test&quot;&gt;used&lt;/a&gt;")
                .doesNotContain("<script>")
                .doesNotContain("href=\"https://evil.test\"")
                .contains("href=\"" + BASE + "/orders/" + order.orderId() + "\"");
        assertThat(message.get("Text").asString()).contains(comment);
        assertThat(message.at("/To/0/Address").asString()).isEqualTo(asha.email());
    }

    @Test
    void smtpDownRetriesAfterOneFiveAndThirtyMinutesThenFailsAndInAppDeliveryIsUntouched() throws Exception {
        Person asha = registered("Asha Sharma");
        Order order = TestEvents.groceryOrder(asha.id(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);
        Stored notification = notificationOf(confirmed, "ORDER_CONFIRMED");
        // the in-app notification is stored and published whatever SMTP does
        assertThat(recorder.await(kind("notification").and(mentioning(notification.id())), 1, WAIT)).hasSize(1);

        MutableClock clock = new MutableClock(Instant.now());
        EmailSender broken = sender(smtpThatIsDown(), clock);
        double sentBefore = emails("SENT");
        double failedBefore = emails("FAILED");
        EmailSender.Result down = broken.sendDue();
        // every failed SMTP attempt is a FAILED email (alert EmailFailures), also when it is retried later
        assertThat(down.retried()).isPositive();
        assertThat(emails("FAILED") - failedBefore).isEqualTo(down.retried() + down.failed());
        assertThat(emails("SENT")).isEqualTo(sentBefore);
        OutboxEmail first = email(notification);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
        assertThat(first.lastError()).isNotBlank();

        clock.advance(Duration.ofSeconds(30));
        broken.sendDue();
        assertThat(email(notification).attempts()).as("not due yet").isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        broken.sendDue();
        assertThat(email(notification).attempts()).isEqualTo(2);
        assertThat(email(notification).nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        broken.sendDue();
        assertThat(email(notification).attempts()).isEqualTo(3);
        assertThat(email(notification).nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));

        clock.advance(Duration.ofMinutes(29));
        broken.sendDue();
        assertThat(email(notification).attempts()).isEqualTo(3);

        clock.advance(Duration.ofMinutes(1));
        broken.sendDue();
        OutboxEmail failed = email(notification);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(4);
        assertThat(failed.sentAt()).isNull();

        clock.advance(Duration.ofHours(2));
        broken.sendDue();
        sender(mailSender, clock).sendDue();
        assertThat(email(notification).status()).isEqualTo("FAILED");
        assertThat(email(notification).attempts()).isEqualTo(4);
        assertThat(mailpit().messagesTo(asha.email())).isEmpty();

        // the in-app notification is still there for the customer
        assertThat(inboxAbout(order.orderId(), asha.id(), Roles.CUSTOMER))
                .extracting(n -> n.get("type").asString()).contains("ORDER_CONFIRMED");

        // the admin email log
        UUID support = UUID.randomUUID();
        JsonNode log = body(mvc.perform(get("/api/admin/notifications/emails").param("status", "FAILED")
                        .param("to", asha.email().toUpperCase()).with(as(support, Roles.SUPPORT)))
                .andExpect(status().isOk()));
        assertThat(log.get("totalElements").asLong()).isEqualTo(1);
        JsonNode entry = log.at("/content/0");
        assertThat(entry.get("to").asString()).isEqualTo(asha.email());
        assertThat(entry.get("subject").asString()).isEqualTo(notification.title());
        assertThat(entry.get("type").asString()).isEqualTo("ORDER_CONFIRMED");
        assertThat(entry.get("status").asString()).isEqualTo("FAILED");
        assertThat(entry.get("attempts").asInt()).isEqualTo(4);
        assertThat(entry.get("lastError").asString()).isNotBlank();
        assertThat(entry.path("createdAt").asString()).isNotBlank();
        assertThat(entry.path("sentAt").isNull() || entry.path("sentAt").isMissingNode()).as("not sent").isTrue();
        assertThat(body(mvc.perform(get("/api/admin/notifications/emails").param("status", "SENT")
                .param("to", asha.email()).with(as(support, Roles.SUPPORT)))).get("totalElements").asLong())
                .isZero();
        mvc.perform(get("/api/admin/notifications/emails").param("status", "LOST").with(as(support, Roles.SUPPORT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/notifications/emails").with(as(asha.id(), Roles.CUSTOMER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/notifications/emails")).andExpect(status().isUnauthorized());
    }

    @Test
    void aFailedAttemptIsRetriedLaterAndSent() {
        Person asha = registered("Asha Sharma");
        Order order = TestEvents.groceryOrder(asha.id(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> delivered = TestEvents.orderDelivered(order, UUID.randomUUID());
        dispatch(delivered);
        Stored notification = notificationOf(delivered, "ORDER_DELIVERED");

        MutableClock clock = new MutableClock(Instant.now());
        sender(smtpThatIsDown(), clock).sendDue();
        assertThat(email(notification).attempts()).isEqualTo(1);

        EmailSender working = sender(mailSender, clock);
        working.sendDue();
        assertThat(email(notification).status()).as("not due yet").isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(1));
        working.sendDue();
        OutboxEmail sent = email(notification);
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.attempts()).isEqualTo(2);
        assertThat(mailpit().await(asha.email(), 1, WAIT)).hasSize(1);
    }

    @Test
    void replicasNeverTakeTheSameEmailSkipLockedAndTheLease() throws Exception {
        Person asha = registered("Asha Sharma");
        Order order = TestEvents.groceryOrder(asha.id(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);
        UUID emailId = email(notificationOf(confirmed, "ORDER_CONFIRMED")).id();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // replica A takes the due emails and is still in its claiming transaction
            Future<List<OutboxEmail>> replicaA = threads.submit(() -> transaction.execute(status -> {
                List<OutboxEmail> taken = outbox.claimDue(Instant.now(), 1000, Instant.now().plus(Duration.ofMinutes(10)));
                claimed.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return taken;
            }));
            assertThat(claimed.await(20, TimeUnit.SECONDS)).isTrue();
            // replica B at the same moment: does not wait for A's locks and does not get A's rows
            Future<List<OutboxEmail>> replicaB = threads.submit(() -> transaction.execute(status ->
                    outbox.claimDue(Instant.now(), 1000, Instant.now().plus(Duration.ofMinutes(10)))));
            assertThat(replicaB.get(10, TimeUnit.SECONDS)).extracting(OutboxEmail::id).doesNotContain(emailId);
            release.countDown();
            assertThat(replicaA.get(20, TimeUnit.SECONDS)).extracting(OutboxEmail::id).contains(emailId);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        // after A's commit the lease keeps everybody else away; nothing was counted as an attempt
        List<OutboxEmail> later = transaction.execute(status ->
                outbox.claimDue(Instant.now(), 1000, Instant.now().plus(Duration.ofMinutes(10))));
        assertThat(later).extracting(OutboxEmail::id).doesNotContain(emailId);
        OutboxEmail leased = outbox.forNotification(notificationOf(confirmed, "ORDER_CONFIRMED").id()).getFirst();
        assertThat(leased.status()).isEqualTo("PENDING");
        assertThat(leased.attempts()).isZero();
        assertThat(leased.nextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(9)));
    }

    // ---------------------------------------------------------------- helpers

    /** A customer registered through identity (UserRegistered); the address is what their token would say. */
    private Person registered(String name) {
        UUID id = UUID.randomUUID();
        Person person = new Person(id, tokenEmail(id), name);
        dispatch(TestEvents.userRegistered(id, person.email(), name, Roles.CUSTOMER));
        return person;
    }

    private static String tokenEmail(UUID userId) {
        return "user-" + userId.toString().substring(0, 8) + "@example.com";
    }

    private static String tokenName(UUID userId) {
        return "User " + userId.toString().substring(0, 8);
    }

    /** A GET of the preferences API: the caller's token makes them known to the address projection. */
    private void preferences(UUID userId, String... roles) throws Exception {
        mvc.perform(get("/api/notifications/preferences").with(as(userId, roles))).andExpect(status().isOk());
    }

    private List<UUID> recipientsOf(ConsumerRecord<String, String> record, String type) {
        return outbox.forNotification(notificationOf(record, type).id()).stream().map(OutboxEmail::userId).toList();
    }

    private OutboxEmail email(Stored notification) {
        List<OutboxEmail> emails = outbox.forNotification(notification.id());
        assertThat(emails).hasSize(1);
        return emails.getFirst();
    }

    /** A sender of its own (a batch large enough for every due row of the shared database), on {@code clock}. */
    private EmailSender sender(JavaMailSender mail, Clock clock) {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setBatchSize(1000);
        properties.setAppBaseUrl(BASE + "/");
        return new EmailSender(outbox, mail, new EmailRenderer(properties), properties, transactionManager, metrics,
                clock);
    }

    /** SMTP that is down: a port nobody listens on (connection refused at once). */
    private static JavaMailSender smtpThatIsDown() {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        Properties properties = new Properties();
        properties.put("mail.smtp.connectiontimeout", "2000");
        properties.put("mail.smtp.timeout", "2000");
        sender.setJavaMailProperties(properties);
        return sender;
    }
}
