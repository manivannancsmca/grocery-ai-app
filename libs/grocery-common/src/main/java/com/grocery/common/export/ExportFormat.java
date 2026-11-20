package com.grocery.common.export;

import com.grocery.common.web.BadRequestException;

import java.util.Locale;

/** The file formats an export is downloaded in: {@code ?format=csv|xlsx|pdf}. */
public enum ExportFormat {

    /** Comma-separated values, UTF-8 with a byte-order mark (so Excel shows ₹ and non-Latin names correctly). */
    CSV("csv", "text/csv;charset=UTF-8"),
    /** An Excel workbook, written row by row (never built in memory). */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    /** A printable A4 landscape table; at most {@code grocery.export.pdf-max-rows} rows. */
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** The {@code format} request parameter (case-insensitive); missing or unknown → 400 INVALID_PARAMETER. */
    public static ExportFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("INVALID_PARAMETER", "Choose an export format: csv, xlsx or pdf");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "csv" -> CSV;
            case "xlsx", "excel" -> XLSX;
            case "pdf" -> PDF;
            default -> throw new BadRequestException("INVALID_PARAMETER",
                    "Unknown export format '" + abbreviate(value.trim()) + "': use csv, xlsx or pdf");
        };
    }

    private static String abbreviate(String value) {
        return value.length() <= 20 ? value : value.substring(0, 20) + "…";
    }
}
