package com.grocery.common.avro;

/**
 * A JSON payload does not match its Avro schema (or an Avro value cannot be rendered as JSON). The message names the
 * record (event) and the path of the offending field, e.g.
 * {@code OrderCreated.items[1].unitPrice: 12.345 cannot be stored as decimal(10,2) without rounding}.
 */
public class AvroConversionException extends IllegalArgumentException {

    private final String record;
    private final String path;
    private final String detail;

    public AvroConversionException(String record, String path, String detail) {
        super(format(record, path, detail));
        this.record = record;
        this.path = path;
        this.detail = detail;
    }

    public AvroConversionException(String record, String path, String detail, Throwable cause) {
        super(format(record, path, detail), cause);
        this.record = record;
        this.path = path;
        this.detail = detail;
    }

    /** Wraps a conversion error with more context (e.g. topic and event version) in front of its message. */
    AvroConversionException(String context, AvroConversionException cause) {
        super(context + ": " + cause.getMessage(), cause);
        this.record = cause.record;
        this.path = cause.path;
        this.detail = cause.detail;
    }

    /** Simple name of the root record, i.e. the event type (e.g. {@code OrderCreated}). */
    public String record() {
        return record;
    }

    /** Path of the field inside the record, e.g. {@code items[1].unitPrice}; empty for the record itself. */
    public String path() {
        return path;
    }

    /** What is wrong, without record and path. */
    public String detail() {
        return detail;
    }

    private static String format(String record, String path, String detail) {
        return (path == null || path.isEmpty() ? record : record + "." + path) + ": " + detail;
    }
}
