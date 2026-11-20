package com.grocery.common.export;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Writes one export file, row by row, straight to the response: {@link #start}, {@link #row} for every row,
 * {@link #flush} after every batch (sends what is ready, keeps memory flat), {@link #finish} at the end. An
 * {@link IOException} means the output is gone (the client cancelled or lost the connection).
 */
interface TableWriter {

    void start() throws IOException;

    void row(Object[] values) throws IOException;

    void flush() throws IOException;

    void finish() throws IOException;

    /** What a writer knows about the file: title, column layout, the description line, the time zone. */
    record Layout(String title, List<ExportColumn<?>> columns, String description, CellValues values) {
    }

    static TableWriter create(ExportFormat format, OutputStream out, Layout layout) {
        return switch (format) {
            case CSV -> new CsvTableWriter(out, layout);
            case XLSX -> new XlsxTableWriter(out, layout);
            case PDF -> new PdfTableWriter(out, layout);
        };
    }
}
