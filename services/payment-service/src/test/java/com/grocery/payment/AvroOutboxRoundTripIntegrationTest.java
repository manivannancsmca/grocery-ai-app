package com.grocery.payment;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every payment event the real business operations write to the outbox is also stored as Avro in
 * {@code payload_avro} (Confluent wire format, schema from grocery-event-schemas, subject
 * {@code payment.events-com.grocery.events.payment.<EventType>}), and decoding it the way every consumer does
 * (grocery-common's {@link AvroEventDeserializer}) gives back the JSON in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> PAYMENT_EVENTS = List.of("PaymentInitiated", "PaymentCaptured",
            "PaymentFailed", "PaymentExpired", "RefundCompleted", "RefundFailed");
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");

    private final JsonAvroConverter converter = new JsonAvroConverter();
    /** The application's own in-memory registry (same mock:// scope), as a consumer would reach it. */
    private final GroceryAvroProperties avro = avroProperties();
    private final SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avro);
    private final AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avro, converter);

    private record OutboxRow(String id, String topic, String aggregateId, String eventType, int eventVersion,
                             String payload, byte[] payloadAvro) {
    }

    private static GroceryAvroProperties avroProperties() {
        GroceryAvroProperties properties = new GroceryAvroProperties();
        properties.setSchemaRegistryUrl(SCHEMA_REGISTRY_URL);
        return properties;
    }

    private void adminRefund(UUID orderId, String key, String amount) throws Exception {
        mvc.perform(post("/api/admin/refunds").with(as(financeAdmin, "FINANCE_ADMIN"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"note\":\"Damaged\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void everyPaymentEventIsPublishedAsAvroThatDecodesToItsJsonPayload() throws Exception {
        // initiated -> a declined attempt -> captured -> a partial admin refund -> an admin refund the provider refuses
        UUID paid = payableOrder("125.00");
        String rzp = razorpayOrderId(paid);
        signedWebhook(paymentWebhook("payment.failed", "pay_decl" + UUID.randomUUID().toString().substring(0, 6),
                rzp, "failed", 12500), webhookEventId()).andExpect(status().isOk());
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "card", 12500)).andExpect(status().isOk());
        adminRefund(paid, "avro-1-" + paid, "25.50");
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 400, 1);
        adminRefund(paid, "avro-2-" + paid, "10.00");
        // initiated -> expired by the poller -> captured late (old Checkout tab) and refunded in full automatically
        UUID late = payableOrder("40.00");
        String lateRzp = razorpayOrderId(late);
        endWindow(late);
        expiryPoller.run();
        verifySigned(customer, lateRzp, RAZORPAY.addPayment(lateRzp, "captured", "upi", 4000))
                .andExpect(status().isOk());
        // initiated -> captured -> the order is cancelled: refunded for ORDER_CANCELLED
        UUID cancelled = payableOrder("70.00");
        String cancelledRzp = razorpayOrderId(cancelled);
        verifySigned(customer, cancelledRzp, RAZORPAY.addPayment(cancelledRzp, "captured", "card", 7000))
                .andExpect(status().isOk());
        orderListener.on(orderCancelled(newEventId(), cancelled, "CONFIRMED"));

        assertThat(eventTypes(paid)).containsExactly("PaymentInitiated", "PaymentFailed", "PaymentCaptured",
                "RefundCompleted", "RefundFailed");
        assertThat(eventTypes(late)).containsExactly("PaymentInitiated", "PaymentExpired", "PaymentCaptured",
                "RefundCompleted");
        assertThat(event(late, "PaymentCaptured").get("late").asBoolean()).isTrue();
        assertThat(event(late, "RefundCompleted").get("reason").asString()).isEqualTo("LATE_CAPTURE");
        assertThat(eventTypes(cancelled)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted");
        assertThat(event(cancelled, "RefundCompleted").get("reason").asString()).isEqualTo("ORDER_CANCELLED");

        // every row in the table: these orders' events, and whatever the other test classes wrote (both contexts)
        List<OutboxRow> all = outboxRows();
        List<String> orders = Stream.of(paid, late, cancelled).map(UUID::toString).toList();
        assertThat(all).filteredOn(row -> orders.contains(row.aggregateId()))
                .hasSize(12)
                .extracting(OutboxRow::eventType).containsAll(PAYMENT_EVENTS);
        all.forEach(this::assertAvroIsTheJsonPayload);
    }

    /**
     * Phase 12 (grocery-event-schemas 1.5.0): RefundCompleted and RefundFailed of a return carry its {@code returnId}
     * (reason RETURN) through Avro.
     */
    @Test
    void returnRefundsArePublishedAsAvroWithTheirReturnId() throws Exception {
        UUID paid = payableOrder("80.00");
        String rzp = razorpayOrderId(paid);
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "card", 8000)).andExpect(status().isOk());
        UUID refunded = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        orderListener.on(returnApproved(paid, refunded, "30.00"));
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 400, 1);
        orderListener.on(returnApproved(paid, refused, "20.00"));

        assertThat(eventTypes(paid)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted",
                "RefundFailed");
        List<OutboxRow> rows = outboxRows().stream().filter(row -> row.aggregateId().equals(paid.toString())).toList();
        rows.forEach(this::assertAvroIsTheJsonPayload);
        JsonNode completed = decoded(rows, "RefundCompleted");
        assertThat(completed.get("returnId").asString()).isEqualTo(refunded.toString());
        assertThat(completed.get("reason").asString()).isEqualTo("RETURN");
        assertThat(completed.get("amount").decimalValue()).isEqualByComparingTo("30.00");
        JsonNode failed = decoded(rows, "RefundFailed");
        assertThat(failed.get("returnId").asString()).isEqualTo(refused.toString());
        assertThat(failed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");
    }

    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> returnApproved(UUID orderId,
                                                                                         UUID returnId,
                                                                                         String amount) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("returnId", returnId.toString());
        payload.put("returnNumber", "RT-" + returnId.toString().substring(0, 8).toUpperCase());
        payload.put("orderId", orderId.toString());
        payload.put("refundAmount", new java.math.BigDecimal(amount));
        return orderEvent("ReturnApproved", newEventId(), orderId, payload);
    }

    private JsonNode decoded(List<OutboxRow> rows, String eventType) {
        OutboxRow row = rows.stream().filter(r -> r.eventType().equals(eventType)).findFirst().orElseThrow();
        return converter.readExact(deserializer.deserialize(row.topic(), row.payloadAvro()));
    }

    private List<OutboxRow> outboxRows() {
        return jdbc().sql("SELECT id, topic, aggregate_id, event_type, event_version, payload, payload_avro "
                        + "FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getInt("event_version"),
                        rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    private void assertAvroIsTheJsonPayload(OutboxRow row) {
        String what = row.eventType() + " " + row.id();
        assertThat(row.topic()).isEqualTo("payment.events");
        assertThat(row.eventType()).isIn(PAYMENT_EVENTS);
        assertThat(row.eventVersion()).isEqualTo(1);
        byte[] avro = row.payloadAvro();
        assertThat(avro).as("payload_avro of %s", what).isNotNull();
        assertThat(avro.length).as("length of %s", what).isGreaterThan(5);
        assertThat(avro[0]).as("magic byte of %s", what).isZero();

        // the schema id in the wire format is the one registered under <topic>-<record full name>
        int schemaId = ByteBuffer.wrap(avro, 1, 4).getInt();
        String recordName = "com.grocery.events.payment." + row.eventType();
        SchemaMetadata registered;
        try {
            registered = registry.getLatestSchemaMetadata("payment.events-" + recordName);
        } catch (Exception e) {
            throw new AssertionError("No subject payment.events-" + recordName + " in the registry", e);
        }
        assertThat(registered.getId()).as("schema id of %s", what).isEqualTo(schemaId);
        assertThat(new Schema.Parser().parse(registered.getSchema()).getFullName()).isEqualTo(recordName);

        String decoded = deserializer.deserialize(row.topic(), avro);
        assertThat(canonical(converter.readExact(decoded)))
                .as("decoded payload_avro of %s:%n%s%nvs payload:%n%s", what, decoded, row.payload())
                .isEqualTo(canonical(converter.readExact(row.payload())));
    }

    /**
     * Comparable form of a JSON tree: objects as sorted maps (MySQL's JSON column reorders keys), numbers by value
     * (MySQL and Avro decimals may differ in trailing zeros), ISO instants truncated to microseconds (Avro's
     * timestamp-micros), everything else as is.
     */
    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> fields = new TreeMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), canonical(field.getValue())));
            return fields;
        }
        if (node.isArray()) {
            return node.valueStream().map(AvroOutboxRoundTripIntegrationTest::canonical).toList();
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asString();
        if (ISO_INSTANT.matcher(text).matches()) {
            try {
                return Instant.parse(text).truncatedTo(ChronoUnit.MICROS);
            } catch (DateTimeParseException e) {
                return text;
            }
        }
        return text;
    }
}
