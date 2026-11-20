package com.grocery.common.approval;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for {@code GET /.well-known/approval-jwks.json} on ai-orchestrator-service: serves the public keys it is
 * given, counts fetches, and can be switched "down" (503).
 */
final class StubJwks implements AutoCloseable {

    static final String PATH = "/.well-known/approval-jwks.json";

    private final HttpServer server;
    private final AtomicInteger fetches = new AtomicInteger();
    private volatile List<JWK> keys;
    private volatile boolean down;

    private StubJwks(HttpServer server, List<JWK> keys) {
        this.server = server;
        this.keys = keys;
    }

    static StubJwks start(JWK... keys) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubJwks stub = new StubJwks(server, List.of(keys));
            server.createContext(PATH, stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    void keys(JWK... published) {
        keys = List.of(published);
    }

    void down(boolean value) {
        down = value;
    }

    int fetches() {
        return fetches.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        fetches.incrementAndGet();
        byte[] body = down ? "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8)
                : new JWKSet(keys).toString(true).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(down ? 503 : 200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
