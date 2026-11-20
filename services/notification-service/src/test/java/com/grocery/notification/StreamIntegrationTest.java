package com.grocery.notification;

import com.grocery.common.security.Roles;
import com.grocery.notification.SseClient.Block;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * {@code GET /api/notifications/stream} over real HTTP (contract §2): the JDK HttpClient reads the stream line by
 * line with real signed tokens.
 */
class StreamIntegrationTest extends AbstractIntegrationTest {

    @Test
    void theStreamStartsWithRetryAndCarriesTheContractHeadersAndHeartbeats() {
        UUID user = UUID.randomUUID();
        SseClient client = stream(token(user, Roles.CUSTOMER));

        assertThat(client.status()).isEqualTo(200);
        assertThat(client.header("Content-Type")).startsWith("text/event-stream");
        assertThat(client.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(client.header("X-Accel-Buffering")).isEqualTo("no");
        Block heartbeat = client.await(Block::isComment, WAIT);
        assertThat(heartbeat).as("a heartbeat (every second in the tests)").isNotNull();
        assertThat(heartbeat.comment()).isEqualTo("hb");
        assertThat(client.lines()).startsWith("retry: 3000", "");
        assertThat(client.lines()).contains(": hb");
        assertThat(registry.size(user)).isEqualTo(1);
    }

    @Test
    void noOrABadTokenIsUnauthorized() throws Exception {
        SseClient anonymous = track(SseClient.connect(port, null, null));
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.errorBody()).contains("UNAUTHENTICATED");

        SseClient garbage = track(SseClient.connect(port, "garbage", null));
        assertThat(garbage.status()).isEqualTo(401);
        assertThat(garbage.errorBody()).contains("INVALID_TOKEN");

        SseClient expired = track(SseClient.connect(port, TOKENS.token(UUID.randomUUID(),
                Instant.now().minus(Duration.ofMinutes(10)), Roles.CUSTOMER), null));
        assertThat(expired.status()).isEqualTo(401);
    }

    @Test
    void liveNotificationsAndSignalsReachTheCustomersStream() {
        UUID customer = UUID.randomUUID();
        SseClient client = openStream(customer, Roles.CUSTOMER);
        Order order = TestEvents.groceryOrder(customer, UUID.randomUUID());
        ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);

        dispatch(rejected);

        Block notification = client.next(WAIT);
        Stored stored = notificationOf(rejected, "ORDER_REJECTED");
        assertThat(notification.event()).isEqualTo("notification");
        assertThat(notification.id()).isEqualTo(stored.id().toString());
        assertThat(data(notification).get("id").asString()).isEqualTo(stored.id().toString());
        assertThat(data(notification).get("type").asString()).isEqualTo("ORDER_REJECTED");
        assertThat(data(notification).get("title").asString()).isEqualTo(stored.title());
        assertThat(data(notification).get("read").asBoolean(true)).isFalse();

        Block signal = client.next(WAIT);
        assertThat(signal.event()).isEqualTo("signal");
        assertThat(signal.id()).isNull();
        assertThat(data(signal).get("type").asString()).isEqualTo("ORDER_UPDATED");
        assertThat(data(signal).at("/data/orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(data(signal).at("/data/status").asString()).isEqualTo("REJECTED");
        assertThat(data(signal).at("/data/event").asString()).isEqualTo("OrderRejected");
        assertThat(data(signal).get("at").asString()).endsWith("Z");

        // ADMIN_ORDERS_CHANGED is not for customers
        Block marker = sendMarker(customer, client);
        assertThat(client.history()).filteredOn(b -> b.data() != null && b.data().contains("ADMIN_ORDERS_CHANGED"))
                .isEmpty();
        assertThat(marker).isNotNull();
    }

    @Test
    void lastEventIdReplaysOnlyNewerNotificationsOldestFirst() {
        UUID customer = UUID.randomUUID();
        List<UUID> ids = rejectedOrders(customer, 4);

        SseClient client = open(stream(token(customer, Roles.CUSTOMER), ids.get(1).toString()));

        List<Block> replayed = client.next(2, WAIT);
        assertThat(replayed).extracting(Block::event).containsOnly("notification");
        assertThat(replayed).extracting(Block::id).containsExactly(ids.get(2).toString(), ids.get(3).toString());
        assertThat(client.next(Duration.ofMillis(500))).as("no RESYNC, nothing else").isNull();

        // live events follow
        UUID live = rejectedOrders(customer, 1).getFirst();
        assertThat(client.await(b -> b.is("notification"), WAIT).id()).isEqualTo(live.toString());
    }

    @Test
    void liveEventsThatArriveDuringTheReplayAreSentAfterItWithoutDuplicates() {
        UUID customer = UUID.randomUUID();
        List<UUID> before = rejectedOrders(customer, 2);
        AtomicReference<UUID> committedBeforeTheQuery = new AtomicReference<>();
        AtomicReference<UUID> committedAfterTheQuery = new AtomicReference<>();
        doAnswer(invocation -> {
            // committed before the replay query: replayed AND published live (held, then skipped)
            committedBeforeTheQuery.set(rejectAndWaitForThePublish(customer));
            Object replay = invocation.callRealMethod();
            // committed after the query: only live, held until the replay is done
            committedAfterTheQuery.set(rejectAndWaitForThePublish(customer));
            return replay;
        }).when(notificationRepository).newerThan(eq(customer), any(), any(), anyInt());

        SseClient client = open(stream(token(customer, Roles.CUSTOMER), before.getFirst().toString()));
        UUID marker = rejectedOrders(customer, 1).getFirst();
        client.await(b -> marker.toString().equals(b.id()), WAIT);

        assertThat(notificationIds(client)).containsExactly(before.get(1).toString(),
                committedBeforeTheQuery.get().toString(), committedAfterTheQuery.get().toString(),
                marker.toString());
    }

    @Test
    void anUnknownOrMalformedLastEventIdGetsOnlyResync() {
        UUID customer = UUID.randomUUID();
        rejectedOrders(customer, 2);
        UUID somebodyElses = rejectedOrders(UUID.randomUUID(), 1).getFirst();

        for (String lastEventId : List.of("garbage", UUID.randomUUID().toString(), somebodyElses.toString())) {
            SseClient client = open(stream(token(customer, Roles.CUSTOMER), lastEventId));
            Block resync = client.next(WAIT);
            assertThat(resync.event()).as(lastEventId).isEqualTo("signal");
            assertThat(data(resync).get("type").asString()).isEqualTo("RESYNC");
            assertThat(data(resync).get("data").isEmpty()).isTrue();
            assertThat(resync.id()).isNull();
            assertThat(client.next(Duration.ofMillis(300))).as("no replay").isNull();
        }
    }

    @Test
    void aReplayLongerThanTheLimitEndsWithResync() {
        UUID customer = UUID.randomUUID();
        List<UUID> ids = rejectedOrders(customer, 8);   // the tests' replay-limit is 5

        SseClient client = open(stream(token(customer, Roles.CUSTOMER), ids.getFirst().toString()));

        List<Block> blocks = client.next(6, WAIT);
        assertThat(blocks.subList(0, 5)).extracting(Block::id).containsExactly(ids.get(1).toString(),
                ids.get(2).toString(), ids.get(3).toString(), ids.get(4).toString(), ids.get(5).toString());
        assertThat(blocks.get(5).event()).isEqualTo("signal");
        assertThat(data(blocks.get(5)).get("type").asString()).isEqualTo("RESYNC");
    }

    @Test
    void theStreamEndsWhenTheTokenExpires() {
        UUID user = UUID.randomUUID();
        SseClient client = open(stream(TOKENS.token(user, Instant.now().plusSeconds(3), Roles.CUSTOMER)));

        Block session = client.next(Duration.ofSeconds(8));
        assertThat(session.event()).isEqualTo("session");
        assertThat(data(session).get("reason").asString()).isEqualTo("TOKEN_EXPIRED");
        assertThat(client.awaitEnd(WAIT)).as("closed by the server").isTrue();
        waitUntil(() -> registry.size(user) == 0, WAIT);
    }

    @Test
    void aSixthStreamReplacesTheOldest() {
        UUID user = UUID.randomUUID();
        List<SseClient> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clients.add(openStream(user, Roles.CUSTOMER));
        }
        assertThat(registry.size(user)).isEqualTo(5);

        SseClient sixth = openStream(user, Roles.CUSTOMER);

        Block session = clients.getFirst().next(WAIT);
        assertThat(session.event()).isEqualTo("session");
        assertThat(data(session).get("reason").asString()).isEqualTo("REPLACED");
        assertThat(clients.getFirst().awaitEnd(WAIT)).isTrue();
        assertThat(registry.size(user)).isEqualTo(5);
        // the others (and the new one) stay open and receive events
        rejectedOrders(user, 1);
        for (SseClient other : List.of(clients.get(1), clients.get(4), sixth)) {
            assertThat(other.await(b -> b.is("notification"), WAIT)).isNotNull();
            assertThat(other.ended()).isFalse();
        }
    }

    @Test
    void aVendorSuspensionRevokesEveryStreamOfTheOwnerOnly() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        SseClient firstTab = openStream(owner, Roles.VENDOR);
        SseClient secondTab = openStream(owner, Roles.VENDOR);
        UUID bystander = UUID.randomUUID();
        SseClient other = openStream(bystander, Roles.VENDOR);

        dispatch(TestEvents.vendor("VendorSuspended", vendorId, owner, "Fresh Farms", "Expired licence"));

        for (SseClient tab : List.of(firstTab, secondTab)) {
            List<Block> blocks = tab.next(3, WAIT);
            assertThat(blocks).extracting(Block::event).containsExactly("notification", "signal", "session");
            assertThat(data(blocks.get(0)).get("type").asString()).isEqualTo("ACCOUNT_SUSPENDED");
            assertThat(data(blocks.get(0)).get("body").asString()).isEqualTo("Expired licence");
            assertThat(data(blocks.get(1)).get("type").asString()).isEqualTo("ACCOUNT_UPDATED");
            assertThat(data(blocks.get(2)).get("reason").asString()).isEqualTo("REVOKED");
            assertThat(blocks).extracting(Block::id).as("only notifications carry an SSE id")
                    .containsExactly(data(blocks.get(0)).get("id").asString(), null, null);
            assertThat(data(blocks.get(0)).at("/data/kind").asString()).isEqualTo("VENDOR");
            assertThat(tab.awaitEnd(WAIT)).isTrue();
        }
        waitUntil(() -> registry.size(owner) == 0, WAIT);
        assertThat(other.ended()).isFalse();
        assertThat(registry.size(bystander)).isEqualTo(1);
    }

    @Test
    void aUserSuspensionRevokesTheUsersStreams() {
        UUID user = UUID.randomUUID();
        SseClient client = openStream(user, Roles.CUSTOMER);

        dispatch(TestEvents.user("UserSuspended", user));

        Block session = client.await(b -> b.is("session"), WAIT);
        assertThat(data(session).get("reason").asString()).isEqualTo("REVOKED");
        assertThat(client.history()).noneMatch(b -> b.is("notification"));
        assertThat(client.awaitEnd(WAIT)).isTrue();
    }

    @Test
    void aVendorNeverReceivesAnotherVendorsNotificationsOrSignals() {
        UUID vendorA = UUID.randomUUID();
        UUID vendorB = UUID.randomUUID();
        UUID ownerA = knownVendor(vendorA, "A Mart");
        UUID ownerB = knownVendor(vendorB, "B Mart");
        SseClient a = openStream(ownerA, Roles.VENDOR);
        SseClient b = openStream(ownerB, Roles.VENDOR);
        UUID productB = UUID.randomUUID();
        Order orderB = TestEvents.groceryOrder(UUID.randomUUID(), vendorB);
        knownOrder(orderB);

        dispatch(TestEvents.lowStock(productB, vendorB, "B Rice", 1, 5));
        dispatch(TestEvents.stockAdjusted(productB, vendorB));
        dispatch(TestEvents.priceChange("PriceChangeApproved", UUID.randomUUID(), productB, vendorB, null));
        dispatch(TestEvents.priceChanged(productB, vendorB));
        dispatch(TestEvents.orderConfirmed(orderB, 2));
        // Phase 8: B's new-order alert and board signals come from its own store order events
        dispatch(TestEvents.storeOrderReceived(orderB, ownerB, 2, Instant.now().plusSeconds(300)));
        dispatch(TestEvents.storeOrderAccepted(orderB, 15));
        dispatch(TestEvents.availability(productB, vendorB, false));

        // B gets its own news
        assertThat(b.await(blk -> blk.data() != null && blk.data().contains("STOCK_CHANGED"), WAIT)).isNotNull();
        assertThat(b.history()).filteredOn(blk -> blk.is("notification")).extracting(blk -> data(blk)
                .get("type").asString()).contains("LOW_STOCK", "PRICE_CHANGE_APPROVED", "NEW_ORDER");
        // a marker for A, delivered after everything above
        UUID productA = UUID.randomUUID();
        dispatch(TestEvents.lowStock(productA, vendorA, "A Rice", 1, 5));
        assertThat(a.await(blk -> blk.data() != null && blk.data().contains(productA.toString()), WAIT))
                .isNotNull();

        List<Block> aboutB = a.history().stream()
                .filter(blk -> blk.data() != null && (blk.data().contains(productB.toString())
                        || blk.data().contains(orderB.orderId().toString()) || blk.data().contains(vendorB.toString())))
                .toList();
        assertThat(aboutB).as("only the public signals (in stock / price facts)").isNotEmpty()
                .allSatisfy(blk -> {
                    assertThat(blk.event()).isEqualTo("signal");
                    assertThat(data(blk).get("type").asString()).isIn("STOCK_CHANGED", "PRICE_CHANGED");
                });
    }

    @Test
    void anAdminHoldingTwoRolesReceivesARoleNotificationOnce() {
        UUID admin = UUID.randomUUID();
        SseClient client = openStream(admin, Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN);
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        dispatch(TestEvents.refundFailed(order.orderId(), new BigDecimal("50.00"), "ADMIN", "TIMEOUT"));

        Predicate<Block> aboutOrder = blk -> blk.data() != null && blk.data().contains(order.orderId().toString());
        assertThat(client.await(aboutOrder.and(blk -> blk.data().contains("ADMIN_ORDERS_CHANGED")), WAIT))
                .isNotNull();
        assertThat(client.history()).filteredOn(aboutOrder).extracting(Block::event)
                .containsExactly("notification", "signal");
    }

    @Test
    void aClientThatGoesAwayIsForgotten() {
        UUID user = UUID.randomUUID();
        SseClient client = openStream(user, Roles.CUSTOMER);
        assertThat(registry.size(user)).isEqualTo(1);
        assertThat(sseConnections()).as("grocery_sse_connections counts this replica's open streams")
                .isGreaterThanOrEqualTo(1);

        client.close();

        // the next heartbeat writes fail: the connection is removed (no leak), and the gauge follows
        waitUntil(() -> registry.size(user) == 0, Duration.ofSeconds(15));
        waitUntil(() -> sseConnections() == registry.size(), Duration.ofSeconds(15));
    }

    @Autowired
    private MeterRegistry meterRegistry;

    /** Phase 13: {@code grocery_sse_connections} (in memory: the registry's size at every read). */
    private double sseConnections() {
        return meterRegistry.get("grocery.sse.connections").gauge().value();
    }

    @Test
    void theSseConnectionsGaugeFollowsTheOpenStreams() {
        waitUntil(() -> sseConnections() == registry.size(), WAIT);
        UUID user = UUID.randomUUID();
        List<SseClient> tabs = new ArrayList<>();
        tabs.add(openStream(user, Roles.CUSTOMER));
        tabs.add(openStream(user, Roles.CUSTOMER));
        assertThat(registry.size(user)).isEqualTo(2);
        assertThat(sseConnections()).isGreaterThanOrEqualTo(2);
        tabs.forEach(SseClient::close);
        waitUntil(() -> registry.size(user) == 0, Duration.ofSeconds(15));
        waitUntil(() -> sseConnections() == registry.size(), Duration.ofSeconds(15));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * {@code count} ORDER_REJECTED notifications for the customer; their ids, oldest first. Returns once their
     * messages went through Redis, so a stream opened afterwards does not receive them live.
     */
    private List<UUID> rejectedOrders(UUID customer, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order order = TestEvents.groceryOrder(customer, UUID.randomUUID());
            ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);
            dispatch(rejected);
            ids.add(notificationOf(rejected, "ORDER_REJECTED").id());
            recorder.await(RealtimeRecorder.signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT);
        }
        RealtimeRecorder.sleep(100);   // the fan-out thread has handed them to the open streams
        return ids;
    }

    /** A new notification for the customer, once its Redis message reached this replica's fan-out. */
    private UUID rejectAndWaitForThePublish(UUID customer) {
        Order order = TestEvents.groceryOrder(customer, UUID.randomUUID());
        ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);
        dispatch(rejected);
        recorder.await(kind("notification").and(mentioning(order.orderId())), 1, WAIT);
        RealtimeRecorder.sleep(300);   // the fan-out thread hands it to the (replaying) connection
        return notificationOf(rejected, "ORDER_REJECTED").id();
    }

    /** A notification for the user; returns its block once the stream received it. */
    private Block sendMarker(UUID customer, SseClient client) {
        UUID id = rejectedOrders(customer, 1).getFirst();
        return client.await(b -> id.toString().equals(b.id()), WAIT);
    }

    private static List<String> notificationIds(SseClient client) {
        return client.history().stream().filter(b -> b.is("notification")).map(Block::id).toList();
    }
}
