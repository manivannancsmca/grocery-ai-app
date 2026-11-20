package com.grocery.common.deadletter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The current state of one dead letter, as stored (value JSON) in the compacted topic
 * {@code _grocery.dead-letter-actions} under the key {@code <consumerGroup>|<dltTopic>|<partition>|<offset>}:
 * <pre>{"status":"REPLAY_FAILED","attempts":1,"lastError":"…","actor":"&lt;user id&gt;","at":"2026-11-13T10:00:00Z","reason":null}</pre>
 * {@code reason} (the operator's discard reason) is an addition to the contract's {@code {status, attempts, lastError,
 * actor, at}}; readers ignore unknown fields, so the value can grow.
 */
public record DeadLetterAction(DeadLetterStatus status, int attempts, String lastError, String actor, Instant at,
                               String reason) {

    /** The state of every dead letter without an entry. */
    public static final DeadLetterAction PENDING = new DeadLetterAction(DeadLetterStatus.PENDING, 0, null, null, null, null);

    /** A private mapper: the topic's format must not depend on a service's Jackson settings. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The JSON shape on the topic (timestamps as ISO-8601 strings). */
    record Json(String status, Integer attempts, String lastError, String actor, String at, String reason) {
    }

    public byte[] toJson() {
        return JSON.writeValueAsBytes(new Json(status.name(), attempts, lastError, actor,
                at == null ? null : at.toString(), reason));
    }

    /**
     * @throws IllegalArgumentException when the value is not a readable action
     */
    public static DeadLetterAction fromJson(byte[] value) {
        try {
            Json json = JSON.readValue(value, Json.class);
            if (json == null || json.status() == null) {
                throw new IllegalArgumentException("No status in " + new String(value, StandardCharsets.UTF_8));
            }
            return new DeadLetterAction(DeadLetterStatus.valueOf(json.status()),
                    json.attempts() == null ? 0 : json.attempts(), json.lastError(), json.actor(),
                    json.at() == null ? null : Instant.parse(json.at()), json.reason());
        } catch (JacksonException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Unreadable dead-letter action: " + e.getMessage(), e);
        }
    }
}
