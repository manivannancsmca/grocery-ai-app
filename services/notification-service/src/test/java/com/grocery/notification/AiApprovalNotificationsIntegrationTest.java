package com.grocery.notification;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Approval;
import com.grocery.notification.repository.UuidBytes;
import com.grocery.notification.service.NotificationRetention;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 11 contract §8: ai.events in group {@code notification-service.dispatch} → AI_APPROVAL_REQUESTED to the
 * approver user or roles, AI_APPROVAL_DECIDED (REJECTED / EXPIRED only), AI_ACTION_COMPLETED and AI_ACTION_FAILED to
 * the requester and the customer it is for, the reader-dependent link (customer page for the customer the action is
 * for, admin page for everyone else), and the signal AI_APPROVALS_CHANGED to every party — the approvers of the later
 * events come from the {@code ai_approval_ref} projection. Plus the Phase 7 rules (event age, idempotency, broken
 * events, retention) for the new events, and the events as they travel: Avro with the real schemas 1.4.0.
 */
class AiApprovalNotificationsIntegrationTest extends AbstractIntegrationTest {

    /** 14:35 India time. */
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-13T09:05:00Z");

    @Autowired
    KafkaListenerEndpointRegistry kafkaListeners;
    @Autowired
    AvroEventEncoder avroEncoder;
    @Autowired
    JsonAvroConverter avroConverter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    NotificationRetention retention;

    @Test
    void theDispatchConsumerReadsAiEvents() {
        assertThat(kafkaListeners.getListenerContainer("notification-dispatch").getContainerProperties().getTopics())
                .contains("ai.events", "order.events");
    }

    @Test
    void aCustomersOrderIsAskedOfTheCustomerAndItsOutcomeToldOnce() throws Exception {
        UUID customer = UUID.randomUUID();
        Approval approval = TestEvents.customerOrder(customer);
        ConsumerRecord<String, String> requested = TestEvents.approvalRequested(approval, EXPIRES_AT);

        dispatch(requested);

        Stored asked = notificationOf(requested, "AI_APPROVAL_REQUESTED");
        assertThat(notificationsOf(requested)).hasSize(1);
        assertThat(asked.category()).isEqualTo("APPROVAL");
        assertThat(asked.severity()).isEqualTo("INFO");
        assertThat(asked.title()).isEqualTo("Approval needed: " + approval.title());
        assertThat(asked.body()).isEqualTo("Proposed by the Shopping assistant. Expires at 14:35.");
        assertThat(asked.link()).isEqualTo("/assistant/approvals?id=" + approval.approvalId());
        assertThat(asked.audiences()).containsExactly("user:" + customer);
        assertThat(fieldNames(asked.data())).containsExactlyInAnyOrder("approvalId", "action");   // MySQL JSON: any order
        assertThat(asked.data().get("approvalId").asString()).isEqualTo(approval.approvalId().toString());
        assertThat(asked.data().get("action").asString()).isEqualTo("PLACE_ORDER");
        assertThat(approverRow(approval.approvalId())).isEqualTo(Map.of("user", customer.toString(), "roles", ""));
        assertThat(signalAudiences(approval, 1).getFirst()).containsExactly("user:" + customer);

        // the customer sees it in the inbox; nobody else does
        assertThat(inboxAbout(approval.approvalId(), customer, "CUSTOMER"))
                .extracting(n -> n.get("type").asString()).containsExactly("AI_APPROVAL_REQUESTED");
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "SUPER_ADMIN", "FINANCE_ADMIN")).isEmpty();

        ConsumerRecord<String, String> executed = TestEvents.approvalExecuted(approval, customer, "GA-7K3F9Q2M");
        dispatch(executed);

        Stored done = notificationOf(executed, "AI_ACTION_COMPLETED");
        assertThat(notificationsOf(executed)).hasSize(1);               // requester = customer: told once
        assertThat(done.severity()).isEqualTo("SUCCESS");
        assertThat(done.title()).isEqualTo("Done: " + approval.title());
        assertThat(done.body()).isEqualTo("GA-7K3F9Q2M");
        assertThat(done.link()).isEqualTo("/assistant/approvals?id=" + approval.approvalId());
        assertThat(done.audiences()).containsExactly("user:" + customer);
        assertThat(signalAudiences(approval, 2).get(1)).containsExactly("user:" + customer);
    }

    @Test
    void anAdminRefundIsAskedOfTheFinanceRolesAndItsOutcomeToldToTheRequester() throws Exception {
        UUID ops = UUID.randomUUID();
        Approval approval = TestEvents.adminRefund(ops);
        ConsumerRecord<String, String> requested = TestEvents.approvalRequested(approval, EXPIRES_AT);

        dispatch(requested);

        Stored asked = notificationOf(requested, "AI_APPROVAL_REQUESTED");
        assertThat(notificationsOf(requested)).hasSize(1);              // one row for both roles
        assertThat(asked.body()).isEqualTo("Proposed by the Business insights assistant. Expires at 14:35.");
        assertThat(asked.link()).isEqualTo("/admin/ai/approvals?id=" + approval.approvalId());
        assertThat(asked.audiences()).containsExactlyInAnyOrder("role:FINANCE_ADMIN", "role:SUPER_ADMIN");
        assertThat(approverRow(approval.approvalId()))
                .isEqualTo(Map.of("user", "", "roles", "FINANCE_ADMIN,SUPER_ADMIN"));
        assertThat(signalAudiences(approval, 1).getFirst())
                .containsExactlyInAnyOrder("user:" + ops, "role:FINANCE_ADMIN", "role:SUPER_ADMIN");
        // an admin holding both roles sees it once; the proposing OPS_ADMIN and SUPPORT do not get the request
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "FINANCE_ADMIN", "SUPER_ADMIN"))
                .extracting(n -> n.get("type").asString()).containsExactly("AI_APPROVAL_REQUESTED");
        assertThat(inboxAbout(approval.approvalId(), ops, "OPS_ADMIN")).isEmpty();
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "SUPPORT")).isEmpty();

        UUID finance = UUID.randomUUID();
        ConsumerRecord<String, String> rejected = TestEvents.approvalDecided(approval, "REJECTED", finance,
                "Customer already refunded by hand");
        dispatch(rejected);

        Stored notApproved = notificationOf(rejected, "AI_APPROVAL_DECIDED");
        assertThat(notApproved.severity()).isEqualTo("WARNING");
        assertThat(notApproved.title()).isEqualTo("Not approved: " + approval.title());
        assertThat(notApproved.body()).isEqualTo("Customer already refunded by hand");
        assertThat(notApproved.link()).isEqualTo("/admin/ai/approvals?id=" + approval.approvalId());
        assertThat(notApproved.audiences()).containsExactly("user:" + ops);
        // the decided event names no approver: the finance queues refresh through the projection
        assertThat(signalAudiences(approval, 2).get(1))
                .containsExactlyInAnyOrder("user:" + ops, "role:FINANCE_ADMIN", "role:SUPER_ADMIN");
    }

    @Test
    void aFailedExecutionTellsTheRequesterWhatWentWrong() throws Exception {
        UUID ops = UUID.randomUUID();
        Approval approval = TestEvents.adminRefund(ops);
        dispatch(TestEvents.approvalRequested(approval, EXPIRES_AT));
        UUID finance = UUID.randomUUID();
        dispatch(TestEvents.approvalDecided(approval, "APPROVED", finance, "ok"));
        ConsumerRecord<String, String> failed = TestEvents.approvalFailed(approval, finance, "APPROVAL_BODY_MISMATCH",
                "The approved request was changed after it was approved.", 403);

        dispatch(failed);

        Stored couldNot = notificationOf(failed, "AI_ACTION_FAILED");
        assertThat(couldNot.severity()).isEqualTo("ERROR");
        assertThat(couldNot.title()).isEqualTo("Could not complete: " + approval.title());
        assertThat(couldNot.body()).isEqualTo("The approved request was changed after it was approved.");
        assertThat(couldNot.link()).isEqualTo("/admin/ai/approvals?id=" + approval.approvalId());
        assertThat(couldNot.audiences()).containsExactly("user:" + ops);
        assertThat(inboxAbout(approval.approvalId(), ops, "OPS_ADMIN")).extracting(n -> n.get("type").asString())
                .containsExactly("AI_ACTION_FAILED");               // APPROVED itself notified nobody
        assertThat(signalAudiences(approval, 3)).hasSize(3);
    }

    @Test
    void expiryTellsTheCustomerAndApprovedOrCancelledOnlySignal() throws Exception {
        UUID customer = UUID.randomUUID();
        Approval approval = TestEvents.customerOrder(customer);
        dispatch(TestEvents.approvalRequested(approval, EXPIRES_AT));

        ConsumerRecord<String, String> expired = TestEvents.approvalDecided(approval, "EXPIRED", null, null);
        dispatch(expired);
        Stored late = notificationOf(expired, "AI_APPROVAL_DECIDED");
        assertThat(late.title()).isEqualTo("Expired: " + approval.title());
        assertThat(late.body()).isEqualTo("Nobody decided in time.");
        assertThat(late.link()).isEqualTo("/assistant/approvals?id=" + approval.approvalId());
        assertThat(late.audiences()).containsExactly("user:" + customer);

        assertThat(signalAudiences(approval, 2).get(1)).containsExactly("user:" + customer);

        for (String decision : List.of("APPROVED", "CANCELLED")) {
            Approval other = TestEvents.customerOrder(customer);
            dispatch(TestEvents.approvalRequested(other, EXPIRES_AT));
            ConsumerRecord<String, String> quiet = TestEvents.approvalDecided(other, decision, customer, null);
            dispatch(quiet);
            assertThat(processed(quiet)).isTrue();
            assertThat(notificationsOf(quiet)).as(decision).isEmpty();          // nobody is notified …
            assertThat(signalAudiences(other, 2).get(1)).as(decision)           // … but every screen refreshes
                    .containsExactly("user:" + customer);
        }
    }

    @Test
    void anOldOrRepeatedEventNotifiesNobodyTwiceButTheProjectionIsKept() {
        UUID customer = UUID.randomUUID();
        Approval approval = TestEvents.customerOrder(customer);
        ConsumerRecord<String, String> old = TestEvents.old(TestEvents.approvalRequested(approval, EXPIRES_AT));
        dispatch(old);
        assertThat(processed(old)).isTrue();
        assertThat(notificationsOf(old)).isEmpty();
        assertThat(approverRow(approval.approvalId())).isEqualTo(Map.of("user", customer.toString(), "roles", ""));

        ConsumerRecord<String, String> executed = TestEvents.approvalExecuted(approval, customer, "GA-7K3F9Q2M");
        dispatch(executed);
        dispatch(executed);                                                    // redelivered
        assertThat(notificationsOf(executed)).hasSize(1);
        assertThat(signalAudiences(approval, 1)).hasSize(1);
        ConsumerRecord<String, String> oldFailed = TestEvents.old(TestEvents.approvalFailed(approval, customer,
                "TOTAL_CHANGED", "The total changed.", 409));
        dispatch(oldFailed);
        assertThat(notificationsOf(oldFailed)).isEmpty();
        awaitMarker();
        assertThat(recorder.matching(signal("AI_APPROVALS_CHANGED").and(mentioning(approval.approvalId()))))
                .hasSize(1);
    }

    @Test
    void brokenAiEventsAreInvalid() {
        UUID user = UUID.randomUUID();
        for (String missing : List.of("approvalId", "action", "requestedBy")) {
            Map<String, Object> payload = TestEvents.map("approvalId", UUID.randomUUID(), "action", "PLACE_ORDER",
                    "agent", "shopping", "requestedBy", user, "onBehalfOf", user, "title", "Place order",
                    "decidedBy", user, "resultReference", "GA-1", "executedAt", Instant.now().toString());
            payload.put(missing, null);
            ConsumerRecord<String, String> broken = TestEvents.event("ai.events", "ApprovalExecuted",
                    UUID.randomUUID(), payload);
            assertThatThrownBy(() -> dispatch(broken)).as("without %s", missing)
                    .isInstanceOf(InvalidEventException.class);
            assertThat(processed(broken)).isFalse();
        }
    }

    /** The events as ai-orchestrator-service's outbox ships them: Avro (schemas 1.4.0), decoded by our deserializer. */
    @Test
    void eventsThatTravelledAsAvroAreReadTheSameWay() throws Exception {
        UUID ops = UUID.randomUUID();
        UUID finance = UUID.randomUUID();
        Approval approval = TestEvents.adminRefund(ops);
        List<ConsumerRecord<String, String>> records = List.of(
                TestEvents.approvalRequested(approval, EXPIRES_AT),
                TestEvents.approvalDecided(approval, "APPROVED", finance, null),
                TestEvents.approvalExecuted(approval, finance, "rf_" + approval.approvalId().toString().substring(0, 8)),
                TestEvents.approvalFailed(approval, null, "TARGET_UNAVAILABLE", null, null));
        for (ConsumerRecord<String, String> record : records) {
            dispatch(viaAvro(record));
        }

        Stored asked = notificationOf(records.getFirst(), "AI_APPROVAL_REQUESTED");
        assertThat(asked.body()).isEqualTo("Proposed by the Business insights assistant. Expires at 14:35.");
        assertThat(asked.audiences()).containsExactlyInAnyOrder("role:FINANCE_ADMIN", "role:SUPER_ADMIN");
        assertThat(notificationsOf(records.get(1))).isEmpty();
        assertThat(notificationOf(records.get(2), "AI_ACTION_COMPLETED").body()).startsWith("rf_");
        Stored failed = notificationOf(records.get(3), "AI_ACTION_FAILED");
        assertThat(failed.body()).isEqualTo("TARGET_UNAVAILABLE");               // no message: the code
        assertThat(failed.audiences()).containsExactly("user:" + ops);
        assertThat(signalAudiences(approval, 4)).allSatisfy(audiences -> assertThat(audiences)
                .containsExactlyInAnyOrder("user:" + ops, "role:FINANCE_ADMIN", "role:SUPER_ADMIN"));
    }

    @Test
    void retentionForgetsOldApprovalProjectionRows() {
        UUID customer = UUID.randomUUID();
        Approval stale = TestEvents.customerOrder(customer);
        Approval recent = TestEvents.customerOrder(customer);
        dispatch(TestEvents.old(TestEvents.approvalRequested(stale, EXPIRES_AT)));
        dispatch(TestEvents.old(TestEvents.approvalRequested(recent, EXPIRES_AT)));
        jdbc.sql("UPDATE ai_approval_ref SET updated_at = :at WHERE approval_id = :id")
                .param("at", LocalDateTime.ofInstant(Instant.now().minus(Duration.ofDays(91)), ZoneOffset.UTC))
                .param("id", UuidBytes.bytes(stale.approvalId()))
                .update();

        assertThat(retention.forgetApprovalsOlderThan(Instant.now().minus(Duration.ofDays(90))))
                .isGreaterThanOrEqualTo(1);
        assertThat(approverRowCount(stale.approvalId())).isZero();
        assertThat(approverRowCount(recent.approvalId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- Phase 12 §6

    @Test
    void theVendorDecidesItsOwnActionOnTheVendorAssistantPage() throws Exception {
        UUID vendor = UUID.randomUUID();
        Approval approval = TestEvents.vendorStock(vendor);
        ConsumerRecord<String, String> requested = viaAvro(TestEvents.approvalRequested(approval, EXPIRES_AT));
        dispatch(requested);

        Stored asked = notificationOf(requested, "AI_APPROVAL_REQUESTED");
        assertThat(asked.body()).isEqualTo("Proposed by the Vendor assistant. Expires at 14:35.");
        assertThat(asked.link()).isEqualTo("/vendor/assistant?id=" + approval.approvalId());
        assertThat(asked.audiences()).containsExactly("user:" + vendor);
        assertThat(inboxAbout(approval.approvalId(), vendor, "VENDOR")).hasSize(1);
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "VENDOR")).isEmpty();   // another vendor

        ConsumerRecord<String, String> executed = viaAvro(TestEvents.approvalExecuted(approval, vendor, "40 in stock"));
        dispatch(executed);
        Stored done = notificationOf(executed, "AI_ACTION_COMPLETED");
        assertThat(done.link()).isEqualTo("/vendor/assistant?id=" + approval.approvalId());
        assertThat(done.audiences()).containsExactly("user:" + vendor);
    }

    @Test
    void aReassignmentAsksDeliveryManagersOnTheDeliveryPageAndAdminsOnTheAdminPage() throws Exception {
        UUID manager = UUID.randomUUID();
        Approval approval = TestEvents.deliveryReassignment(manager);
        ConsumerRecord<String, String> requested = viaAvro(TestEvents.approvalRequested(approval, EXPIRES_AT));
        dispatch(requested);

        List<Stored> asked = notificationsOf(requested);
        assertThat(asked).hasSize(2);
        assertThat(asked).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactly("role:DELIVERY_MANAGER");
            assertThat(n.link()).isEqualTo("/delivery/assistant?id=" + approval.approvalId());
        });
        assertThat(asked).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactlyInAnyOrder("role:OPS_ADMIN", "role:SUPER_ADMIN");
            assertThat(n.link()).isEqualTo("/admin/ai/approvals?id=" + approval.approvalId());
        });
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "DELIVERY_MANAGER"))
                .extracting(n -> n.get("link").asString())
                .containsExactly("/delivery/assistant?id=" + approval.approvalId());
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "OPS_ADMIN"))
                .extracting(n -> n.get("link").asString())
                .containsExactly("/admin/ai/approvals?id=" + approval.approvalId());
        assertThat(inboxAbout(approval.approvalId(), UUID.randomUUID(), "DELIVERY_PARTNER")).isEmpty();

        ConsumerRecord<String, String> failed = viaAvro(TestEvents.approvalFailed(approval, UUID.randomUUID(),
                "PARTNER_UNAVAILABLE", "No other partner is free.", 409));
        dispatch(failed);
        Stored notDone = notificationOf(failed, "AI_ACTION_FAILED");
        assertThat(notDone.audiences()).containsExactly("user:" + manager);
        assertThat(notDone.link()).isEqualTo("/delivery/assistant?id=" + approval.approvalId());
    }

    // ---------------------------------------------------------------- helpers

    /** The record's payload encoded with the real schema and decoded by this service's value deserializer. */
    private ConsumerRecord<String, String> viaAvro(ConsumerRecord<String, String> record) {
        String eventType = EventEnvelope.header(record, "eventType");
        byte[] wire = avroEncoder.encode(record.topic(), eventType, 1, avroConverter.readExact(record.value()));
        assertThat(AvroEventDeserializer.isAvro(wire)).isTrue();
        Deserializer<?> values = consumerFactory.getValueDeserializer();
        Headers headers = new RecordHeaders();
        Object value = values.deserialize(record.topic(), headers, wire);
        assertThat(headers.lastHeader("springDeserializerExceptionValue")).as("deserialization failure").isNull();
        assertThat(value).isInstanceOf(String.class);
        ConsumerRecord<String, String> decoded = new ConsumerRecord<>(record.topic(), 0, 0L, record.key(),
                (String) value);
        record.headers().forEach(header -> decoded.headers().add(header));
        return decoded;
    }

    /** The audiences of each AI_APPROVALS_CHANGED signal of this approval, in publish order (waits for {@code count}). */
    private List<List<String>> signalAudiences(Approval approval, int count) {
        List<JsonNode> signals = recorder.await(signal("AI_APPROVALS_CHANGED").and(mentioning(approval.approvalId())),
                count, WAIT);
        assertThat(signals).hasSize(count);
        List<List<String>> result = new ArrayList<>();
        for (JsonNode published : signals) {
            assertThat(fieldNames(published.at("/payload/data"))).containsExactly("approvalId");
            List<String> audiences = new ArrayList<>();
            published.get("audiences").forEach(a -> audiences.add(a.asString()));
            result.add(audiences);
        }
        return result;
    }

    /** {@code user} = approver_user_id ("" when null), {@code roles} = approver_roles. */
    private Map<String, String> approverRow(UUID approvalId) {
        return jdbc.sql("SELECT approver_user_id, approver_roles FROM ai_approval_ref WHERE approval_id = :id")
                .param("id", UuidBytes.bytes(approvalId))
                .query((rs, rowNum) -> {
                    UUID user = UuidBytes.uuid(rs.getBytes("approver_user_id"));
                    return Map.of("user", user == null ? "" : user.toString(), "roles", rs.getString("approver_roles"));
                })
                .single();
    }

    private long approverRowCount(UUID approvalId) {
        return jdbc.sql("SELECT COUNT(*) FROM ai_approval_ref WHERE approval_id = :id")
                .param("id", UuidBytes.bytes(approvalId))
                .query(Long.class).single();
    }

    /** A marker after the events of a test: once it arrived, anything published before it has arrived too. */
    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        assertThat(recorder.await(kind("signal").and(mentioning(marker)), 1, WAIT)).isNotEmpty();
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

}
