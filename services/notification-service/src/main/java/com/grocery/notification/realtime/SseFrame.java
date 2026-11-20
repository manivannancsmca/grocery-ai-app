package com.grocery.notification.realtime;

import java.util.UUID;

/**
 * One Server-Sent-Events block exactly as written to the stream (field, colon, space, value; a blank line ends it):
 * <pre>
 * retry: 3000
 *
 * event: notification
 * id: 0193…
 * data: {"id":"0193…","type":"ORDER_CONFIRMED",…}
 *
 * event: signal
 * data: {"type":"ORDER_UPDATED","data":{…},"at":"…"}
 *
 * event: session
 * data: {"reason":"TOKEN_EXPIRED"}
 *
 * : hb
 * </pre>
 *
 * @param notificationId the notification's id ({@code notification} frames only), to skip live duplicates of replayed ones
 * @param last           the server closes the stream after this frame ({@code session})
 */
public record SseFrame(String text, UUID notificationId, boolean last) {

    public static final SseFrame HEARTBEAT = new SseFrame(": hb\n\n", null, false);

    public static SseFrame retry(long millis) {
        return new SseFrame("retry: " + millis + "\n\n", null, false);
    }

    public static SseFrame notification(UUID id, String json) {
        return new SseFrame("event: notification\nid: " + id + "\n" + data(json) + "\n", id, false);
    }

    public static SseFrame signal(String json) {
        return new SseFrame("event: signal\n" + data(json) + "\n", null, false);
    }

    public static SseFrame session(String json) {
        return new SseFrame("event: session\n" + data(json) + "\n", null, true);
    }

    /** {@code {"reason":"…"}} for a session close decided on this replica. */
    public static SseFrame sessionReason(String reason) {
        return session("{\"reason\":\"" + reason + "\"}");
    }

    /** One {@code data:} line per line of the payload (compact JSON has none, but stay correct). */
    private static String data(String json) {
        StringBuilder lines = new StringBuilder();
        for (String line : json.split("\r\n|\r|\n", -1)) {
            lines.append("data: ").append(line).append('\n');
        }
        return lines.toString();
    }
}
