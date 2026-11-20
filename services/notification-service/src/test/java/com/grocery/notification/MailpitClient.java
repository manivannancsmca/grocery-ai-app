package com.grocery.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Mailpit's HTTP API (v1), to see what the email channel really sent over SMTP: the messages it caught (From, To,
 * Subject) and each message's text and HTML parts. The same image the local compose runs.
 */
final class MailpitClient {

    /** The image tag the tests run (and compose should run). */
    static final String IMAGE = "axllent/mailpit:v1.32.0";
    static final int SMTP_PORT = 1025;
    static final int HTTP_PORT = 8025;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;

    MailpitClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** The summaries ({@code ID}, {@code From}, {@code To}, {@code Subject}) of every message sent to this address. */
    List<JsonNode> messagesTo(String address) {
        JsonNode page = get("/api/v1/messages?limit=1000");
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode message : page.path("messages")) {
            for (JsonNode to : message.path("To")) {
                if (address.equalsIgnoreCase(to.path("Address").asString())) {
                    found.add(message);
                    break;
                }
            }
        }
        return found;
    }

    /** One whole message: {@code From}, {@code To}, {@code Subject}, {@code Text}, {@code HTML}, … */
    JsonNode message(String id) {
        return get("/api/v1/message/" + id);
    }

    /** Waits until {@code count} messages to the address arrived; returns them (possibly fewer after the timeout). */
    List<JsonNode> await(String address, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<JsonNode> found = messagesTo(address);
        while (found.size() < count && System.nanoTime() < deadline) {
            RealtimeRecorder.sleep(100);
            found = messagesTo(address);
        }
        return found;
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit " + path + " → " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit is not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
