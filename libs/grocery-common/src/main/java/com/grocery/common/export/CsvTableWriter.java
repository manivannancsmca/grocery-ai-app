package com.grocery.common.export;

import com.grocery.common.export.ExportColumn.Kind;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV: comma-separated, CRLF line ends, quotes around values with a comma, quote or line break. Starts with a
 * UTF-8 byte-order mark, which Excel needs to read UTF-8 (₹, non-Latin names).
 *
 * <p>Spreadsheet formula injection (OWASP "CSV injection"): a text value starting with {@code = + - @}, a tab or a
 * carriage return would run as a formula when the file is opened in Excel. Such text gets a leading apostrophe, which
 * Excel shows as plain text. Number, money and date columns are written by this class, never from user text, so they
 * are left as they are (a negative amount stays a number).
 */
final class CsvTableWriter implements TableWriter {

    private static final String FORMULA_STARTS = "=+-@\t\r";

    private final Writer out;
    private final Layout layout;
    private final List<ExportColumn<?>> columns;

    CsvTableWriter(OutputStream out, Layout layout) {
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        this.layout = layout;
        this.columns = layout.columns();
    }

    @Override
    public void start() throws IOException {
        out.write('﻿');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(columns.get(i).header(), true));
        }
        out.write("\r\n");
    }

    @Override
    public void row(Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            Kind kind = columns.get(i).kind();
            out.write(escape(layout.values().text(kind, values[i]), kind == Kind.TEXT));
        }
        out.write("\r\n");
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void finish() throws IOException {
        out.flush();
    }

    static String escape(String value, boolean text) {
        String safe = text && !value.isEmpty() && FORMULA_STARTS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        return quote ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }
}
