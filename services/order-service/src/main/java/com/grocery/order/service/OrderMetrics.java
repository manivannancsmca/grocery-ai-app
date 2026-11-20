package com.grocery.order.service;

import com.grocery.order.entity.ReturnStatus;
import com.grocery.order.entity.StuckReason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * order-service's business metrics (docs/api/phase-13-observability-and-testing.md §1); grocery-common adds the common
 * tags ({@code service}, {@code env}, {@code instance}) and the timer's SLO buckets
 * ({@code management.metrics.distribution.slo[grocery.orders.placement]}).
 * <ul>
 *   <li>{@code grocery_orders_stuck{reason}} — gauge: open stuck-order flags whose order is still in the flagged status
 *       (what the admin list shows), one series per {@link StuckReason}, 0 when none;</li>
 *   <li>{@code grocery_returns_open{status}} — gauge: returns waiting for a decision, {@code REQUESTED} (the store) and
 *       {@code ESCALATED} (the admins);</li>
 *   <li>{@code grocery_orders_placement_seconds{outcome}} — timer of {@code POST /api/orders}: {@code PLACED},
 *       {@code REPLAYED} (an Idempotency-Key retry answered with the earlier order), {@code REJECTED} (4xx: blocked
 *       checkout, changed total or cart, coupon refused, validation), {@code UNAVAILABLE} (503: cart or pricing
 *       unreachable), {@code FAILED} (anything else).</li>
 * </ul>
 * The gauges never query on a scrape: two small aggregate queries (index-backed, GROUP BY) refresh them every
 * {@code grocery.order.metrics.refresh-interval} (30 s, at least 15 s) on a background thread of this instance; a
 * failed refresh keeps the last values. Every replica reports the same counts — aggregate with {@code max by (reason)}.
 */
@Component
public class OrderMetrics implements SmartLifecycle {

    public static final String STUCK = "grocery.orders.stuck";
    public static final String PLACEMENT = "grocery.orders.placement";
    public static final String RETURNS_OPEN = "grocery.returns.open";

    public static final String PLACED = "PLACED";
    public static final String REPLAYED = "REPLAYED";
    public static final String REJECTED = "REJECTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String FAILED = "FAILED";

    /** The return statuses that wait for a decision. */
    static final List<ReturnStatus> OPEN_RETURNS = List.of(ReturnStatus.REQUESTED, ReturnStatus.ESCALATED);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(OrderMetrics.class);

    private final JdbcClient jdbc;
    private final Duration refreshInterval;
    private final Map<StuckReason, AtomicLong> stuck = new EnumMap<>(StuckReason.class);
    private final Map<ReturnStatus, AtomicLong> openReturns = new EnumMap<>(ReturnStatus.class);
    private final Map<String, Timer> placementTimers = new HashMap<>();
    private volatile boolean lastRefreshFailed;
    private volatile ScheduledExecutorService scheduler;

    public OrderMetrics(DataSource dataSource, MeterRegistry registry,
                        @Value("${grocery.order.metrics.refresh-interval:30s}") Duration refreshInterval) {
        this.jdbc = JdbcClient.create(dataSource);
        this.refreshInterval = refreshInterval.compareTo(MIN_REFRESH_INTERVAL) < 0 ? MIN_REFRESH_INTERVAL
                : refreshInterval;
        for (StuckReason reason : StuckReason.values()) {
            AtomicLong value = new AtomicLong();
            stuck.put(reason, value);
            Gauge.builder(STUCK, value, AtomicLong::get)
                    .description("Open stuck-order flags (the watchdog's), by reason")
                    .tag("reason", reason.name())
                    .register(registry);
        }
        for (ReturnStatus status : OPEN_RETURNS) {
            AtomicLong value = new AtomicLong();
            openReturns.put(status, value);
            Gauge.builder(RETURNS_OPEN, value, AtomicLong::get)
                    .description("Returns waiting for a decision (REQUESTED: the store, ESCALATED: the admins)")
                    .tag("status", status.name())
                    .register(registry);
        }
        for (String outcome : List.of(PLACED, REPLAYED, REJECTED, UNAVAILABLE, FAILED)) {
            placementTimers.put(outcome, Timer.builder(PLACEMENT)
                    .description("Order placement (POST /api/orders), by outcome")
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    /** Records one placement ({@code outcome} one of the constants above). */
    public void placement(String outcome, long nanos) {
        placementTimers.getOrDefault(outcome, placementTimers.get(FAILED)).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Reads the open stuck flags and returns per status now (the background thread does this every interval). */
    public void refresh() {
        try {
            Map<String, Long> flags = counts("""
                    SELECT s.reason AS k, COUNT(*) AS n FROM stuck_order s
                    JOIN customer_order o ON o.id = s.order_id
                    WHERE s.resolved_at IS NULL AND o.status = s.status
                    GROUP BY s.reason""");
            Map<String, Long> returns = counts("""
                    SELECT status AS k, COUNT(*) AS n FROM order_return
                    WHERE status IN ('REQUESTED', 'ESCALATED')
                    GROUP BY status""");
            stuck.forEach((reason, value) -> value.set(flags.getOrDefault(reason.name(), 0L)));
            openReturns.forEach((status, value) -> value.set(returns.getOrDefault(status.name(), 0L)));
            if (lastRefreshFailed) {
                log.info("Order metrics refreshed again");
            }
            lastRefreshFailed = false;
        } catch (RuntimeException e) {
            if (!lastRefreshFailed) {
                log.warn("Order metrics could not be refreshed (the gauges keep their last values): {}", e.toString());
            }
            lastRefreshFailed = true;
        }
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql(sql).query((rs, row) -> counts.put(rs.getString("k"), rs.getLong("n"))).list();
        return counts;
    }

    Duration refreshInterval() {
        return refreshInterval;
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("order-metrics").daemon().factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::refresh, 0, refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = executor;
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
