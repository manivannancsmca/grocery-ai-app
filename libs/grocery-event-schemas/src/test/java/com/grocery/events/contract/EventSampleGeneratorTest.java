package com.grocery.events.contract;

import com.grocery.events.contract.EventSampleGenerator.Variant;
import com.grocery.events.contract.EventSchemaCatalog.EventSchema;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sample generator produces, for EVERY packaged schema and both variants, a record the platform's serializer
 * accepts and that decodes back unchanged, with realistic values (UUIDs, documented enum values, decimals at their
 * scale and within their precision, current timestamps, INR) - so a consumer contract failure is a consumer problem,
 * never a bad sample.
 */
class EventSampleGeneratorTest {

    private static final EventSchemaCatalog CATALOG = EventSchemaCatalog.fromClasspath();

    static Stream<Arguments> everySchemaAndVariant() {
        return CATALOG.all().stream()
                .flatMap(schema -> Stream.of(Variant.values()).map(variant -> Arguments.of(schema, variant)));
    }

    @Test
    void theCatalogHasEverySchemaWithItsDocumentedKey() {
        assertThat(CATALOG.all()).hasSize(98);
        assertThat(CATALOG.topics()).contains("order.events", "delivery.location", "ai.events", "review.events",
                "customer.events");
        assertThat(CATALOG.all()).allSatisfy(schema -> assertThat(schema.keyField()).as(schema.id()).isNotNull());
        EventSchema orderCreated = CATALOG.get("order.events", "OrderCreated");
        assertThat(orderCreated.keyField()).isEqualTo("orderId");
        assertThat(orderCreated.version()).isEqualTo(1);
        assertThat(orderCreated.aggregateType()).isEqualTo("Order");
        assertThat(CATALOG.get("review.events", "RatingSummaryUpdated").keyField()).isEqualTo("targetId");
        assertThat(CATALOG.eventTypes("inventory.events")).contains("StockReserved", "ProductAvailabilityChanged");
        // 1.6.0: customer.events has its first event
        assertThat(CATALOG.eventTypes("customer.events")).containsExactly("CustomerProfileUpdated");
        assertThat(CATALOG.get("customer.events", "CustomerProfileUpdated").keyField()).isEqualTo("customerId");
        assertThat(CATALOG.get("identity.events", "PasswordResetRequested").keyField()).isEqualTo("userId");
    }

    @Test
    void theCatalogReadsTheSchemasFromAJarAsConsumerServicesSeeIt(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("grocery-event-schemas.jar");
        Path classes = Path.of(getClass().getClassLoader().getResource("avro/order.events/OrderCreated.avsc").toURI())
                .getParent().getParent().getParent();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes.resolve("avro"))) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String name = classes.relativize(file).toString().replace('\\', '/') + (Files.isDirectory(file) ? "/" : "");
                out.putNextEntry(new JarEntry(name));
                if (Files.isRegularFile(file)) {
                    out.write(Files.readAllBytes(file));
                }
                out.closeEntry();
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            EventSchemaCatalog fromJar = EventSchemaCatalog.load(loader);
            assertThat(fromJar.all()).extracting(EventSchema::id)
                    .containsExactlyElementsOf(CATALOG.all().stream().map(EventSchema::id).toList());
        }
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("everySchemaAndVariant")
    void everySchemaGetsAValidSampleThatSurvivesTheWireFormat(EventSchema schema, Variant variant) {
        SchemaRegistryClient registry = new MockSchemaRegistryClient();
        EventSample sample = EventSample.of(schema, variant);

        ConsumerRecord<String, byte[]> raw = sample.rawRecord(registry);

        assertThat(raw.value()[0]).as("Confluent magic byte").isZero();
        assertThat(raw.topic()).isEqualTo(schema.topic());
        assertThat(raw.key()).isEqualTo(sample.text(schema.keyField()));
        assertThat(header(raw, "eventType")).isEqualTo(schema.eventType());
        assertThat(header(raw, "eventVersion")).isEqualTo(String.valueOf(schema.version()));
        assertThat(header(raw, "eventId")).isEqualTo(sample.eventId());
        assertThat(header(raw, "traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(Long.parseLong(header(raw, "occurredAt"))).isGreaterThan(1_700_000_000_000_000L);

        KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(registry, Map.of(
                "schema.registry.url", "mock://unused", "avro.use.logical.type.converters", false,
                "specific.avro.reader", false));
        GenericRecord decoded = (GenericRecord) deserializer.deserialize(raw.topic(), raw.headers(), raw.value());
        assertThat(GenericData.get().toString(decoded)).isEqualTo(GenericData.get().toString(sample.record()));

        checkValues(schema.schema(), sample.record(), variant, schema.id());
    }

    /** Realistic values everywhere: ids, documented values, decimals, timestamps; optional fields per variant. */
    private static void checkValues(Schema schema, Object value, Variant variant, String path) {
        switch (schema.getType()) {
            case UNION -> {
                boolean nullable = schema.getTypes().stream().anyMatch(t -> t.getType() == Schema.Type.NULL);
                if (nullable && variant == Variant.MINIMAL) {
                    assertThat(value).as(path).isNull();
                } else {
                    assertThat(value).as(path).isNotNull();
                    Schema branch = schema.getTypes().get(GenericData.get().resolveUnion(schema, value));
                    checkValues(branch, value, variant, path);
                }
            }
            case RECORD -> {
                GenericRecord record = (GenericRecord) value;
                for (Schema.Field field : schema.getFields()) {
                    checkValues(field.schema(), record.get(field.pos()), variant, path + "." + field.name());
                    List<String> documented = EventSampleGenerator.documentedValues(field.doc());
                    if (!documented.isEmpty() && record.get(field.pos()) != null
                            && field.schema().getType() != Schema.Type.ARRAY) {
                        assertThat(record.get(field.pos()).toString()).as(path + "." + field.name() + " (documented)")
                                .isIn(documented);
                    }
                    if (field.name().equals("currency") && record.get(field.pos()) != null) {
                        assertThat(record.get(field.pos())).as(path + ".currency").hasToString("INR");
                    }
                }
            }
            case ARRAY -> {
                assertThat((Collection<?>) value).as(path).isNotEmpty();
                for (Object element : (Collection<?>) value) {
                    checkValues(schema.getElementType(), element, variant, path + "[]");
                }
            }
            case STRING -> {
                if (schema.getLogicalType() != null && "uuid".equals(schema.getLogicalType().getName())) {
                    assertThat(UUID.fromString(value.toString())).as(path).hasToString(value.toString());
                } else {
                    assertThat(value.toString()).as(path).isNotBlank();
                }
            }
            case BYTES -> {
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal decimal) {
                    BigDecimal amount = EventSampleGenerator.decimalOf((ByteBuffer) value, schema);
                    assertThat(amount.scale()).as(path).isEqualTo(decimal.getScale());
                    assertThat(amount.precision()).as(path).isLessThanOrEqualTo(decimal.getPrecision());
                    assertThat(amount.signum()).as(path).isNotZero();
                }
            }
            case LONG -> {
                if (schema.getLogicalType() instanceof LogicalTypes.TimestampMicros) {
                    Instant at = Instant.ofEpochSecond(0, (Long) value * 1_000);
                    assertThat(Duration.between(Instant.now(), at).abs()).as(path).isLessThan(Duration.ofHours(1));
                }
            }
            default -> {
            }
        }
    }

    @Test
    void valuesFollowTheFieldNamesAndDocs() {
        EventSample created = EventSample.of(CATALOG.get("order.events", "OrderCreated"), Variant.FULL);
        GenericRecord order = created.record();
        assertThat(order.get("orderType")).hasToString("GROCERY");
        assertThat(order.get("orderNumber")).hasToString("GA-7K3M9QX2");
        assertThat(order.get("currency")).hasToString("INR");
        assertThat(order.get("couponCode")).hasToString("WELCOME20");
        assertThat(order.get("couponName")).hasToString("Welcome offer");           // the doc's example
        assertThat(order.get("couponFundedBy")).hasToString("PLATFORM");          // the doc's first value
        Schema total = order.getSchema().getField("total").schema();
        assertThat(EventSampleGenerator.decimalOf((ByteBuffer) order.get("total"), total)).isEqualByComparingTo("249.00");
        assertThat((Long) order.get("paymentDueAt")).isGreaterThan((Long) order.get("createdAt"));
        @SuppressWarnings("unchecked")
        List<GenericRecord> items = (List<GenericRecord>) order.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("type")).hasToString("PRODUCT");
        assertThat(items.getFirst().get("quantity")).isEqualTo(2);

        GenericRecord rating = EventSample.of(CATALOG.get("review.events", "RatingSummaryUpdated"), Variant.FULL)
                .record();
        Schema average = rating.getSchema().getField("averageRating").schema();
        assertThat(EventSampleGenerator.decimalOf((ByteBuffer) rating.get("averageRating"), average))
                .isEqualByComparingTo("4.00");                                       // decimal(3,2)
        assertThat(List.copyOf((List<?>) rating.get("ratingDistribution"))).isEqualTo(List.of(0, 0, 1, 1, 1));

        GenericRecord user = EventSample.of(CATALOG.get("identity.events", "UserRegistered"), Variant.FULL).record();
        assertThat(user.get("email")).hasToString("asha.rao@example.com");
        assertThat(user.get("role")).hasToString("CUSTOMER");

        GenericRecord listing = EventSample.of(CATALOG.get("restaurant.events", "RestaurantListingUpdated"),
                Variant.FULL).record();
        assertThat(listing.get("status")).hasToString("APPROVED");                 // "APPROVED or SUSPENDED"
        assertThat(((GenericRecord) listing.get("address")).get("latitude")).isEqualTo(12.9716);

        GenericRecord receipt = EventSample.of(CATALOG.get("vendor.events", "GoodsReceived"), Variant.FULL).record();
        assertThat(receipt.get("purchaseOrderNumber")).hasToString("PO-7K3M9QX2");
    }

    @Test
    void oneIdPerFieldNameWithinASampleAndFreshIdsPerSample() {
        EventSchema stockReserved = CATALOG.get("inventory.events", "StockReserved");
        EventSample first = EventSample.of(stockReserved, Variant.FULL);
        EventSample second = EventSample.of(stockReserved, Variant.FULL);
        assertThat(first.text("orderId")).isNotEqualTo(second.text("orderId"));
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.key()).isEqualTo(first.text("orderId"));
    }

    @Test
    void overridesApplyByFieldNameAtAnyDepthInJavaTypes() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-14T10:15:30.123456Z");
        EventSample sample = EventSample.of(CATALOG.get("order.events", "OrderConfirmed"), Variant.MINIMAL, Map.of(
                "orderId", orderId, "orderType", "RESTAURANT", "itemId", productId, "confirmedAt", at,
                "total", new BigDecimal("359.5")));
        GenericRecord record = sample.record();
        assertThat(sample.uuid("orderId")).contains(orderId);
        assertThat(record.get("orderType")).hasToString("RESTAURANT");
        @SuppressWarnings("unchecked")
        List<GenericRecord> items = (List<GenericRecord>) record.get("items");
        assertThat(items.getFirst().get("itemId")).hasToString(productId.toString());
        assertThat(record.get("confirmedAt")).isEqualTo(1_794_651_330_123_456L);
        assertThat(EventSampleGenerator.decimalOf((ByteBuffer) record.get("total"),
                record.getSchema().getField("total").schema())).isEqualByComparingTo("359.50");

        assertThatThrownBy(() -> EventSample.of(CATALOG.get("order.events", "OrderConfirmed"), Variant.FULL,
                Map.of("total", new BigDecimal("1.005")))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nestedValuesAndAReadablePayloadForSeedingAndFailureMessages() {
        EventSample receipt = EventSample.of("vendor.events", "GoodsReceived", Map.of("quantity", 7));

        assertThat(receipt.texts("productId")).singleElement()
                .satisfies(id -> assertThat(UUID.fromString(id)).hasToString(id));
        assertThat(receipt.texts("quantity")).containsExactly("7");
        assertThat(receipt.describe())
                .startsWith("{\"vendorId\": \"" + receipt.text("vendorId") + "\"")
                .contains("\"purchaseOrderNumber\": \"PO-7K3M9QX2\"", "\"unitCost\": 180.00", "\"quantity\": 7")
                .containsPattern("\"receivedAt\": \"20\\d\\d-\\d\\d-\\d\\dT");
    }

    @Test
    void decimalsAreFittedToTheirPrecision() {
        assertThat(EventSampleGenerator.fit(new BigDecimal("249.00"),
                LogicalTypes.decimal(3, 2))).isEqualByComparingTo("2.49");
        assertThat(EventSampleGenerator.fit(new BigDecimal("249.00"),
                LogicalTypes.decimal(2, 2))).isEqualByComparingTo("0.25");
        assertThat(EventSampleGenerator.fit(new BigDecimal("500"),
                LogicalTypes.decimal(10, 3))).isEqualByComparingTo("500.000");
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
