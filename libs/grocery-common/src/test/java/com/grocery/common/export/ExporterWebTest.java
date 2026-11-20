package com.grocery.common.export;

import com.grocery.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link Exporter} behind a real embedded Tomcat, as a browser meets it: the download's headers and content, the
 * limits as ProblemDetail errors (nothing written yet), and a failure in the middle of the file, which must cut the
 * connection (the download fails) instead of ending the response normally (a truncated file that looks complete).
 */
// Closed after the class: an open context keeps Logback initialised, and later tests could not set their log format
@DirtiesContext
@SpringBootTest(classes = ExporterWebTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "grocery.security.public-paths=/export-test/**",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9/.well-known/jwks.json",
                "grocery.events.avro.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "grocery.export.batch-size=100",
                "grocery.export.pdf-max-rows=10",
                "grocery.export.max-per-user=1",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
class ExporterWebTest {

    static final CurrentUser USER = new CurrentUser(UUID.fromString("00000000-0000-7000-8000-000000000001"),
            "ops@grocery.local", "Ops Admin", Set.of("OPS_ADMIN"), null, null);

    record Line(String sku, BigDecimal price) {
    }

    static final ExportTable<Line> TABLE = ExportTable.<Line>named("price-list", "Price list")
            .text("SKU", Line::sku)
            .money("Price (INR)", Line::price)
            .build();

    /** Holds the next export in {@code count()} until released: a slot that stays taken. */
    static volatile CountDownLatch entered = new CountDownLatch(0);
    static volatile CountDownLatch release = new CountDownLatch(0);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Exports.class)
    static class App {
    }

    @RestController
    public static class Exports {

        private final Exporter exporter;

        public Exports(Exporter exporter) {
            this.exporter = exporter;
        }

        @GetMapping("/export-test/prices")
        public void prices(@RequestParam String format, @RequestParam(defaultValue = "3") int rows,
                           @RequestParam(defaultValue = "-1") int failAfter,
                           @RequestParam(defaultValue = "false") boolean hold, HttpServletResponse response) {
            exporter.write(response, USER, ExportFormat.parse(format), TABLE, generated(rows, failAfter, hold),
                    Exporter.filters("Category", "Dairy", "Store", null));
        }

        private static RowSource<Line> generated(int rows, int failAfter, boolean hold) {
            return new RowSource<>() {
                @Override
                public long count() {
                    if (hold) {
                        entered.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return rows;
                }

                @Override
                public void forEachBatch(int batchSize, BatchHandler<? super Line> handler) throws IOException {
                    for (int from = 0; from < rows; from += batchSize) {
                        if (failAfter >= 0 && from >= failAfter) {
                            throw new IllegalStateException("the database went away");
                        }
                        List<Line> batch = new ArrayList<>();
                        for (int i = from; i < Math.min(rows, from + batchSize); i++) {
                            batch.add(new Line("SKU-" + i + "-" + "x".repeat(40), BigDecimal.valueOf(i, 1)));
                        }
                        if (!handler.handle(batch)) {
                            return;
                        }
                    }
                }
            };
        }
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aCsvDownloadWithItsFileNameAndRowCount() throws Exception {
        HttpResponse<String> response = get("format=csv&rows=3");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("text/csv"));
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).matches("attachment; filename=\"price-list-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.csv\""));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue(Exporter.ROWS_HEADER)).contains("3");
        assertThat(response.body()).startsWith("﻿SKU,Price (INR)\r\nSKU-0-");
        assertThat(response.body().split("\r\n")).hasSize(4);
    }

    @Test
    void aLargeExportArrivesComplete() throws Exception {
        HttpResponse<String> response = get("format=csv&rows=25000");

        assertThat(response.statusCode()).isEqualTo(200);
        String[] lines = response.body().split("\r\n");
        assertThat(lines).hasSize(25_001);
        assertThat(lines[25_000]).startsWith("SKU-24999-").endsWith(",2499.90");
    }

    @Test
    void anUnknownFormatIsABadRequest() throws Exception {
        HttpResponse<String> response = get("format=doc");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errorCode\":\"INVALID_PARAMETER\"");
    }

    @Test
    void aPdfOverItsLimitIsRefusedBeforeAnythingIsWritten() throws Exception {
        HttpResponse<String> response = get("format=pdf&rows=11");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.body()).contains("\"errorCode\":\"EXPORT_TOO_LARGE\"", "\"limit\":10",
                "Choose CSV or Excel");
    }

    @Test
    void aPdfWithinItsLimit() throws Exception {
        HttpResponse<byte[]> response = http.send(request("format=pdf&rows=10"), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void aSecondExportOfTheSameUserIsRefusedWhileTheFirstRuns() throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> first = http.sendAsync(request("format=csv&hold=true"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> second = get("format=csv");
        release.countDown();

        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("\"errorCode\":\"EXPORT_BUSY\"");
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(get("format=csv").statusCode()).isEqualTo(200); // the slot is free again
    }

    @Test
    void aFailureBeforeTheFirstBatchIsAnOrdinaryErrorResponse() throws Exception {
        HttpResponse<String> response = get("format=csv&rows=50&failAfter=0");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.body()).contains("\"errorCode\":\"INTERNAL_ERROR\"");
    }

    @Test
    void aFailureInTheMiddleOfTheFileCutsTheConnection() {
        // ~50 KB are flushed in batches of 100 rows before the failure: the 200 and part of the file are already sent
        assertThatThrownBy(() -> get("format=csv&rows=5000&failAfter=1000")).isInstanceOf(IOException.class);
    }

    private HttpResponse<String> get(String query) throws IOException, InterruptedException {
        return http.send(request(query), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String query) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/export-test/prices?" + query)).build();
    }
}
