package com.grocery.notification.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Response bodies of contract §2 and §3. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One inbox entry; also the {@code data} of an SSE {@code notification} event, whose {@code id:} is {@link #id}.
     *
     * @param id       UUIDv7 = the SSE id; newer ids sort later
     * @param type     {@link com.grocery.notification.entity.NotificationType}
     * @param category ORDER | PAYMENT | STOCK | PRICING | ACCOUNT | APPROVAL
     * @param severity INFO | SUCCESS | WARNING | ERROR
     * @param link     an app route, or null
     * @param data     ids the client needs (orderId, orderNumber, productId, …)
     * @param read     read by the caller (read state is per user, also for role notifications)
     */
    public record NotificationView(UUID id, String type, String category, String severity, String title, String body,
                                   String link, JsonNode data, boolean read, Instant createdAt) {
    }

    /** An ephemeral "something changed — refetch" hint (SSE {@code signal} event). Never stored, never replayed. */
    public record Signal(String type, Map<String, Object> data, Instant at) {
    }

    /** SSE {@code session} event: the server closes the stream right after it. */
    public record Session(String reason) {
    }

    public record UnreadCount(long count) {
    }

    public record ReadAllResult(int updated) {
    }
}
