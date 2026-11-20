package com.grocery.common.export;

import com.grocery.common.export.ExportColumn.Kind;
import org.dhatim.fastexcel.BorderSide;
import org.dhatim.fastexcel.BorderStyle;
import org.dhatim.fastexcel.ConditionalFormattingExpressionRule;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An Excel workbook written as a stream with fastexcel. Each sheet reads like the PDF:
 * <pre>
 *   row 1   Orders                                              ← the title, bold, in the brand green
 *   row 2   737 rows · Exported 16 Nov 2026, 09:08 … · Filters: Status: Delivered
 *   row 3   (a thin gap)
 *   row 4   Order number | Customer | Status | Total (INR) …   ← the column headers: white bold text on the brand
 *                                                                 green, filter buttons, frozen while scrolling and
 *                                                                 repeated on every printed page
 *   row 5…  the data, every other row lightly shaded
 * </pre>
 * The ZIP around the parts is rewritten as it streams into a plain ZIP ({@link PlainZipOutputStream}), so LibreOffice
 * opens the file as well as Excel.
 * Text is written as inline strings (no shared-strings table growing in memory), numbers and dates as real cells with
 * a number format (they sort and sum in Excel). {@link #flush} writes the rows so far and forgets them, so a million
 * rows need no more memory than one batch. The filter range and the shading are added when a sheet is finished (both
 * are one rule for the whole range, not a style per cell). Past Excel's 1,048,576 rows per sheet the rows continue on
 * a new sheet that starts with the same title and headers.
 */
final class XlsxTableWriter implements TableWriter {

    /** Excel's limit per sheet, the title rows and the header row included. */
    static final int MAX_ROWS_PER_SHEET = 1_048_576;

    // Where things go on each sheet (0-based rows).
    static final int TITLE_ROW = 0;
    static final int DESCRIPTION_ROW = 1;
    static final int GAP_ROW = 2;
    static final int HEADER_ROW = 3;
    static final int FIRST_DATA_ROW = HEADER_ROW + 1;

    // The app's colours (frontend index.css): the brand green, slate text, a very light green for the shaded rows.
    static final String BRAND = "065F46";
    private static final String HEADER_TEXT = "FFFFFF";
    private static final String HEADER_SEPARATOR = "3F8F72";
    private static final String HEADER_EDGE = "022C22";
    private static final String NOTE = "475569";
    private static final String SHADE = "EEF6F1";

    private static final String DATE_TIME_FORMAT = "yyyy-mm-dd hh:mm";
    private static final String DATE_FORMAT = "yyyy-mm-dd";
    private static final String MONEY_FORMAT = "#,##0.00";
    private static final String INTEGER_FORMAT = "0";

    private final OutputStream out;
    private final Layout layout;
    private final List<ExportColumn<?>> columns;
    private final int rowsPerSheet;
    private PlainZipOutputStream zip;
    private Workbook workbook;
    private Worksheet sheet;
    private int sheetNumber;
    private int nextRow;

    XlsxTableWriter(OutputStream out, Layout layout) {
        this(out, layout, MAX_ROWS_PER_SHEET);
    }

    /** {@code rowsPerSheet}: the title rows and the header included; lower only in tests. */
    XlsxTableWriter(OutputStream out, Layout layout, int rowsPerSheet) {
        if (rowsPerSheet <= FIRST_DATA_ROW) {
            throw new IllegalArgumentException("rowsPerSheet must leave room for data rows");
        }
        this.out = out;
        this.layout = layout;
        this.columns = layout.columns();
        this.rowsPerSheet = rowsPerSheet;
    }

    @Override
    public void start() throws IOException {
        zip = new PlainZipOutputStream(out); // a plain ZIP: LibreOffice refuses fastexcel's ZIP64 descriptors
        workbook = new Workbook(zip, "FreshCart", "1.0");
        workbook.properties().setTitle(layout.title());
        workbook.properties().setDescription(layout.description());
        newSheet();
    }

    private void newSheet() throws IOException {
        if (sheet != null) {
            finishSheet();
        }
        sheetNumber++;
        sheet = workbook.newWorksheet(sheetName(layout.title(), sheetNumber));
        sheet.setTabColor(BRAND);
        sheet.pageOrientation("landscape");

        // The title and the line under it (row count, when, by whom, filters): the cells are wide enough to overflow.
        sheet.inlineString(TITLE_ROW, 0, layout.title());
        sheet.style(TITLE_ROW, 0).bold().fontSize(14).fontColor(BRAND).verticalAlignment("center").set();
        sheet.rowHeight(TITLE_ROW, 24);
        sheet.inlineString(DESCRIPTION_ROW, 0, layout.description());
        sheet.style(DESCRIPTION_ROW, 0).fontSize(9).fontColor(NOTE).verticalAlignment("center").set();
        sheet.rowHeight(GAP_ROW, 6);

        // The column headers: white bold text on the brand green, a darker edge underneath, the headers of numbers and
        // dates on the right like their values (Excel puts both on the right). Long headers wrap instead of being cut.
        for (int c = 0; c < columns.size(); c++) {
            ExportColumn<?> column = columns.get(c);
            sheet.inlineString(HEADER_ROW, c, column.header());
            sheet.width(c, width(column));
            sheet.style(HEADER_ROW, c).bold().fontColor(HEADER_TEXT).fillColor(BRAND)
                    .horizontalAlignment(rightAligned(column.kind()) ? "right" : "left").verticalAlignment("center")
                    .wrapText(true)
                    .borderStyle(BorderStyle.THIN).borderColor(HEADER_SEPARATOR)
                    .borderStyle(BorderSide.BOTTOM, BorderStyle.MEDIUM).borderColor(BorderSide.BOTTOM, HEADER_EDGE)
                    .set();
        }
        sheet.rowHeight(HEADER_ROW, 30);
        sheet.freezePane(0, FIRST_DATA_ROW); // title and headers stay in view while scrolling
        sheet.repeatRows(HEADER_ROW);        // and the headers are printed on every page
        nextRow = FIRST_DATA_ROW;
    }

    @Override
    public void row(Object[] values) throws IOException {
        if (nextRow == rowsPerSheet) {
            newSheet();
        }
        int r = nextRow++;
        for (int c = 0; c < values.length; c++) {
            Object value = values[c];
            if (value == null) {
                continue;
            }
            Kind kind = columns.get(c).kind();
            switch (kind) {
                case INTEGER, DECIMAL, MONEY -> {
                    if (value instanceof Number number) {
                        sheet.value(r, c, kind == Kind.DECIMAL ? CellValues.decimal(number) : number);
                        if (kind != Kind.DECIMAL) {
                            sheet.style(r, c).format(kind == Kind.MONEY ? MONEY_FORMAT : INTEGER_FORMAT).set();
                        }
                    } else {
                        text(r, c, value.toString());
                    }
                }
                case DATE_TIME -> {
                    LocalDateTime dateTime = layout.values().localDateTime(value);
                    if (dateTime == null) {
                        text(r, c, value.toString());
                    } else {
                        sheet.value(r, c, dateTime);
                        sheet.style(r, c).format(DATE_TIME_FORMAT).set();
                    }
                }
                case DATE -> {
                    LocalDate date = layout.values().localDate(value);
                    if (date == null) {
                        text(r, c, value.toString());
                    } else {
                        sheet.value(r, c, date);
                        sheet.style(r, c).format(DATE_FORMAT).set();
                    }
                }
                case TEXT, YES_NO -> text(r, c, layout.values().text(kind, value));
            }
        }
    }

    private void text(int r, int c, String value) {
        if (!value.isEmpty()) {
            // Written as text, never as a formula: no "CSV injection" in a workbook.
            sheet.inlineString(r, c, value);
        }
    }

    @Override
    public void flush() throws IOException {
        sheet.flush();
        zip.flush();
    }

    @Override
    public void finish() throws IOException {
        finishSheet();
        workbook.finish();
        zip.checkComplete();
        zip.flush();
    }

    /**
     * Filter buttons over the headers and the data, and the shading of every other data row: added now that the last
     * row is known. Both are a single rule for the range (written after the rows), so they cost nothing per row.
     */
    private void finishSheet() throws IOException {
        int last = nextRow - 1;
        int right = columns.size() - 1;
        sheet.setAutoFilter(HEADER_ROW, 0, Math.max(last, HEADER_ROW), right);
        if (last >= FIRST_DATA_ROW + 1) {
            sheet.range(FIRST_DATA_ROW, 0, last, right).style().fillColor(SHADE)
                    .set(new ConditionalFormattingExpressionRule("MOD(ROW()-" + (FIRST_DATA_ROW + 1) + ",2)=1", false));
        }
        sheet.finish();
    }

    /** Excel sheet names: at most 31 characters, none of {@code \ / ? * [ ] :}. */
    static String sheetName(String title, int number) {
        String clean = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        String suffix = number == 1 ? "" : " (" + number + ")";
        int room = 31 - suffix.length();
        return (clean.isEmpty() ? "Export" : clean.length() > room ? clean.substring(0, room) : clean) + suffix;
    }

    /** Numbers and dates: real cells, which Excel shows on the right. */
    private static boolean rightAligned(Kind kind) {
        return kind == Kind.INTEGER || kind == Kind.DECIMAL || kind == Kind.MONEY || kind == Kind.DATE
                || kind == Kind.DATE_TIME;
    }

    private static double width(ExportColumn<?> column) {
        double byKind = switch (column.kind()) {
            case TEXT -> 22;
            case DATE_TIME -> 18;
            case DATE -> 12;
            case MONEY, DECIMAL -> 14;
            case INTEGER -> 11;
            case YES_NO -> 9;
        };
        // Room for the header's longest word (bold, plus the filter button); longer headers wrap onto a second line.
        int longestWord = 0;
        for (String word : column.header().split("\\s+")) {
            longestWord = Math.max(longestWord, word.length());
        }
        return Math.max(byKind, Math.min(40, Math.max(longestWord + 5, column.header().length() * 0.6 + 3)));
    }
}
