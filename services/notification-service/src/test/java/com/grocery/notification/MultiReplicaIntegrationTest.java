package com.grocery.notification;

import com.grocery.common.security.Roles;
import com.grocery.notification.SseClient.Block;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.event.DispatchListener;
import com.grocery.notification.realtime.ConnectionRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two replicas of the service in one JVM: this test context (replica A) and a second application context (replica
 * B, another random port) on the same MySQL and Redis. Kafka delivers each event to ONE replica; Redis pub/sub brings
 * it to the streams of EVERY replica (contract §1, ADR-0005).
 */
class MultiReplicaIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anEventProcessedByOneReplicaReachesTheStreamsOnEveryReplica() {
        try (ConfigurableApplicationContext replicaB = startReplica("replica-b")) {
            int portB = replicaB.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            DispatchListener listenerB = replicaB.getBean(DispatchListener.class);
            ConnectionRegistry registryB = replicaB.getBean(ConnectionRegistry.class);
            UUID customer = UUID.randomUUID();

            // a stream on replica A, the event consumed by replica B
            SseClient onA = openStream(customer, Roles.CUSTOMER);
            ConsumerRecord<String, String> first = rejected(customer);
            listenerB.on(first);
            UUID firstId = notificationOf(first, "ORDER_REJECTED").id();
            Block received = onA.await(b -> b.is("notification"), WAIT);
            assertThat(received).isNotNull();
            assertThat(received.id()).isEqualTo(firstId.toString());
            assertThat(registry.size(customer)).isEqualTo(1);
            assertThat(registryB.size(customer)).isZero();

            // a second stream of the same user on replica B: both receive the next one, once each
            SseClient onB = open(track(SseClient.connect(portB, token(customer, Roles.CUSTOMER), null)));
            assertThat(registryB.size(customer)).isEqualTo(1);
            ConsumerRecord<String, String> second = rejected(customer);
            listenerB.on(second);
            String secondId = notificationOf(second, "ORDER_REJECTED").id().toString();
            assertThat(onA.await(b -> secondId.equals(b.id()), WAIT)).isNotNull();
            assertThat(onB.await(b -> secondId.equals(b.id()), WAIT)).isNotNull();

            // and the other way round: consumed by replica A, delivered by replica B too
            ConsumerRecord<String, String> third = rejected(customer);
            dispatch(third);
            String thirdId = notificationOf(third, "ORDER_REJECTED").id().toString();
            assertThat(onB.await(b -> thirdId.equals(b.id()), WAIT)).isNotNull();
            assertThat(onA.await(b -> thirdId.equals(b.id()), WAIT)).isNotNull();
            assertThat(notificationIds(onA)).containsExactly(firstId.toString(), secondId, thirdId);
            assertThat(notificationIds(onB)).containsExactly(secondId, thirdId);

            // a reconnect to the other replica replays from the shared inbox
            SseClient reconnect = open(track(SseClient.connect(portB, token(customer, Roles.CUSTOMER),
                    firstId.toString())));
            assertThat(reconnect.next(2, WAIT)).extracting(Block::id).containsExactly(secondId, thirdId);

            // a suspension consumed by B ends the user's streams on both replicas
            listenerB.on(TestEvents.user("UserSuspended", customer));
            for (SseClient client : List.of(onA, onB, reconnect)) {
                Block session = client.await(b -> b.is("session"), WAIT);
                assertThat(session).isNotNull();
                assertThat(data(session).get("reason").asString()).isEqualTo("REVOKED");
                assertThat(client.awaitEnd(WAIT)).isTrue();
            }
            waitUntil(() -> registry.size(customer) == 0 && registryB.size(customer) == 0, WAIT);
        }
    }

    @Test
    void stoppingAReplicaClosesItsStreams() {
        UUID customer = UUID.randomUUID();
        SseClient onB;
        try (ConfigurableApplicationContext replicaB = startReplica("replica-b2")) {
            int portB = replicaB.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            onB = open(track(SseClient.connect(portB, token(customer, Roles.CUSTOMER), null)));
        }
        assertThat(onB.awaitEnd(WAIT)).as("the client reconnects to another replica").isTrue();
    }

    private ConfigurableApplicationContext startReplica(String instanceId) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.kafka.listener.auto-startup=false",
                "--grocery.events.avro.schema-registry-url=" + SCHEMA_REGISTRY_URL,
                "--grocery.events.avro.auto-register-schemas=true",
                "--grocery.notification.heartbeat-interval=1s",
                "--grocery.notification.replay-limit=5",
                "--grocery.notification.instance-id=" + instanceId));
        infrastructure().forEach((name, value) -> args.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(NotificationServiceApplication.class).run(args.toArray(String[]::new));
    }

    private static ConsumerRecord<String, String> rejected(UUID customer) {
        Order order = TestEvents.groceryOrder(customer, UUID.randomUUID());
        return TestEvents.orderRejected(order);
    }

    private static List<String> notificationIds(SseClient client) {
        return client.history().stream().filter(b -> b.is("notification")).map(Block::id).toList();
    }
}
