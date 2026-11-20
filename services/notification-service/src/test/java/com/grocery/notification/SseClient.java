package com.grocery.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A minimal Server-Sent-Events client over the JDK HttpClient (HTTP/1.1), reading the real stream line by line the
 * way a browser's fetch-based client does: blocks end with a blank line; {@code field: value} with one optional
 * space after the colon; lines starting with {@code :} are comments.
 */
final class SseClient implements AutoCloseable {

    /** One block of the stream: an event, a retry instruction or a comment (heartbeat). */
    record Block(String event, String id, String data, String comment, String retry) {

        boolean isComment() {
            return comment != null && event == null && data == null && retry == null;
        }

        boolean is(String name) {
            return name.equals(event);
        }
    }

    private static final Block END = new Block(null, null, null, null, null);

    private final HttpClient client;
    private final HttpResponse<InputStream> response;
    private final BlockingQueue<Block> queue = new LinkedBlockingQueue<>();
    private final List<Block> history = new CopyOnWriteArrayList<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private volatile boolean ended;

    private SseClient(HttpClient client, HttpResponse<InputStream> response) {
        this.client = client;
        this.response = response;
        if (response.statusCode() == 200) {
            Thread.ofVirtual().name("sse-test-reader").start(this::read);
        } else {
            ended = true;
        }
    }

    static SseClient connect(int port, String token, String lastEventId) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/notifications/stream"))
                .header("Accept", "text/event-stream")
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        try {
            return new SseClient(client, client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    int status() {
        return response.statusCode();
    }

    String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /** The body of a non-200 answer. */
    String errorBody() throws IOException {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every raw line received so far, blank lines included. */
    List<String> lines() {
        return List.copyOf(lines);
    }

    /** Every block received so far. */
    List<Block> history() {
        return List.copyOf(history);
    }

    /** The next block that is not a heartbeat comment, or null on timeout / end of stream. */
    Block next(Duration timeout) {
        return await(block -> !block.isComment(), timeout);
    }

    /** The next block matching the predicate (skipping the others), or null on timeout / end of stream. */
    Block await(Predicate<Block> predicate, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                Block block = queue.poll(left, TimeUnit.NANOSECONDS);
                if (block == null || block == END) {
                    if (block == END) {
                        queue.add(END);
                    }
                    return null;
                }
                if (predicate.test(block)) {
                    return block;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The next {@code count} non-comment blocks (fewer on timeout). */
    List<Block> next(int count, Duration timeout) {
        List<Block> blocks = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (blocks.size() < count) {
            long left = deadline - System.nanoTime();
            Block block = left <= 0 ? null : next(Duration.ofNanos(left));
            if (block == null) {
                break;
            }
            blocks.add(block);
        }
        return blocks;
    }

    /** Waits until the server closed the stream. */
    boolean awaitEnd(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!ended && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ended;
    }

    boolean ended() {
        return ended;
    }

    /** Drops the connection like a closed browser tab. */
    @Override
    public void close() {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // closing anyway
        }
        client.shutdownNow();
    }

    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String event = null;
            String id = null;
            StringBuilder data = null;
            String comment = null;
            String retry = null;
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (line.isEmpty()) {
                    if (event != null || id != null || data != null || comment != null || retry != null) {
                        Block block = new Block(event, id, data == null ? null : data.toString(), comment, retry);
                        history.add(block);
                        queue.add(block);
                    }
                    event = null;
                    id = null;
                    data = null;
                    comment = null;
                    retry = null;
                    continue;
                }
                if (line.startsWith(":")) {
                    comment = value(line.substring(1));
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : value(line.substring(colon + 1));
                switch (field) {
                    case "event" -> event = value;
                    case "id" -> id = value;
                    case "retry" -> retry = value;
                    case "data" -> data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                    default -> {
                        // ignored, as the SSE specification says
                    }
                }
            }
        } catch (IOException e) {
            // closed by the test or the server
        } finally {
            ended = true;
            queue.add(END);
        }
    }

    private static String value(String raw) {
        return raw.startsWith(" ") ? raw.substring(1) : raw;
    }
}
