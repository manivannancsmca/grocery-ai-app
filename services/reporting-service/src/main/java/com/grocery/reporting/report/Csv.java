package com.grocery.reporting.report;

import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV: a header row, then one row per record; fields separated by commas, rows by CRLF. A field containing a
 * comma, a double quote, CR or LF is enclosed in double quotes, its quotes doubled. {@code null} is an empty field,
 * numbers are written plainly ({@code 1234.50}, never {@code 1.2345E+3}).
 *
 * <p>Spreadsheet formula injection (OWASP "CSV injection"): a <b>text</b> field starting with {@code =}, {@code +},
 * {@code -}, {@code @}, TAB or CR — a store or item name typed by a vendor — is prefixed with a single quote so a
 * spreadsheet shows it as text instead of running it. Numbers (e.g. a negative net) are never prefixed.
 */
public final class Csv {

    public static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String CRLF = "\r\n";

    private Csv() {
    }

    public static String write(List<String> header, List<? extends List<?>> rows) {
        StringBuilder csv = new StringBuilder();
        row(csv, header);
        for (List<?> row : rows) {
            row(csv, row);
        }
        return csv.toString();
    }

    private static void row(StringBuilder csv, List<?> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(field(fields.get(i)));
        }
        csv.append(CRLF);
    }

    static String field(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
