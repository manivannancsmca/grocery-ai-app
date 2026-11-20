package com.grocery.notification.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The occurredAt header as Debezium writes it (epoch microseconds) and the other shapes we accept. */
class OccurredAtTest {

    private static final Instant AT = Instant.parse("2026-11-11T10:15:30.123456Z");

    @Test
    void epochMicrosecondsFromDebezium() {
        assertThat(OccurredAt.parse("1794392130123456")).isEqualTo(AT);
    }

    @Test
    void epochMillisecondsAndSeconds() {
        assertThat(OccurredAt.parse("1794392130123")).isEqualTo(Instant.parse("2026-11-11T10:15:30.123Z"));
        assertThat(OccurredAt.parse("1794392130")).isEqualTo(Instant.parse("2026-11-11T10:15:30Z"));
    }

    @Test
    void isoTimestamps() {
        assertThat(OccurredAt.parse("2026-11-11T10:15:30.123456Z")).isEqualTo(AT);
        assertThat(OccurredAt.parse("2026-11-11T10:15:30.123456")).isEqualTo(AT);
        assertThat(OccurredAt.parse("2026-11-11 10:15:30.123456")).isEqualTo(AT);
    }

    @Test
    void missingOrUnreadableMeansUnknown() {
        assertThat(OccurredAt.parse(null)).isNull();
        assertThat(OccurredAt.parse(" ")).isNull();
        assertThat(OccurredAt.parse("yesterday")).isNull();
        assertThat(OccurredAt.parse("2026-11-11")).isNull();
    }
}
