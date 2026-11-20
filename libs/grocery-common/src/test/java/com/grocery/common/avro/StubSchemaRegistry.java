package com.grocery.common.avro;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.avro.Schema;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny in-process Schema Registry for tests that need real HTTP behaviour (status codes, outages): answers
 * {@code GET /schemas/ids/{id}} from the registered schemas, 404 (40403) for unknown ids, and the configured failure
 * status for the next N requests.
 */
public final class StubSchemaRegistry implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<Integer, String> schemas = new ConcurrentHashMap<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int failureStatus = 503;

    private StubSchemaRegistry(HttpServer server) {
        this.server = server;
    }

    public static StubSchemaRegistry start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubSchemaRegistry registry = new StubSchemaRegistry(server);
            server.createContext("/", registry::handle);
            server.start();
            return registry;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubSchemaRegistry register(int id, Schema schema) {
        schemas.put(id, schema.toString());
        return this;
    }

    /** The next {@code count} requests answer {@code status} (e.g. 503, 401). */
    public void failNext(int count, int status) {
        failureStatus = status;
        failuresLeft.set(count);
    }

    public int requests() {
        return requests.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            int status = failureStatus;
            respond(exchange, status, Map.of("error_code", status * 100 + 1, "message", "stub failure " + status));
            return;
        }
        if (path.startsWith("/schemas/ids/")) {
            String schema = schemas.get(Integer.parseInt(path.substring("/schemas/ids/".length()).replace("/", "")));
            if (schema != null) {
                respond(exchange, 200, Map.of("schema", schema));
                return;
            }
            respond(exchange, 404, Map.of("error_code", 40403, "message", "Schema not found"));
            return;
        }
        respond(exchange, 404, Map.of("error_code", 40401, "message", "Subject not found"));
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/vnd.schemaregistry.v1+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
