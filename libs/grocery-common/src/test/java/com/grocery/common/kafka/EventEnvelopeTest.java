package com.grocery.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    record UserRegisteredView(String userId, String role) {
    }

    private static ConsumerRecord<String, String> record(String value, String... headers) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("identity.events", 1, 7L, "user-1", value);
        for (int i = 0; i < headers.length; i += 2) {
            record.headers().add(headers[i], headers[i + 1] == null ? null : headers[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void readsDebeziumHeadersAndPayloadWithTolerantReader() {
        EventEnvelope envelope = EventEnvelope.from(record(
                "{\"userId\":\"user-1\",\"role\":\"VENDOR\",\"newField\":true}",
                "eventId", "e-1", "eventType", "UserRegistered", "eventVersion", "1",
                "correlationId", "corr-12345678", "causationId", "null"));

        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.eventType()).isEqualTo("UserRegistered");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.key()).isEqualTo("user-1");
        assertThat(envelope.correlationId()).isEqualTo("corr-12345678");
        assertThat(envelope.payloadAs(jsonMapper, UserRegisteredView.class))
                .isEqualTo(new UserRegisteredView("user-1", "VENDOR"));
    }

    @Test
    void missingHeadersOrBadPayloadAreInvalidEvents() {
        assertThatThrownBy(() -> EventEnvelope.from(record("{}", "eventType", "X")))
                .isInstanceOf(InvalidEventException.class);
        EventEnvelope envelope = EventEnvelope.from(record("not json", "eventId", "e-2", "eventType", "X"));
        assertThatThrownBy(() -> envelope.payloadAs(jsonMapper, UserRegisteredView.class))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void interceptorExposesCorrelationAndCausationToTheMdc() {
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ConsumerRecord<Object, Object> rec = (ConsumerRecord) record("{}", "eventId", "e-3", "correlationId", "corr-abcdefgh");

        interceptor.intercept(rec, null);
        assertThat(MDC.get("correlationId")).isEqualTo("corr-abcdefgh");
        assertThat(MDC.get("causationId")).isEqualTo("e-3");

        interceptor.afterRecord(rec, null);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("causationId")).isNull();
    }
}
