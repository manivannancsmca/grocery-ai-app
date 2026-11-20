package com.grocery.common.deadletter;

import com.grocery.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterServiceTest {

    private final DeadLetterFakes fakes = new DeadLetterFakes();
    private final DeadLetterService service = fakes.service;

    @Test
    void listsNewestFirstWithFiltersAndPages() {
        fakes.add(DeadLetterRecords.record("order.events.DLT", 0, 1).timestamp(1_000).group("svc.orders"));
        fakes.add(DeadLetterRecords.record("order.events.DLT", 0, 2).timestamp(3_000).group("svc.orders").eventType("OrderPaid"));
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 7).timestamp(2_000).group("svc.vendors"));

        PageResponse<DeadLetterView> all = service.list(null, null, null, null, null);
        assertThat(all.content()).extracting(DeadLetterView::id)
                .containsExactly("order.events.DLT:0:2", "vendor.events.DLT:0:7", "order.events.DLT:0:1");
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.size()).isEqualTo(20);

        assertThat(service.list(null, "svc.vendors", null, 0, 10).content()).extracting(DeadLetterView::id)
                .containsExactly("vendor.events.DLT:0:7");
        assertThat(service.list(null, null, "OrderPaid", 0, 10).content()).extracting(DeadLetterView::id)
                .containsExactly("order.events.DLT:0:2");

        PageResponse<DeadLetterView> second = service.list(null, null, null, 1, 2);
        assertThat(second.content()).extracting(DeadLetterView::id).containsExactly("order.events.DLT:0:1");
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(service.list(null, null, null, 5, 500).size()).isEqualTo(100);
        assertThat(service.list(null, null, null, 5, 500).content()).isEmpty();

        service.discard("order.events.DLT:0:1", "known bad data", "admin-1");
        assertThat(service.list(Set.of(DeadLetterStatus.DISCARDED), null, null, 0, 10).content()).extracting(DeadLetterView::id)
                .containsExactly("order.events.DLT:0:1");
        assertThat(service.list(Set.of(DeadLetterStatus.PENDING), null, null, 0, 10).totalElements()).isEqualTo(2);

        // several statuses at once (any of them); an empty set is every status
        fakes.replayer.behaviour = d -> "boom";
        service.replay("vendor.events.DLT:0:7", "admin-1");
        assertThat(service.list(Set.of(DeadLetterStatus.PENDING, DeadLetterStatus.REPLAY_FAILED), null, null, 0, 10)
                .content()).extracting(DeadLetterView::id).containsExactly("order.events.DLT:0:2", "vendor.events.DLT:0:7");
        assertThat(service.list(Set.of(), null, null, 0, 10).totalElements()).isEqualTo(3);
        // size=1 for counts
        PageResponse<DeadLetterView> count = service.list(Set.of(DeadLetterStatus.PENDING, DeadLetterStatus.DISCARDED),
                null, null, 0, 1);
        assertThat(count.totalElements()).isEqualTo(2);
        assertThat(count.content()).hasSize(1);
    }

    @Test
    void anUnreadableRecordIsListedWithEmptyFields() {
        // only the consumer group: no event headers, no exception headers, no key, a value nobody can decode
        org.apache.kafka.common.header.internals.RecordHeaders headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        headers.add(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                "test-service.events".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.add("eventVersion", "v-one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.add("occurredAt", "sometime".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.add(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_PARTITION, new byte[]{1, 2});
        headers.add(org.springframework.kafka.support.KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
                "\n\n   \n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fakes.index.add(DeadLetter.from(new org.apache.kafka.clients.consumer.ConsumerRecord<>("x.events.DLT", 0, 3L,
                -1L, org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE, -1, 3, null, new byte[]{0, 9, 9},
                headers, java.util.Optional.empty())));
        fakes.add(DeadLetterRecords.record("x.events.DLT", 0, 4));

        PageResponse<DeadLetterView> page = service.list(null, null, null, 0, 100);
        assertThat(page.totalElements()).isEqualTo(2);
        DeadLetterView unreadable = page.content().stream().filter(v -> v.id().equals("x.events.DLT:0:3")).findFirst()
                .orElseThrow();
        assertThat(unreadable.originalTopic()).isEqualTo("x.events");
        assertThat(unreadable.consumerGroup()).isEqualTo("test-service.events");
        assertThat(unreadable).extracting(DeadLetterView::key, DeadLetterView::eventId, DeadLetterView::eventType,
                        DeadLetterView::eventVersion, DeadLetterView::occurredAt, DeadLetterView::deadLetteredAt,
                        DeadLetterView::exceptionClass, DeadLetterView::exceptionMessage, DeadLetterView::payloadPreview,
                        DeadLetterView::originalPartition, DeadLetterView::originalOffset)
                .containsOnlyNulls();
        assertThat(unreadable.status()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(service.get("x.events.DLT:0:3").payload()).isNull();
        assertThat(service.get("x.events.DLT:0:3").stackTrace()).isNotNull();
    }

    @Test
    void theViewCarriesTheContractsFields() {
        fakes.add(DeadLetterRecords.record("order.events.DLT", 0, 4));

        DeadLetterView view = service.list(null, null, null, 0, 10).content().getFirst();
        assertThat(view.dltTopic()).isEqualTo("order.events.DLT");
        assertThat(view.originalTopic()).isEqualTo("order.events");
        assertThat(view.originalPartition()).isEqualTo(2);
        assertThat(view.originalOffset()).isEqualTo(845L);
        assertThat(view.consumerGroup()).isEqualTo("test-service.events");
        assertThat(view.key()).isEqualTo("order-1");
        assertThat(view.eventType()).isEqualTo("OrderCreated");
        assertThat(view.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(view.exceptionMessage()).isEqualTo("boom");
        assertThat(view.payloadPreview()).isEqualTo("{\"orderId\":\"o-1\",\"amount\":245.00}");
        assertThat(view.status()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(view.attempts()).isZero();
        assertThat(view.lastError()).isNull();
        assertThat(view.lastActionBy()).isNull();
        assertThat(view.lastActionAt()).isNull();
    }

    @Test
    void previewsAreAtMostFiveHundredCharacters() {
        fakes.add(DeadLetterRecords.record("a.DLT", 0, 0).value("{\"text\":\"" + "x".repeat(1000) + "\"}"));
        fakes.add(DeadLetterRecords.record("a.DLT", 0, 1).value(new byte[]{0, 0, 0, 0, 1, 2}));

        assertThat(service.list(null, null, null, 0, 10).content()).extracting(DeadLetterView::payloadPreview)
                .satisfiesExactlyInAnyOrder(
                        preview -> assertThat(preview).hasSize(500).startsWith("{\"text\":\"xxx").endsWith("x…"),
                        preview -> assertThat(preview).isNull());               // undecodable
    }

    @Test
    void theDetailHasTheDecodedPayloadAndTheStackTrace() {
        fakes.add(DeadLetterRecords.record("a.DLT", 0, 0));
        fakes.add(DeadLetterRecords.record("a.DLT", 0, 1).value("not json"));
        fakes.add(DeadLetterRecords.record("a.DLT", 0, 2).value(new byte[]{0, 0, 0, 0, 1, 2}));

        DeadLetterDetail detail = service.get("a.DLT:0:0");
        assertThat(detail.view().id()).isEqualTo("a.DLT:0:0");
        assertThat(detail.payload().get("amount").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(detail.payload().get("amount").toString()).isEqualTo("245.00");
        assertThat(detail.stackTrace()).startsWith("org.springframework.kafka.listener.ListenerExecutionFailedException")
                .hasSizeLessThanOrEqualTo(4096);
        assertThat(service.get("a.DLT:0:1").payload().isString()).isTrue();
        assertThat(service.get("a.DLT:0:2").payload()).isNull();

        assertThatThrownBy(() -> service.get("a.DLT:0:9"))
                .isInstanceOfSatisfying(DeadLetterException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.errorCode()).isEqualTo("DEAD_LETTER_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.get("garbage")).isInstanceOf(DeadLetterException.class);
    }

    @Test
    void aFailedReplayCanBeRetriedUntilItSucceedsThenTheDeadLetterIsClosed() {
        DeadLetter deadLetter = fakes.add(DeadLetterRecords.record("order.events.DLT", 0, 5));
        fakes.replayer.behaviour = d -> "java.lang.IllegalStateException: still broken";

        DeadLetterView failed = service.replay(deadLetter.id(), "ops-1");
        assertThat(failed.status()).isEqualTo(DeadLetterStatus.REPLAY_FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("java.lang.IllegalStateException: still broken");
        assertThat(failed.lastActionBy()).isEqualTo("ops-1");
        assertThat(failed.lastActionAt()).isEqualTo(DeadLetterFakes.NOW);
        assertThat(service.replay(deadLetter.id(), "ops-1").attempts()).isEqualTo(2);

        fakes.replayer.behaviour = d -> null;
        DeadLetterView replayed = service.replay(deadLetter.id(), "ops-2");
        assertThat(replayed.status()).isEqualTo(DeadLetterStatus.REPLAYED);
        assertThat(replayed.attempts()).isEqualTo(3);
        assertThat(replayed.lastError()).isNull();
        assertThat(replayed.lastActionBy()).isEqualTo("ops-2");

        assertThatThrownBy(() -> service.replay(deadLetter.id(), "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.errorCode()).isEqualTo("DEAD_LETTER_CLOSED");
                    assertThat(e.properties()).isEqualTo(Map.of("currentStatus", "REPLAYED"));
                });
        assertThatThrownBy(() -> service.discard(deadLetter.id(), "too late", "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class,
                        e -> assertThat(e.properties()).containsEntry("currentStatus", "REPLAYED"));
        assertThat(fakes.replayer.replayed).hasSize(3);
        assertThat(fakes.actionLog.appended).extracting(a -> a.key())
                .containsOnly("test-service.events|order.events.DLT|0|5");
        assertThat(fakes.actionLog.appended).extracting(a -> a.action().status())
                .containsExactly(DeadLetterStatus.REPLAY_FAILED, DeadLetterStatus.REPLAY_FAILED, DeadLetterStatus.REPLAYED);
    }

    @Test
    void discardNeedsAReasonOfAtMostThreeHundredCharacters() {
        DeadLetter deadLetter = fakes.add(DeadLetterRecords.record("a.DLT", 0, 0));

        for (String reason : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.discard(deadLetter.id(), reason, "ops-1"))
                    .isInstanceOfSatisfying(DeadLetterException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.errorCode()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.properties().get("errors")).isEqualTo(
                                List.of(Map.of("field", "reason", "message", "must not be blank")));
                    });
        }
        assertThatThrownBy(() -> service.discard(deadLetter.id(), "x".repeat(301), "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(fakes.actionLog.appended).isEmpty();

        DeadLetterView discarded = service.discard(deadLetter.id(), " " + "y".repeat(300) + " ", "ops-1");
        assertThat(discarded.status()).isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(discarded.lastActionBy()).isEqualTo("ops-1");
        assertThat(fakes.actionLog.appended.getFirst().action().reason()).isEqualTo("y".repeat(300));
        assertThatThrownBy(() -> service.replay(deadLetter.id(), "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class,
                        e -> assertThat(e.properties()).containsEntry("currentStatus", "DISCARDED"));
        assertThat(fakes.replayer.replayed).isEmpty();
    }

    @Test
    void replayAllTakesOpenDeadLettersOldestFirstAtMostAHundred() {
        for (int i = 0; i < 105; i++) {
            fakes.add(DeadLetterRecords.record("order.events.DLT", 0, i).timestamp(10_000 + i).group("svc.orders"));
        }
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 0).timestamp(1).group("svc.vendors"));
        service.discard("order.events.DLT:0:0", "bad", "ops-1");
        fakes.replayer.behaviour = d -> d.dltOffset() % 10 == 3 ? "boom" : null;

        DeadLetterRequests.ReplayAllResult result = service.replayAll("svc.orders", null, "ops-1");

        assertThat(result).isEqualTo(new DeadLetterRequests.ReplayAllResult(100, 90, 10));
        assertThat(fakes.replayer.replayed).first().isEqualTo("order.events.DLT:0:1");
        assertThat(fakes.replayer.replayed).last().isEqualTo("order.events.DLT:0:100");

        // the failed ones and the rest are still open; replayed ones are not taken again
        fakes.replayer.replayed.clear();
        fakes.replayer.behaviour = d -> null;
        assertThat(service.replayAll(null, null, "ops-1")).isEqualTo(new DeadLetterRequests.ReplayAllResult(15, 15, 0));
        assertThat(fakes.replayer.replayed).first().isEqualTo("vendor.events.DLT:0:0");
        assertThat(service.replayAll(null, "OrderCreated", "ops-1").attempted()).isZero();
    }

    @Test
    void aStateThatCannotBeSavedIs503() {
        DeadLetter replayed = fakes.add(DeadLetterRecords.record("a.DLT", 0, 0));
        DeadLetter discarded = fakes.add(DeadLetterRecords.record("a.DLT", 0, 1));
        fakes.actionLog.failing = true;

        assertThatThrownBy(() -> service.replay(replayed.id(), "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.errorCode()).isEqualTo("DEAD_LETTER_STATUS_NOT_SAVED");
                    assertThat(e.properties()).containsEntry("currentStatus", "REPLAYED");
                });
        // the listener did run: this instance shows the outcome
        assertThat(fakes.index.state(replayed).status()).isEqualTo(DeadLetterStatus.REPLAYED);

        assertThatThrownBy(() -> service.discard(discarded.id(), "bad", "ops-1"))
                .isInstanceOfSatisfying(DeadLetterException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(fakes.index.state(discarded).status()).isEqualTo(DeadLetterStatus.PENDING);

        // a later state read from the topic replaces the local one
        fakes.index.applyAction(replayed.actionKey(),
                new DeadLetterAction(DeadLetterStatus.DISCARDED, 1, null, "ops-2", DeadLetterFakes.NOW, "x"), 0);
        assertThat(fakes.index.state(replayed).status()).isEqualTo(DeadLetterStatus.DISCARDED);
    }
}
