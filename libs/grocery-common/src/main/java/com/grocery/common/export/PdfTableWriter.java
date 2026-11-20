package com.grocery.common.export;

import com.grocery.common.export.ExportColumn.Kind;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/**
 * A printable table: A4 landscape, the title and a description line (row count, filters, time zone) on top, the
 * column headers repeated on every page, page numbers at the bottom. Rows are handed to the document every
 * {@value #ROWS_PER_CHUNK} rows (OpenPDF's "large table" mode): finished pages go to the output and leave memory.
 *
 * <p>The PDF uses the standard Helvetica font (nothing embedded, small files). It has Latin letters only: a character
 * it cannot draw is shown as "?". CSV and Excel keep every character.
 */
final class PdfTableWriter implements TableWriter {

    private static final int ROWS_PER_CHUNK = 100;
    // The app's colours, the same as the Excel file: the column headers are white bold text on the brand green.
    private static final Color BRAND = new Color(6, 95, 70);
    private static final Color HEADER_SEPARATOR = new Color(63, 143, 114);
    private static final Color STRIPE = new Color(238, 246, 241);
    private static final Color RULE = new Color(214, 226, 219);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, BRAND);
    private static final Font NOTE = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(71, 85, 105));
    private static final Font HEADER = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);
    private static final Font CELL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(30, 41, 59));
    private static final Font FOOTER = new Font(Font.HELVETICA, 7, Font.NORMAL, new Color(100, 116, 139));

    private final OutputStream out;
    private final Layout layout;
    private final List<ExportColumn<?>> columns;
    private final CharsetEncoder latin = Charset.forName("windows-1252").newEncoder();
    private final DecimalFormat money = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    private Document document;
    private PdfPTable table;
    private int pending;
    private long rows;

    PdfTableWriter(OutputStream out, Layout layout) {
        this.out = out;
        this.layout = layout;
        this.columns = layout.columns();
        money.setRoundingMode(RoundingMode.HALF_UP);
    }

    @Override
    public void start() throws IOException {
        document = new Document(PageSize.A4.rotate(), 28, 28, 32, 40);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setCloseStream(false); // the response stream belongs to the Exporter
        String footer = latin(layout.title());
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter pdf, Document page) {
                float y = page.bottom() - 18;
                ColumnText.showTextAligned(pdf.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase(footer, FOOTER), page.left(), y, 0);
                ColumnText.showTextAligned(pdf.getDirectContent(), Element.ALIGN_RIGHT,
                        new Phrase("Page " + pdf.getPageNumber(), FOOTER), page.right(), y, 0);
            }
        });
        document.addTitle(latin(layout.title()));
        document.addCreator("FreshCart");
        document.open();

        document.add(new Paragraph(latin(layout.title()), TITLE));
        Paragraph description = new Paragraph(latin(layout.description()), NOTE);
        description.setSpacingAfter(10);
        document.add(description);

        float[] widths = new float[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = width(columns.get(i));
        }
        table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setComplete(false);
        for (ExportColumn<?> column : columns) {
            PdfPCell cell = cell(column.header(), HEADER, column.kind());
            cell.setBackgroundColor(BRAND);
            cell.setBorderColor(HEADER_SEPARATOR);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setPaddingTop(5);
            cell.setPaddingBottom(6);
            table.addCell(cell);
        }
    }

    @Override
    public void row(Object[] values) throws IOException {
        boolean striped = rows++ % 2 == 1;
        for (int i = 0; i < values.length; i++) {
            Kind kind = columns.get(i).kind();
            PdfPCell cell = cell(text(kind, values[i]), CELL, kind);
            cell.setBorderColor(RULE);
            if (striped) {
                cell.setBackgroundColor(STRIPE);
            }
            table.addCell(cell);
        }
        if (++pending == ROWS_PER_CHUNK) {
            document.add(table); // lays out these rows, sends the full pages, drops them from the table
            pending = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void finish() throws IOException {
        if (rows == 0) {
            table.setHeaderRows(0); // a table of header rows only is not drawn: show the headers as a plain row
        }
        table.setComplete(true);
        document.add(table);
        if (rows == 0) {
            Paragraph none = new Paragraph("No records match these filters.", NOTE);
            none.setSpacingBefore(8);
            document.add(none);
        }
        document.close();
        out.flush();
    }

    private String text(Kind kind, Object value) {
        if (kind == Kind.MONEY && value instanceof Number number) {
            return money.format(CellValues.decimal(number));
        }
        return layout.values().text(kind, value);
    }

    private PdfPCell cell(String text, Font font, Kind kind) {
        PdfPCell cell = new PdfPCell(new Phrase(latin(text), font));
        cell.setPadding(3.5f);
        cell.setPaddingBottom(4.5f);
        cell.setHorizontalAlignment(numeric(kind) ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return cell;
    }

    private static boolean numeric(Kind kind) {
        return kind == Kind.INTEGER || kind == Kind.DECIMAL || kind == Kind.MONEY;
    }

    /** A column's share of the page: by the kind of value, and wide enough for the header's longest word. */
    private static float width(ExportColumn<?> column) {
        float byKind = switch (column.kind()) {
            case TEXT -> 3f;
            case DATE_TIME -> 2.2f;
            case DATE -> 1.6f;
            case MONEY, DECIMAL -> 1.6f;
            case INTEGER -> 1.2f;
            case YES_NO -> 1f;
        };
        int longestWord = 0;
        for (String word : column.header().split("\\s+")) {
            longestWord = Math.max(longestWord, word.length());
        }
        return Math.max(byKind, longestWord * 0.24f);
    }

    /** Helvetica draws Windows-1252 only: anything else becomes "?" (visible, rather than silently dropped). */
    private String latin(String text) {
        if (text == null || text.isEmpty() || latin.canEncode(text)) {
            return text == null ? "" : text;
        }
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            String character = Character.toString(point);
            safe.append(latin.canEncode(character) ? character : "?");
        });
        return safe.toString();
    }
}
