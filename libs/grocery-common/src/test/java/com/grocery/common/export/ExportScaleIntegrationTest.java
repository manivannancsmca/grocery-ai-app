package com.grocery.common.export;

import com.grocery.common.export.ExportSort.Key;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in scale check (not part of the normal build): a million rows exported through the real pipeline — keyset
 * batches from MySQL, mapped, written as CSV and as Excel — while the heap is sampled. Run it with
 * <pre>
 * mvn test -Dtest=ExportScaleIntegrationTest -Dexport.scale=true -DargLine=-Xmx384m
 * </pre>
 * It prints the time and the peak heap of each format; the heap stays far below the cap however many rows there are.
 */
@EnabledIfSystemProperty(named = "export.scale", matches = "true")
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = ExportScaleIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "grocery.events.avro.enabled=false",
                "grocery.housekeeping.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
class ExportScaleIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    static final int ROWS = 1_000_000;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    @Entity(name = "ScaleRow")
    // Like the services' lists: the default order (newest first) is served by an index, so each batch is a range read.
    @Table(name = "export_scale_row", indexes = @Index(name = "idx_scale_created", columnList = "createdAt"))
    public static class ScaleRow {
        @Id
        private UUID id;
        @Column(nullable = false, length = 20)
        private String orderNumber;
        @Column(nullable = false, length = 80)
        private String customer;
        @Column(nullable = false, length = 20)
        private String status;
        @Column(nullable = false, precision = 12, scale = 2)
        private BigDecimal total;
        @Column(nullable = false)
        private Instant createdAt;

        protected ScaleRow() {
        }

        public UUID getId() {
            return id;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    record Row(String orderNumber, String customer, String status, BigDecimal total, Instant createdAt) {
    }

    static final ExportTable<Row> TABLE = ExportTable.<Row>named("scale", "Scale")
            .text("Order number", Row::orderNumber)
            .text("Customer", Row::customer)
            .label("Status", Row::status)
            .money("Total (INR)", Row::total)
            .dateTime("Placed", Row::createdAt)
            .build();

    @Autowired
    ExportQueries queries;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aMillionRowsStreamWithFlatMemory() throws Exception {
        load();
        RowSource<Row> rows = queries.jpql(ScaleRow.class, "r", "from ScaleRow r")
                .orderBy(Key.desc("r.createdAt", ScaleRow::getCreatedAt), Key.desc("r.id", ScaleRow::getId))
                .rows(r -> new Row(r.orderNumber, r.customer, r.status, r.total, r.createdAt));
        assertThat(rows.count()).isEqualTo(ROWS);

        for (ExportFormat format : List.of(ExportFormat.CSV, ExportFormat.XLSX)) {
            CountingOutput out = new CountingOutput();
            HeapWatch heap = HeapWatch.start();
            long started = System.nanoTime();
            TableWriter writer = TableWriter.create(format, out, new TableWriter.Layout("Scale",
                    List.copyOf(TABLE.columns()), "scale", new CellValues(ZoneId.of("Asia/Kolkata"))));
            AtomicLong written = new AtomicLong();
            writer.start();
            rows.forEachBatch(1_000, batch -> {
                for (Row row : batch) {
                    writer.row(TABLE.values(row));
                    written.incrementAndGet();
                }
                writer.flush();
                return true;
            });
            writer.finish();
            long millis = (System.nanoTime() - started) / 1_000_000;
            long peak = heap.stop();
            System.out.printf("SCALE %s: %,d rows, %,d bytes in %,d ms; peak heap %,d MB of max %,d MB%n", format,
                    written.get(), out.bytes, millis, peak / (1024 * 1024),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024));
            assertThat(written.get()).isEqualTo(ROWS);
        }
    }

    private void load() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        int perStatement = 2_000;
        for (int from = 0; from < ROWS; from += perStatement) {
            StringBuilder sql = new StringBuilder("insert into export_scale_row "
                    + "(id, order_number, customer, status, total, created_at) values ");
            List<Object> args = new ArrayList<>();
            for (int i = from; i < from + perStatement; i++) {
                sql.append(i == from ? "" : ",").append("(?,?,?,?,?,?)");
                UUID id = UUID.randomUUID();
                args.add(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits()).array());
                args.add("GA-" + Integer.toString(i, 36).toUpperCase());
                args.add("Customer " + (i % 5_000));
                args.add(i % 3 == 0 ? "OUT_FOR_DELIVERY" : "DELIVERED");
                args.add(BigDecimal.valueOf(10_000 + i % 90_000, 2));
                args.add(java.sql.Timestamp.from(start.plusSeconds(i / 3)));
            }
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    /** Counts what would go to the client. */
    static final class CountingOutput extends OutputStream {
        long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }

    /** Samples the used heap every 50 ms (after a GC at the start) and keeps the peak. */
    static final class HeapWatch {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private volatile boolean running = true;
        private volatile long peak;
        private Thread thread;

        static HeapWatch start() {
            System.gc();
            HeapWatch watch = new HeapWatch();
            watch.thread = Thread.ofPlatform().daemon().start(() -> {
                while (watch.running) {
                    watch.peak = Math.max(watch.peak, watch.memory.getHeapMemoryUsage().getUsed());
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            return watch;
        }

        long stop() throws InterruptedException {
            running = false;
            thread.join();
            return peak;
        }
    }

}
