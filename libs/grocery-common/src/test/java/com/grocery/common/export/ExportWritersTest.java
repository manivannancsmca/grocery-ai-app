package com.grocery.common.export;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.grocery.common.export.XlsxTableWriter.DESCRIPTION_ROW;
import static com.grocery.common.export.XlsxTableWriter.FIRST_DATA_ROW;
import static com.grocery.common.export.XlsxTableWriter.HEADER_ROW;
import static com.grocery.common.export.XlsxTableWriter.TITLE_ROW;
import static org.assertj.core.api.Assertions.assertThat;

/** The three file writers on the same small table: what a person sees when they open the file. */
class ExportWritersTest {

    enum Status { OUT_FOR_DELIVERY, DELIVERED }

    record Order(String number, String customer, Status status, BigDecimal total, Integer items, Instant placed,
                 LocalDate due, Boolean paid) {
    }

    static final ExportTable<Order> TABLE = ExportTable.<Order>named("orders", "Orders")
            .text("Order number", Order::number)
            .text("Customer", Order::customer)
            .text("Status", Order::status)
            .money("Total (INR)", Order::total)
            .integer("Items", Order::items)
            .dateTime("Placed", Order::placed)
            .date("Due", Order::due)
            .yesNo("Paid", Order::paid)
            .build();

    static final List<Order> ORDERS = List.of(
            // 2026-11-15T04:45Z is 10:15 in India
            new Order("ORD-1", "Lakshmi, \"Lux\" R", Status.OUT_FOR_DELIVERY, new BigDecimal("1234.5"), 3,
                    Instant.parse("2026-11-15T04:45:00Z"), LocalDate.parse("2026-11-16"), true),
            new Order("ORD-2", "=HYPERLINK(\"http://evil\")", Status.DELIVERED, new BigDecimal("-20"), 1,
                    Instant.parse("2026-11-14T20:00:00Z"), null, false),
            new Order("ORD-3", "Karthik\nSecond line", Status.DELIVERED, null, null, null, null, null));

    static final TableWriter.Layout LAYOUT = new TableWriter.Layout("Orders", List.copyOf(TABLE.columns()),
            "3 rows  ·  Filters: Status: Delivered", new CellValues(ZoneId.of("Asia/Kolkata")));

    @Test
    void csvIsUtf8WithBomQuotedWhereNeededAndGuardsAgainstFormulas() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(new CsvTableWriter(out, LAYOUT), ORDERS);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        assertThat(csv.substring(1).split("\r\n", -1)).containsExactly(
                "Order number,Customer,Status,Total (INR),Items,Placed,Due,Paid",
                "ORD-1,\"Lakshmi, \"\"Lux\"\" R\",Out for delivery,1234.50,3,2026-11-15 10:15,2026-11-16,Yes",
                // a value Excel would run as a formula is shown as text; a negative amount stays a number
                "ORD-2,\"'=HYPERLINK(\"\"http://evil\"\")\",Delivered,-20.00,1,2026-11-15 01:30,,No",
                "ORD-3,\"Karthik\nSecond line\",Delivered,,,,,",
                "");
    }

    @Test
    void xlsxHasRealNumbersAndDatesAndTextNeverBecomesAFormula() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(new XlsxTableWriter(out, LAYOUT), ORDERS);

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getFirstSheet();
            assertThat(sheet.getName()).isEqualTo("Orders");
            List<Row> rows = sheet.read();
            // like the PDF: the title and the description on top, then the headers, then the data
            assertThat(row(rows, TITLE_ROW).getCellText(0)).isEqualTo("Orders");
            assertThat(row(rows, DESCRIPTION_ROW).getCellText(0)).isEqualTo("3 rows  ·  Filters: Status: Delivered");
            assertThat(row(rows, HEADER_ROW).getCellText(0)).isEqualTo("Order number");
            assertThat(row(rows, HEADER_ROW).getCellText(7)).isEqualTo("Paid");
            assertThat(dataRows(rows)).hasSize(3);
            Row first = row(rows, FIRST_DATA_ROW);
            assertThat(first.getCellText(1)).isEqualTo("Lakshmi, \"Lux\" R");
            assertThat(first.getCellText(2)).isEqualTo("Out for delivery");
            assertThat(first.getCellAsNumber(3)).contains(new BigDecimal("1234.5"));
            assertThat(first.getCellAsNumber(4)).contains(new BigDecimal("3"));
            assertThat(first.getCellAsDate(5)).hasValueSatisfying(placed ->
                    assertThat(placed).hasToString("2026-11-15T10:15"));
            assertThat(first.getCellText(7)).isEqualTo("Yes");
            Row second = row(rows, FIRST_DATA_ROW + 1);
            assertThat(second.getCellText(1)).isEqualTo("=HYPERLINK(\"http://evil\")");
            assertThat(second.getCell(1).getFormula()).isNull();
            assertThat(second.getCellAsNumber(3)).contains(new BigDecimal("-20"));
        }
    }

    @Test
    void xlsxHeadersStandOutStayInViewAndFilter() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(new XlsxTableWriter(out, LAYOUT), ORDERS);
        Map<String, String> parts = unzip(out.toByteArray());

        String styles = parts.get("xl/styles.xml");
        // white bold header text on the brand green, a medium edge under the headers
        assertThat(styles).containsIgnoringCase("065F46").containsIgnoringCase("FFFFFF").contains("medium");
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        // the title rows and the headers are frozen (rows 1–4), the headers have filter buttons over all data rows
        assertThat(sheet).contains("ySplit=\"4\"", "state=\"frozen\"", "<autoFilter ref=\"A4:H7\"");
        // every other data row shaded by one conditional format, not a style per cell
        assertThat(sheet).contains("<conditionalFormatting sqref=\"A5:H7\"", "MOD(ROW()-5,2)=1");
        assertThat(sheet).contains("tabColor");
        // printed: the header row on every page
        assertThat(parts.get("xl/workbook.xml")).contains("_xlnm.Print_Titles");
    }

    @Test
    void xlsxIsAPlainZipThatStrictReadersAccept() throws IOException {
        // big enough for parts written in many chunks, and three sheets
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxTableWriter writer = new XlsxTableWriter(out, LAYOUT, FIRST_DATA_ROW + 12_000);
        writer.start();
        for (int i = 0; i < 30_000; i++) {
            Order order = ORDERS.get(i % 3);
            writer.row(TABLE.values(new Order("ORD-" + i, order.customer() + " " + i, order.status(), order.total(),
                    order.items(), order.placed(), order.due(), order.paid())));
            if (i % 1_000 == 999) {
                writer.flush();
            }
        }
        writer.finish();
        byte[] xlsx = out.toByteArray();

        // java.util.zip.ZipInputStream reads the local headers and data descriptors as LibreOffice does: it refused
        // fastexcel's ZIP64 descriptors ("invalid entry size"); every part reads back whole now
        Map<String, Long> sizes = new TreeMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                sizes.put(entry.getName(), (long) zip.readAllBytes().length);
            }
        }
        assertThat(sizes).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml");
        assertThat(sizes.get("xl/worksheets/sheet1.xml")).isGreaterThan(1_000_000);
        // no entry asks for ZIP64 (version 4.5): the central directory and every local header it points to say 2.0
        java.nio.ByteBuffer zip = java.nio.ByteBuffer.wrap(xlsx).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int end = xlsx.length - 22;
        assertThat(zip.getInt(end)).isEqualTo(0x06054b50);
        int entry = zip.getInt(end + 16);
        for (int i = 0; i < Short.toUnsignedInt(zip.getShort(end + 10)); i++) {
            assertThat(zip.getInt(entry)).isEqualTo(0x02014b50);
            assertThat(zip.getShort(entry + 6)).isEqualTo((short) 20);
            int local = zip.getInt(entry + 42);
            assertThat(zip.getInt(local)).isEqualTo(0x04034b50);
            assertThat(zip.getShort(local + 4)).isEqualTo((short) 20);
            entry += 46 + Short.toUnsignedInt(zip.getShort(entry + 28)) + Short.toUnsignedInt(zip.getShort(entry + 30))
                    + Short.toUnsignedInt(zip.getShort(entry + 32));
        }
        // and the central directory agrees with the parts
        assertThat(unzip(xlsx).get("xl/worksheets/sheet1.xml")).hasSize(sizes.get("xl/worksheets/sheet1.xml").intValue());
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheets().mapToLong(sheet -> {
                try {
                    return dataRows(sheet.read()).size();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).sum()).isEqualTo(30_000);
        }
    }

    @Test
    void anEmptyXlsxStillHasItsTitleAndFilterableHeaders() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(new XlsxTableWriter(out, LAYOUT), List.of());

        assertThat(unzip(out.toByteArray()).get("xl/worksheets/sheet1.xml"))
                .contains("<autoFilter ref=\"A4:H4\"").doesNotContain("conditionalFormatting");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(row(rows, HEADER_ROW).getCellText(1)).isEqualTo("Customer");
            assertThat(dataRows(rows)).isEmpty();
        }
    }

    @Test
    void xlsxContinuesOnANewSheetPastTheSheetLimit() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the title rows and the header, then 2 data rows per sheet
        XlsxTableWriter writer = new XlsxTableWriter(out, LAYOUT, FIRST_DATA_ROW + 2);
        writer.start();
        for (int i = 0; i < 5; i++) {
            writer.row(TABLE.values(ORDERS.get(i % 3)));
        }
        writer.finish();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            List<Sheet> sheets = workbook.getSheets().toList();
            assertThat(sheets).extracting(Sheet::getName).containsExactly("Orders", "Orders (2)", "Orders (3)");
            assertThat(dataRows(sheets.get(0).read())).hasSize(2);
            assertThat(dataRows(sheets.get(1).read())).hasSize(2);
            List<Row> last = sheets.get(2).read();
            assertThat(dataRows(last)).hasSize(1);
            // each sheet starts like the first one
            assertThat(row(last, TITLE_ROW).getCellText(0)).isEqualTo("Orders");
            assertThat(row(last, HEADER_ROW).getCellText(0)).isEqualTo("Order number");
        }
    }

    @Test
    void pdfRepeatsTheHeaderOnEveryPageAndNumbersThePages() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfTableWriter writer = new PdfTableWriter(out, LAYOUT);
        writer.start();
        for (int i = 0; i < 250; i++) {
            Order order = ORDERS.getFirst();
            writer.row(TABLE.values(new Order("ORD-" + (1000 + i), "Customer " + i, order.status(), order.total(),
                    order.items(), order.placed(), order.due(), order.paid())));
            if (i % 50 == 49) {
                writer.flush();
            }
        }
        writer.finish();

        PdfReader reader = new PdfReader(out.toByteArray());
        assertThat(reader.getNumberOfPages()).isGreaterThan(1);
        PdfTextExtractor text = new PdfTextExtractor(reader);
        String firstPage = text.getTextFromPage(1);
        assertThat(firstPage).contains("Orders", "Filters: Status: Delivered", "Order number", "ORD-1000",
                "1,234.50", "2026-11-15 10:15", "Page 1");
        String lastPage = text.getTextFromPage(reader.getNumberOfPages());
        assertThat(lastPage).contains("Order number", "ORD-1249", "Page " + reader.getNumberOfPages());
    }

    @Test
    void anEmptyPdfSaysSo() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(new PdfTableWriter(out, LAYOUT), List.of());

        String page = new PdfTextExtractor(new PdfReader(out.toByteArray())).getTextFromPage(1);
        assertThat(page).contains("Order number", "No records match these filters.");
    }

    @Test
    void pdfShowsAQuestionMarkForCharactersHelveticaCannotDraw() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Order tamil = new Order("ORD-9", "லக்ஷ்மி", Status.DELIVERED, BigDecimal.ONE, 1, null, null, true);
        write(new PdfTableWriter(out, LAYOUT), List.of(tamil));

        assertThat(new PdfTextExtractor(new PdfReader(out.toByteArray())).getTextFromPage(1)).contains("ORD-9", "?");
    }

    @Test
    void sheetNamesFollowExcelRules() {
        assertThat(XlsxTableWriter.sheetName("Orders: open/late [today]", 1)).isEqualTo("Orders  open late  today");
        assertThat(XlsxTableWriter.sheetName("A very long export title that goes on", 2))
                .hasSize(31).endsWith(" (2)");
    }

    /** The row at a 0-based index (fastexcel-reader numbers rows from 1 and skips rows without cells). */
    private static Row row(List<Row> rows, int index) {
        return rows.stream().filter(r -> r.getRowNum() == index + 1).findFirst().orElseThrow();
    }

    private static List<Row> dataRows(List<Row> rows) {
        return rows.stream().filter(r -> r.getRowNum() > FIRST_DATA_ROW).toList();
    }

    /**
     * The parts of an .xlsx (a zip of XML files), by name. Read through the zip's directory: the streamed entries
     * (sizes after the data, as Excel accepts) are refused by {@code ZipInputStream}.
     */
    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Path file = Files.createTempFile("export", ".xlsx");
        try {
            Files.write(file, xlsx);
            Map<String, String> parts = new TreeMap<>();
            try (ZipFile zip = new ZipFile(file.toFile())) {
                for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                    parts.put(entry.getName(), new String(zip.getInputStream(entry).readAllBytes(),
                            StandardCharsets.UTF_8));
                }
            }
            return parts;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void write(TableWriter writer, List<Order> orders) throws IOException {
        writer.start();
        for (Order order : orders) {
            writer.row(TABLE.values(order));
        }
        writer.flush();
        writer.finish();
    }
}
