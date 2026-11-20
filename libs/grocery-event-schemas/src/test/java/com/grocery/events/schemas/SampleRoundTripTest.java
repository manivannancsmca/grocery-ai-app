package com.grocery.events.schemas;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Conversions;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Representative payloads, with the values the services' records carry (UUID, Instant, BigDecimal, nested lists,
 * nulls), encode to Avro binary with these schemas and decode back unchanged. The logical-type conversions are the
 * standard Avro ones the outbox converter relies on.
 */
class SampleRoundTripTest {

    private static final GenericData MODEL = new GenericData();

    static {
        MODEL.addLogicalTypeConversion(new Conversions.DecimalConversion());
        MODEL.addLogicalTypeConversion(new Conversions.UUIDConversion());
        MODEL.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());
        MODEL.addLogicalTypeConversion(new TimeConversions.DateConversion());
    }

    private static final UUID ORDER_ID = UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000002");
    private static final UUID VENDOR_ID = UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000003");
    private static final UUID PRODUCT_ID = UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000004");
    private static final Instant NOW = Instant.parse("2026-11-11T10:15:30.123456Z");

    @Test
    void orderCreatedWithNestedItemsDecimalsAndTimestamps() {
        Schema schema = schema("order.events/OrderCreated");
        Schema item = schema.getField("items").schema().getElementType();
        GenericRecord original = record(schema,
                "orderId", ORDER_ID,
                "orderNumber", "GA-7K3M9QX2",
                "orderType", "GROCERY",
                "customerId", CUSTOMER_ID,
                "storeId", VENDOR_ID,
                "storeName", null,
                "items", List.of(
                        record(item, "itemId", PRODUCT_ID, "type", "PRODUCT", "name", "Toned Milk 500 ml",
                                "quantity", 2, "unitPrice", new BigDecimal("29.00"), "lineTotal",
                                new BigDecimal("58.00")),
                        record(item, "itemId", UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000005"),
                                "type", "PRODUCT", "name", "Brown Bread", "quantity", 1,
                                "unitPrice", new BigDecimal("45.50"), "lineTotal", new BigDecimal("45.50"))),
                "subtotal", new BigDecimal("103.50"),
                "deliveryFee", new BigDecimal("25.00"),
                "total", new BigDecimal("128.50"),
                "currency", "INR",
                "cartUpdatedAt", NOW.minusSeconds(60),
                "paymentDueAt", NOW.plusSeconds(900),
                "createdAt", NOW,
                "couponCode", "WELCOME20",
                "discount", new BigDecimal("20.70"),
                "deliveryFeeDiscount", null,
                "couponName", "Welcome offer",
                "couponFundedBy", "PLATFORM");

        GenericRecord decoded = roundTrip(original);

        assertThat(MODEL.toString(decoded)).isEqualTo(MODEL.toString(original));
        assertThat(decoded.get("orderId")).isEqualTo(ORDER_ID);
        assertThat(decoded.get("total")).isEqualTo(new BigDecimal("128.50"));
        assertThat(decoded.get("createdAt")).isEqualTo(NOW);
        assertThat(decoded.get("storeName")).isNull();
        assertThat(decoded.get("couponFundedBy")).hasToString("PLATFORM");
        @SuppressWarnings("unchecked")
        List<GenericRecord> items = (List<GenericRecord>) decoded.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).get("unitPrice")).isEqualTo(new BigDecimal("45.50"));
        assertThat(items.get(1).get("name")).hasToString("Brown Bread");
    }

    @Test
    void restaurantListingWithNestedArraysOfRecordsAndAnOptionalRecord() {
        Schema schema = schema("restaurant.events/RestaurantListingUpdated");
        Schema address = nonNull(schema.getField("address").schema());
        Schema hours = schema.getField("openingHours").schema().getElementType();
        Schema category = schema.getField("menu").schema().getElementType();
        Schema item = category.getField("items").schema().getElementType();
        GenericRecord original = record(schema,
                "restaurantId", VENDOR_ID,
                "ownerUserId", CUSTOMER_ID,
                "status", "APPROVED",
                "name", "Spice Route",
                "description", null,
                "cuisines", List.of("NORTH_INDIAN", "CHINESE"),
                "address", record(address, "line1", "12 MG Road", "line2", null, "city", "Bengaluru",
                        "state", "Karnataka", "postalCode", "560001", "country", "IN", "latitude", 12.9756,
                        "longitude", 77.6067),
                "openingHours", List.of(record(hours, "dayOfWeek", "MONDAY", "opensAt", "09:00",
                        "closesAt", "01:30")),
                "acceptingOrders", true,
                "menu", List.of(
                        record(category, "categoryId", PRODUCT_ID, "name", "Starters", "description", null,
                                "sortOrder", 0, "items", List.of(
                                        record(item, "itemId", ORDER_ID, "name", "Paneer Tikka",
                                                "description", "Grilled cottage cheese",
                                                "price", new BigDecimal("249.00"), "foodType", "VEG",
                                                "imageUrl", null, "available", false, "sortOrder", 1))),
                        record(category, "categoryId", CUSTOMER_ID, "name", "Desserts", "description", null,
                                "sortOrder", 5, "items", List.of())),
                "listingVersion", 12L,
                "updatedAt", NOW);

        GenericRecord decoded = roundTrip(original);

        assertThat(MODEL.toString(decoded)).isEqualTo(MODEL.toString(original));
        GenericRecord decodedAddress = (GenericRecord) decoded.get("address");
        assertThat(decodedAddress.get("latitude")).isEqualTo(12.9756);
        assertThat(decodedAddress.get("line2")).isNull();
    }

    @Test
    void productUpsertedReusesItsIdNameRecordAndRescalesTheUnitValueExactly() {
        Schema schema = schema("catalog.events/ProductUpserted");
        Schema idName = nonNull(schema.getField("brand").schema());
        assertThat(nonNull(schema.getField("category").schema())).isEqualTo(idName);
        assertThat(schema.getField("categoryPath").schema().getElementType()).isEqualTo(idName);
        Schema attribute = schema.getField("attributes").schema().getElementType();
        GenericRecord dairy = record(idName, "id", UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000010"),
                "name", "Dairy");
        GenericRecord milk = record(idName, "id", UUID.fromString("0192f3a0-7c41-7d2e-9b1a-5e8f00000011"),
                "name", "Milk");
        GenericRecord original = record(schema,
                "productId", PRODUCT_ID,
                "vendorId", VENDOR_ID,
                "vendorName", "Fresh Mart",
                "vendorActive", true,
                "name", "Toned Milk",
                "description", null,
                "brand", null,
                "category", milk,
                "categoryPath", List.of(dairy, milk),
                "categoryActive", true,
                "unitValue", new BigDecimal("500"),          // the service strips trailing zeros
                "unitOfMeasure", "ML",
                "unitLabel", "500 ml",
                "sku", null,
                "barcode", "8901234567890",
                "foodType", "VEG",
                "tags", List.of("milk", "dairy"),
                "attributes", List.of(record(attribute, "name", "Fat", "value", "3%")),
                "imageUrls", List.of(),
                "status", "PUBLISHED",
                "publishedAt", NOW,
                "updatedAt", NOW,
                "productVersion", 7L);

        GenericRecord decoded = roundTrip(original);

        assertThat(decoded.get("unitValue")).isEqualTo(new BigDecimal("500.000"));
        assertThat(((BigDecimal) decoded.get("unitValue")).compareTo(new BigDecimal("500"))).isZero();
        assertThat(decoded.get("brand")).isNull();
        assertThat(((GenericRecord) decoded.get("category")).get("name")).hasToString("Milk");
        assertThat((List<?>) decoded.get("categoryPath")).hasSize(2);
        assertThat(decoded.get("productVersion")).isEqualTo(7L);
    }

    @Test
    void sharedLifecyclePayloadWithNulls() {
        Schema schema = schema("vendor.events/VendorRegistered");
        GenericRecord original = record(schema, "vendorId", VENDOR_ID, "ownerUserId", CUSTOMER_ID,
                "businessName", null, "reason", null, "actorUserId", CUSTOMER_ID);

        GenericRecord decoded = roundTrip(original);

        assertThat(MODEL.toString(decoded)).isEqualTo(MODEL.toString(original));
        assertThat(decoded.get("actorUserId")).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void aMissingRequiredValueCannotBeEncoded() {
        Schema schema = schema("payment.events/PaymentExpired");
        GenericRecord missingPaymentId = record(schema, "orderId", ORDER_ID, "paymentId", null);

        assertThatThrownBy(() -> encode(missingPaymentId)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("paymentId");
    }

    @Test
    void moneyWithMoreDecimalsThanTheScaleIsRejectedNotRounded() {
        Schema schema = schema("payment.events/RefundFailed");
        GenericRecord original = record(schema, "orderId", ORDER_ID, "paymentId", PRODUCT_ID, "refundId", VENDOR_ID,
                "amount", new BigDecimal("10.005"), "reason", "ADMIN", "errorCode", null, "returnId", null);

        assertThatThrownBy(() -> encode(original)).isInstanceOf(AvroRuntimeException.class)
                .hasStackTraceContaining("scale");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Schema schema(String id) {
        return EventSchemaFile.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow()
                .parse().rawSchema();
    }

    private static Schema nonNull(Schema union) {
        return union.getTypes().stream().filter(s -> s.getType() != Schema.Type.NULL).findFirst().orElseThrow();
    }

    /** A record of {@code schema} from name/value pairs; every field of the schema must be given. */
    private static GenericRecord record(Schema schema, Object... namesAndValues) {
        assertThat(namesAndValues.length / 2).as("values for every field of %s", schema.getName())
                .isEqualTo(schema.getFields().size());
        GenericData.Record record = new GenericData.Record(schema);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String name = (String) namesAndValues[i];
            assertThat(schema.getField(name)).as("field %s.%s", schema.getName(), name).isNotNull();
            record.put(name, namesAndValues[i + 1]);
        }
        return record;
    }

    private static byte[] encode(GenericRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(record.getSchema(), MODEL).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GenericRecord roundTrip(GenericRecord record) {
        Schema schema = record.getSchema();
        try {
            return new GenericDatumReader<GenericRecord>(schema, schema, MODEL)
                    .read(null, DecoderFactory.get().binaryDecoder(encode(record), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
