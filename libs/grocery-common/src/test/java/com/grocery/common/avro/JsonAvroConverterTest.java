package com.grocery.common.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonAvroConverterTest {

    private static final Schema ALL = new Schema.Parser().parse("""
            {"type":"record","name":"AllTypes","namespace":"com.grocery.events.test","fields":[
              {"name":"text","type":"string"},
              {"name":"id","type":{"type":"string","logicalType":"uuid"}},
              {"name":"at","type":{"type":"long","logicalType":"timestamp-micros"}},
              {"name":"atMillis","type":{"type":"long","logicalType":"timestamp-millis"}},
              {"name":"atNanos","type":{"type":"long","logicalType":"timestamp-nanos"}},
              {"name":"day","type":{"type":"int","logicalType":"date"}},
              {"name":"price","type":{"type":"bytes","logicalType":"decimal","precision":10,"scale":2}},
              {"name":"fixedPrice","type":{"type":"fixed","name":"Money","size":8,"logicalType":"decimal","precision":18,"scale":4}},
              {"name":"count","type":"int"},
              {"name":"big","type":"long"},
              {"name":"ratio","type":"float"},
              {"name":"score","type":"double"},
              {"name":"flag","type":"boolean"},
              {"name":"tags","type":{"type":"array","items":"string"}},
              {"name":"stock","type":{"type":"map","values":"int"}},
              {"name":"address","type":{"type":"record","name":"Address","fields":[
                {"name":"city","type":"string"},
                {"name":"lat","type":["null","double"],"default":null}]}},
              {"name":"note","type":["null","string"],"default":null},
              {"name":"choice","type":["null","int","long","string"],"default":null},
              {"name":"extra","type":{"type":"string","logicalType":"json"}},
              {"name":"status","type":{"type":"enum","name":"Status","symbols":["NEW","DONE"]}},
              {"name":"blob","type":"bytes"},
              {"name":"currency","type":"string","default":"INR"}
            ]}""");

    private static final String INPUT = """
            {"text":"hello","id":"0b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","at":"2026-11-11T10:05:00.123456Z",
             "atMillis":"2026-11-11T10:05:00.123Z","atNanos":"2026-11-11T10:05:00.123456789Z","day":"2026-11-12",
             "price":125,"fixedPrice":-1234.5,"count":3,"big":1099511627776,"ratio":1.5,"score":0.1,"flag":true,
             "tags":["a","b"],"stock":{"x":1,"y":2},"address":{"city":"Pune"},"note":null,"choice":"two",
             "extra":{"k":[1,2.50,{"n":null}],"s":"v"},"status":"DONE","blob":"AQID"}""";

    /** As a producer would have written it with Jackson: schema order, nulls explicit, decimals with their scale. */
    private static final String EXPECTED = "{\"text\":\"hello\",\"id\":\"0b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b\","
            + "\"at\":\"2026-11-11T10:05:00.123456Z\",\"atMillis\":\"2026-11-11T10:05:00.123Z\","
            + "\"atNanos\":\"2026-11-11T10:05:00.123456789Z\",\"day\":\"2026-11-12\",\"price\":125.00,"
            + "\"fixedPrice\":-1234.5000,\"count\":3,\"big\":1099511627776,\"ratio\":1.5,\"score\":0.1,\"flag\":true,"
            + "\"tags\":[\"a\",\"b\"],\"stock\":{\"x\":1,\"y\":2},\"address\":{\"city\":\"Pune\",\"lat\":null},"
            + "\"note\":null,\"choice\":\"two\",\"extra\":{\"k\":[1,2.50,{\"n\":null}],\"s\":\"v\"},\"status\":\"DONE\","
            + "\"blob\":\"AQID\",\"currency\":\"INR\"}";

    private final JsonAvroConverter converter = new JsonAvroConverter();

    private JsonNode json(String text) {
        return converter.readExact(text);
    }

    private GenericRecord all() {
        return converter.toRecord(json(INPUT), ALL);
    }

    /** INPUT with one field replaced. */
    private GenericRecord allWith(String field, String jsonValue) {
        JsonNode input = json(INPUT);
        ((tools.jackson.databind.node.ObjectNode) input).set(field, json(jsonValue));
        return converter.toRecord(input, ALL);
    }

    private String rendered(String field, String jsonValue) {
        return converter.toJson(allWith(field, jsonValue), ALL).get(field).toString();
    }

    @Test
    void roundTripsEveryTypeAndRendersExactlyLikeTheProducer() {
        GenericRecord record = all();

        assertThat(record.get("at")).isEqualTo(1_794_391_500_123_456L);       // epoch micros, raw Avro value
        assertThat(record.get("day")).isEqualTo(20_769);                       // epoch days
        assertThat(record.get("price")).isEqualTo(ByteBuffer.wrap(new BigDecimal("125.00").unscaledValue().toByteArray()));
        assertThat(record.get("currency")).hasToString("INR");                 // missing -> default
        assertThat(((GenericRecord) record.get("address")).get("lat")).isNull();
        assertThat(record.get("extra")).isEqualTo("{\"k\":[1,2.50,{\"n\":null}],\"s\":\"v\"}");
        assertThat(GenericData.get().validate(ALL, record)).isTrue();

        assertThat(converter.toJsonString(record, ALL)).isEqualTo(EXPECTED);
    }

    @Test
    void readsRawAvroValuesAsTheDatumReaderReturnsThem() throws IOException {
        GenericRecord record = all();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(ALL).write(record, encoder);
        encoder.flush();
        GenericRecord decoded = new GenericDatumReader<GenericRecord>(ALL)
                .read(null, DecoderFactory.get().binaryDecoder(out.toByteArray(), null));   // Utf8, ByteBuffer, HashMap

        assertThat(json(converter.toJsonString(decoded, ALL))).isEqualTo(json(EXPECTED));
    }

    @Test
    void decimalsAreRescaledExactlyAndRenderedWithTheirScale() {
        assertThat(rendered("price", "125")).isEqualTo("125.00");
        assertThat(rendered("price", "0.1")).isEqualTo("0.10");
        assertThat(rendered("price", "-0.01")).isEqualTo("-0.01");
        assertThat(rendered("price", "99999999.99")).isEqualTo("99999999.99");
        assertThat(rendered("fixedPrice", "-0.0001")).isEqualTo("-0.0001");
        assertThat(rendered("fixedPrice", "99999999999999.9999")).isEqualTo("99999999999999.9999");

        assertThatThrownBy(() -> allWith("price", "12.345"))
                .isInstanceOf(AvroConversionException.class)
                .hasMessage("AllTypes.price: 12.345 cannot be stored as decimal(10,2) without rounding");
        assertThatThrownBy(() -> allWith("price", "123456789.00"))
                .hasMessage("AllTypes.price: 123456789.00 has more digits than decimal(10,2) allows");
        assertThatThrownBy(() -> allWith("fixedPrice", "100000000000000"))
                .hasMessageContaining("has more digits than decimal(18,4)");
        assertThatThrownBy(() -> allWith("price", "\"125.00\""))
                .hasMessage("AllTypes.price: expected decimal(10,2) but got a string");
    }

    @Test
    void integersAreRangeCheckedAndMustBeWhole() {
        assertThat(rendered("count", "3.0")).isEqualTo("3");
        assertThat(rendered("big", "-9223372036854775808")).isEqualTo("-9223372036854775808");
        assertThatThrownBy(() -> allWith("count", "3.5"))
                .hasMessage("AllTypes.count: 3.5 is not a whole number, expected int");
        assertThatThrownBy(() -> allWith("count", "1099511627776"))
                .hasMessage("AllTypes.count: 1099511627776 is out of range for int");
        assertThatThrownBy(() -> allWith("big", "9223372036854775808"))
                .hasMessage("AllTypes.big: 9223372036854775808 is out of range for long");
        assertThatThrownBy(() -> allWith("count", "\"3\""))
                .hasMessage("AllTypes.count: expected int but got a string");
        assertThatThrownBy(() -> allWith("count", "true"))
                .hasMessage("AllTypes.count: expected int but got the boolean true");
    }

    @Test
    void floatingPointNumbersAreRangeChecked() {
        assertThat(rendered("ratio", "2")).isEqualTo("2.0");
        assertThat(rendered("score", "\"NaN\"")).isEqualTo("\"NaN\"");          // as Jackson writes NaN
        assertThatThrownBy(() -> allWith("ratio", "1e39"))
                .hasMessageContaining("is out of range for float");
        assertThatThrownBy(() -> allWith("score", "1e400"))
                .hasMessageContaining("is out of range for double");
        assertThatThrownBy(() -> allWith("flag", "1"))
                .hasMessage("AllTypes.flag: expected boolean but got the number 1");
    }

    @Test
    void uuidsAreValidatedAndKeptAsWritten() {
        assertThat(rendered("id", "\"0B0E5C3E-1C2D-4E5F-8A9B-0C1D2E3F4A5B\""))
                .isEqualTo("\"0B0E5C3E-1C2D-4E5F-8A9B-0C1D2E3F4A5B\"");
        assertThatThrownBy(() -> allWith("id", "\"order-1\""))
                .hasMessage("AllTypes.id: 'order-1' is not a UUID");
        assertThatThrownBy(() -> allWith("id", "\"1-1-1-1-1\""))
                .hasMessage("AllTypes.id: '1-1-1-1-1' is not a UUID");
    }

    @Test
    void timestampsAreIsoInstantsTruncatedToTheSchemaPrecision() {
        assertThat(rendered("at", "\"2026-11-11T10:05:00.123456789Z\"")).isEqualTo("\"2026-11-11T10:05:00.123456Z\"");
        assertThat(rendered("at", "\"2026-11-11T15:35:00+05:30\"")).isEqualTo("\"2026-11-11T10:05:00Z\"");
        assertThat(rendered("at", "\"1969-12-31T23:59:59.999999Z\"")).isEqualTo("\"1969-12-31T23:59:59.999999Z\"");
        assertThat(rendered("atMillis", "\"2026-11-11T10:05:00.123999Z\"")).isEqualTo("\"2026-11-11T10:05:00.123Z\"");
        assertThat(rendered("atNanos", "\"2026-11-11T10:05:00.000000001Z\"")).isEqualTo("\"2026-11-11T10:05:00.000000001Z\"");
        assertThatThrownBy(() -> allWith("at", "\"2026-11-11 10:05\""))
                .hasMessage("AllTypes.at: '2026-11-11 10:05' is not an ISO-8601 instant, expected long (timestamp-micros)");
        assertThatThrownBy(() -> allWith("at", "1762855500123456"))
                .hasMessage("AllTypes.at: expected long (timestamp-micros) but got the number 1762855500123456");
    }

    @Test
    void datesAreIsoLocalDates() {
        assertThat(rendered("day", "\"1970-01-01\"")).isEqualTo("\"1970-01-01\"");
        assertThat(rendered("day", "\"1969-12-31\"")).isEqualTo("\"1969-12-31\"");
        assertThatThrownBy(() -> allWith("day", "\"2026-02-30\"")).hasMessage("AllTypes.day: '2026-02-30' is not a date (yyyy-MM-dd)");
        assertThatThrownBy(() -> allWith("day", "\"12/11/2026\"")).hasMessageContaining("is not a date");
    }

    @Test
    void recordsAreStrictAndNameTheEventAndFieldPath() {
        Schema payment = new AvroEventSchemas().require("test.events", "PaymentCaptured", 1);
        String valid = """
                {"orderId":"0b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","paymentId":"1b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b",
                 "amount":245.00,"capturedAt":"2026-11-11T10:05:00Z","late":false,
                 "items":[{"productId":"2b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","quantity":2,"unitPrice":50.00},
                          {"productId":"3b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","quantity":1,"unitPrice":%s}]%s}""";

        GenericRecord record = converter.toRecord(json(valid.formatted("145.00", "")), payment);
        assertThat(record.get("currency")).hasToString("INR");
        assertThat(record.get("method")).isNull();
        assertThat(converter.toJson(record, payment).get("method").isNull()).isTrue();     // explicit null

        assertThatThrownBy(() -> converter.toRecord(json(valid.formatted("1.005", "")), payment))
                .hasMessage("PaymentCaptured.items[1].unitPrice: 1.005 cannot be stored as decimal(10,2) without rounding");
        assertThatThrownBy(() -> converter.toRecord(json(valid.formatted("1", ",\"discount\":5")), payment))
                .hasMessage("PaymentCaptured.discount: unknown field (not in com.grocery.events.test.PaymentCaptured)");
        assertThatThrownBy(() -> converter.toRecord(json(valid.formatted("1", "").replace("\"late\":false,", "")), payment))
                .hasMessage("PaymentCaptured.late: missing required field (no default in com.grocery.events.test.PaymentCaptured)");
        assertThatThrownBy(() -> converter.toRecord(json(valid.formatted("1", "").replace("\"late\":false", "\"late\":null")), payment))
                .hasMessage("PaymentCaptured.late: null is not allowed, expected boolean");
        assertThatThrownBy(() -> allWith("address", "{\"city\":\"Pune\",\"zip\":\"411001\"}"))
                .hasMessage("AllTypes.address.zip: unknown field (not in com.grocery.events.test.Address)");
        assertThatThrownBy(() -> allWith("stock", "{\"x\":1.5}"))
                .hasMessage("AllTypes.stock[\"x\"]: 1.5 is not a whole number, expected int");
        assertThatThrownBy(() -> allWith("tags", "[\"a\",2]"))
                .hasMessage("AllTypes.tags[1]: expected string but got the number 2");
    }

    @Test
    void unionsPickTheBranchMatchingTheJsonNode() {
        assertThat(allWith("choice", "null").get("choice")).isNull();
        assertThat(allWith("choice", "7").get("choice")).isEqualTo(7);
        assertThat(allWith("choice", "1099511627776").get("choice")).isEqualTo(1_099_511_627_776L);
        assertThat(allWith("choice", "\"seven\"").get("choice")).isEqualTo("seven");
        assertThat(rendered("choice", "7")).isEqualTo("7");
        assertThat(rendered("note", "\"hi\"")).isEqualTo("\"hi\"");

        assertThatThrownBy(() -> allWith("choice", "true"))
                .hasMessage("AllTypes.choice: expected union [null | int | long | string] but got the boolean true");
        assertThatThrownBy(() -> allWith("choice", "2.5"))
                .hasMessageStartingWith("AllTypes.choice: the number 2.5 matches no branch of union [null | int | long | string]");
        assertThatThrownBy(() -> allWith("note", "42"))
                .hasMessage("AllTypes.note: expected union [null | string] but got the number 42");
        assertThatThrownBy(() -> allWith("text", "null"))
                .hasMessage("AllTypes.text: null is not allowed, expected string");
    }

    @Test
    void jsonLogicalTypeCarriesAnySubtree() {
        assertThat(rendered("extra", "[1,\"two\",3.10]")).isEqualTo("[1,\"two\",3.10]");
        assertThat(rendered("extra", "\"just text\"")).isEqualTo("\"just text\"");
        assertThat(rendered("extra", "null")).isEqualTo("null");
        assertThat(allWith("extra", "null").get("extra")).isEqualTo("null");

        Schema payment = new AvroEventSchemas().require("test.events", "PaymentCaptured", 1);
        Schema attributes = payment.getField("attributes").schema();               // ["null", json]
        assertThat(converter.toAvro(json("null"), attributes)).isNull();
        assertThat(converter.toAvro(json("{\"gateway\":\"razorpay\"}"), attributes)).isEqualTo("{\"gateway\":\"razorpay\"}");

        GenericRecord broken = allWith("extra", "{}");
        broken.put("extra", "{not json");
        assertThatThrownBy(() -> converter.toJson(broken, ALL))
                .isInstanceOf(AvroConversionException.class)
                .hasMessageStartingWith("AllTypes.extra: not valid JSON text");
    }

    @Test
    void enumsBytesAndRecordsOnly() {
        assertThat(rendered("status", "\"NEW\"")).isEqualTo("\"NEW\"");
        assertThatThrownBy(() -> allWith("status", "\"CANCELLED\""))
                .hasMessage("AllTypes.status: 'CANCELLED' is not one of [NEW, DONE]");
        assertThat(rendered("blob", "\"\"")).isEqualTo("\"\"");
        assertThatThrownBy(() -> allWith("blob", "\"not*base64\""))
                .hasMessage("AllTypes.blob: not valid base64 for bytes");
        assertThatThrownBy(() -> converter.toRecord(json("\"x\""), Schema.create(Schema.Type.STRING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be records");
        assertThatThrownBy(() -> converter.toRecord(json("[1]"), ALL))
                .hasMessage("AllTypes: expected record AllTypes but got an array");
    }

    @Test
    void avroValuesThatDoNotFitTheSchemaAreReported() {
        GenericRecord record = all();
        record.put("count", "three");
        assertThatThrownBy(() -> converter.toJson(record, ALL))
                .isInstanceOf(AvroConversionException.class)
                .hasMessage("AllTypes.count: String is not a valid value for int");
        GenericRecord other = all();
        other.put("choice", true);
        assertThatThrownBy(() -> converter.toJson(other, ALL))
                .hasMessage("AllTypes.choice: value does not match union [null | int | long | string]");
    }
}
