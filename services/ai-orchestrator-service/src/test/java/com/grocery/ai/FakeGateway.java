package com.grocery.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A stand-in for the API gateway (and the business services behind it) on a local port: tests register answers per
 * method and path pattern, and every request is recorded with its headers and exact body bytes. Unregistered paths
 * answer 404 like a missing route.
 */
public final class FakeGateway {

    /** One request as received (header names lower-case). */
    public record Request(String method, String path, String query, Map<String, String> headers, byte[] body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return JSON.readTree(bodyText());
        }

        /** A query parameter's raw (decoded) value, or null. */
        public String param(String name) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (key.equals(name)) {
                    return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    public record Response(int status, String body, Duration delay) {

        public static Response json(int status, String body) {
            return new Response(status, body, Duration.ZERO);
        }

        public static Response problem(int status, String errorCode, String detail) {
            return json(status, "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\"" + detail
                    + "\",\"errorCode\":\"" + errorCode + "\"}");
        }

        public Response delayed(Duration by) {
            return new Response(status, body, by);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request);
    }

    private record Route(String method, Pattern path, Handler handler) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    /** Later registrations win over earlier ones for the same request. */
    public FakeGateway on(String method, String pathRegex, Handler handler) {
        routes.addFirst(new Route(method, Pattern.compile(pathRegex), handler));
        return this;
    }

    public FakeGateway json(String method, String pathRegex, int status, String body) {
        return on(method, pathRegex, request -> Response.json(status, body));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String method, String pathRegex) {
        Pattern pattern = Pattern.compile(pathRegex);
        List<Request> found = new ArrayList<>();
        for (Request request : requests) {
            if (request.method().equals(method) && pattern.matcher(request.path()).matches()) {
                found.add(request);
            }
        }
        return found;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.getFirst()));
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Collections.unmodifiableMap(headers), body);
        requests.add(request);
        Response response = Response.problem(404, "NO_ROUTE", "No fake route for " + request.method() + " "
                + request.path());
        for (Route route : routes) {
            if (route.method().equals(request.method()) && route.path().matcher(request.path()).matches()) {
                response = route.handler().handle(request);
                break;
            }
        }
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.status() >= 400 ? "application/problem+json"
                : "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException clientGone) {
            // the caller timed out and closed the connection
        } finally {
            exchange.close();
        }
    }
}
