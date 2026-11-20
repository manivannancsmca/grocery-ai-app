package com.grocery.reporting;

import com.grocery.reporting.TestEvents.Order;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grocery.reporting.TestEvents.INDIA;
import static com.grocery.reporting.TestEvents.grocery;
import static com.grocery.reporting.TestEvents.orderConfirmed;
import static com.grocery.reporting.TestEvents.orderCreated;
import static com.grocery.reporting.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API around the numbers: India-date boundaries, the range rules, parameter errors, CSV, Excel, PDF and roles. */
class ReportApiIntegrationTest extends AbstractIntegrationTest {

    static final List<String> REPORTS = List.of("/api/admin/reports/overview", "/api/admin/reports/sales/daily",
            "/api/admin/reports/stores/top", "/api/admin/reports/products/top",
            "/api/admin/reports/delivery/performance", "/api/admin/reports/promotions",
            "/api/admin/reports/ratings", "/api/admin/reports/purchasing");

    // ---------------------------------------------------------------- India dates

    @Test
    void ordersCountOnTheirIndiaDate() throws Exception {
        UUID vendor = UUID.randomUUID();
        // 2026-11-01 23:59:59.999999 IST | 2026-11-02 00:00 IST | 2026-11-02 23:59:59.999999 IST | 2026-11-03 00:00 IST
        placeAndConfirm(vendor, Instant.parse("2026-11-01T18:29:59.999999Z"), "10.00");
        placeAndConfirm(vendor, Instant.parse("2026-11-01T18:30:00Z"), "20.00");
        placeAndConfirm(vendor, Instant.parse("2026-11-02T18:29:59.999999Z"), "40.00");
        placeAndConfirm(vendor, Instant.parse("2026-11-02T18:30:00Z"), "80.00");

        JsonNode oneDay = report("/api/admin/reports/overview", "from", "2026-11-02", "to", "2026-11-02");
        assertThat(oneDay.get("orders").asLong()).isEqualTo(2);
        assertThat(oneDay.get("grossSales").toString()).isEqualTo("120.00");   // (20 + 30) + (40 + 30) with the fee

        JsonNode daily = report("/api/admin/reports/sales/daily", "from", "2026-11-01", "to", "2026-11-03");
        List<String> days = new ArrayList<>();
        daily.forEach(d -> days.add(d.get("date").asString() + "=" + d.get("orders").asLong() + "/"
                + d.get("gmv")));
        assertThat(days).containsExactly("2026-11-01=1/40.00", "2026-11-02=2/120.00", "2026-11-03=1/110.00");
    }

    private void placeAndConfirm(UUID vendor, Instant placedAt, String price) {
        Order o = grocery(UUID.randomUUID(), vendor, "Green Basket", "30.00",
                product(UUID.randomUUID(), "Item", 1, price));
        dispatchAll(orderCreated(o, placedAt), orderConfirmed(o, placedAt.plusSeconds(60)));
    }

    // ---------------------------------------------------------------- the range

    @Test
    void theRangeIsInclusiveAndAtMost366Days() throws Exception {
        JsonNode leapYear = report("/api/admin/reports/sales/daily", "from", "2024-01-01", "to", "2024-12-31");
        assertThat(leapYear).hasSize(366);
        assertThat(leapYear.get(0).get("date").asString()).isEqualTo("2024-01-01");
        assertThat(leapYear.get(365).get("date").asString()).isEqualTo("2024-12-31");

        for (String path : REPORTS) {
            mvc.perform(get(path).param("from", "2025-01-01").param("to", "2026-01-02").with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.detail").value("A report covers at most 366 days (asked: 367)"));
        }
    }

    @Test
    void withoutDatesTheReportCoversTheLast30DaysEndingTodayInIndia() throws Exception {
        LocalDate today = LocalDate.now(INDIA);
        JsonNode r = report("/api/admin/reports/overview");
        assertThat(r.get("to").asString()).isEqualTo(today.toString());
        assertThat(r.get("from").asString()).isEqualTo(today.minusDays(29).toString());

        JsonNode onlyTo = report("/api/admin/reports/overview", "to", "2026-03-31");
        assertThat(onlyTo.get("from").asString()).isEqualTo("2026-03-02");
    }

    @Test
    void badParametersAre400() throws Exception {
        expect400("/api/admin/reports/overview", "from", "2026-10-06", "to", "2026-10-05");      // from after to
        expect400("/api/admin/reports/overview", "from", "yesterday");
        expect400("/api/admin/reports/overview", "to", "2026-02-30");
        expect400("/api/admin/reports/overview", "format", "xml");
        expect400("/api/admin/reports/sales/daily", "type", "PHARMACY");
        expect400("/api/admin/reports/sales/daily", "storeId", "not-a-uuid");
        expect400("/api/admin/reports/stores/top", "metric", "revenue");
        expect400("/api/admin/reports/stores/top", "limit", "0");
        expect400("/api/admin/reports/stores/top", "limit", "101");
        expect400("/api/admin/reports/products/top", "limit", "ten");
        expect400("/api/admin/reports/products/top", "metric", "gmv");
        expect400("/api/admin/reports/ratings", "targetType", "SUPPLIER");
        expect400("/api/admin/reports/ratings", "minCount", "0");
        expect400("/api/admin/reports/purchasing", "vendorId", "42");
    }

    private void expect400(String path, String... params) throws Exception {
        var request = get(path).with(admin());
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    // ---------------------------------------------------------------- CSV

    @Test
    void csvHasAHeaderRowAndEscapesText() throws Exception {
        ReportDataset data = new ReportDataset();
        data.all().forEach(this::dispatch);

        MvcResult stores = mvc.perform(get("/api/admin/reports/stores/top").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"stores-top_2026-10-05_2026-10-06.csv\""))
                .andReturn();
        assertThat(lines(stores)).containsExactly(
                "rank,storeId,storeName,storeType,orders,gmv,averageOrderValue",
                "1," + data.greenBasket + ",Green Basket,GROCERY,2,380.00,190.00",
                "2," + data.spiceRoute + ",Spice Route,RESTAURANT,1,250.00,250.00",
                "3," + data.freshDaily + ",\"Fresh, \"\"Daily\"\" Mart\",GROCERY,1,111.00,111.00");
        assertThat(stores.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\r\n");

        MvcResult overview = mvc.perform(get("/api/admin/reports/overview").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andReturn();
        assertThat(lines(overview)).containsExactly(
                "from,to,timezone,currency,grossSales,netSales,orders,averageOrderValue,cancellationRate,refunds,"
                        + "couponDiscounts.platform,couponDiscounts.store,couponDiscounts.total,"
                        + "couponDiscounts.items,couponDiscounts.deliveryFee,newCustomers,placedOrders,"
                        + "cancelledOrders,rejectedOrders,refundCount,couponOrders,returnsApproved,returnedAmount",
                "2026-10-05,2026-10-06,Asia/Kolkata,INR,741.00,446.00,4,185.25,0.3333,295.00,9.00,40.00,69.00,"
                        + "29.00,40.00,2,6,2,1,2,3,1,45.00");

        MvcResult daily = mvc.perform(get("/api/admin/reports/sales/daily").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv"))
                .andExpect(status().isOk()).andReturn();
        assertThat(lines(daily)).containsExactly("date,orders,gmv,discounts,refunds,net",
                "2026-10-05,3,630.00,60.00,250.00,380.00",
                "2026-10-06,1,111.00,9.00,45.00,66.00");

        MvcResult products = mvc.perform(get("/api/admin/reports/products/top").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv")
                        .param("limit", "2"))
                .andExpect(status().isOk()).andReturn();
        assertThat(lines(products)).containsExactly(
                "rank,itemId,itemType,name,storeId,storeName,units,revenue,orders,returnedUnits",
                "1," + data.apples + ",PRODUCT,Apples," + data.greenBasket + ",Green Basket,6,300.00,2,1",
                "2," + data.bread + ",PRODUCT,\"Bread, brown\"," + data.freshDaily
                        + ",\"Fresh, \"\"Daily\"\" Mart\",3,90.00,1,0");

        MvcResult delivery = mvc.perform(get("/api/admin/reports/delivery/performance").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv"))
                .andExpect(status().isOk()).andReturn();
        assertThat(lines(delivery)).containsExactly(
                "date,deliveries,avgConfirmToAssignedMinutes,avgAssignedToPickedUpMinutes,"
                        + "avgPickedUpToDeliveredMinutes,failed",
                "2026-10-05,2,15.0,12.5,35.0,0",
                "2026-10-06,0,,,,1");

        MvcResult promotions = mvc.perform(get("/api/admin/reports/promotions").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv"))
                .andExpect(status().isOk()).andReturn();
        assertThat(lines(promotions)).containsExactly(
                "couponCode,couponName,orders,discountGiven,gmv,fundedBy,discount,deliveryFeeDiscount,stores",
                "FREEDEL,Free delivery at Spice Route,1,40.00,250.00,STORE,0.00,40.00,1",
                "SAVE10,Save 10%,2,29.00,321.00,PLATFORM,29.00,0.00,2");

        MvcResult ratings = mvc.perform(get("/api/admin/reports/ratings").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv")
                        .param("limit", "1"))
                .andExpect(status().isOk()).andReturn();
        List<String> ratingLines = lines(ratings);
        assertThat(ratingLines.getFirst()).isEqualTo("list,rank,targetType,targetId,targetName,averageRating,ratingCount,"
                + "stars1,stars2,stars3,stars4,stars5,updatedAt");
        assertThat(ratingLines.get(1)).startsWith("lowest,1,MENU_ITEM," + data.biryani + ",Biryani,2.00,1,0,1,0,0,0,");
        assertThat(ratingLines.get(2)).startsWith("highest,1,VENDOR," + data.greenBasket + ",Green Basket,5.00,3,");
        assertThat(ratingLines).hasSize(3);

        MvcResult purchasing = mvc.perform(get("/api/admin/reports/purchasing").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "csv"))
                .andExpect(status().isOk()).andReturn();
        assertThat(lines(purchasing)).containsExactly(
                "vendorId,vendorName,date,units,cost,receipts,purchaseOrders",
                data.greenBasket + ",Green Basket,2026-10-05,20,577.50,2,1",
                data.freshDaily + ",\"Fresh, \"\"Daily\"\" Mart\",2026-10-06,20,245.00,1,1");
    }

    @Test
    void anEmptyReportIsStillAHeaderRow() throws Exception {
        MvcResult empty = mvc.perform(get("/api/admin/reports/promotions").with(admin())
                        .param("from", "2020-01-01").param("to", "2020-01-31").param("format", "csv"))
                .andExpect(status().isOk()).andReturn();
        assertThat(empty.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("couponCode,couponName,orders,discountGiven,gmv,fundedBy,discount,deliveryFeeDiscount,"
                        + "stores\r\n");
    }

    @Test
    void jsonMoneyHasTwoDecimals() throws Exception {
        new ReportDataset().orderEvents().forEach(this::dispatch);
        String body = mvc.perform(get("/api/admin/reports/overview").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"grossSales\":741.00", "\"averageOrderValue\":185.25",
                "\"cancellationRate\":0.3333", "\"couponDiscounts\":{\"platform\":9.00,\"store\":40.00,\"total\":69.00",
                "\"from\":\"2026-10-05\"");

        String daily = mvc.perform(get("/api/admin/reports/sales/daily").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.FROM))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(daily).isEqualTo("[{\"date\":\"2026-10-05\",\"orders\":3,\"gmv\":630.00,\"discounts\":60.00,"
                + "\"refunds\":250.00,\"net\":380.00}]");                       // list reports are plain arrays
    }

    // ---------------------------------------------------------------- Excel and PDF

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void excelHasHeadingsInWordsRealNumbersAndThePeriodOnTop() throws Exception {
        new ReportDataset().all().forEach(this::dispatch);

        MvcResult daily = mvc.perform(get("/api/admin/reports/sales/daily").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string("X-Export-Rows", "2"))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=\"report-daily-sales-")))
                .andReturn();
        List<Row> rows = sheet(daily);
        // like every export: the title, a line with the period, the headers on row 4, then the data
        assertThat(rowNumber(rows, 1).getCellText(0)).isEqualTo("Daily sales");
        assertThat(rowNumber(rows, 2).getCellText(0)).contains("2 rows", "From: 2026-10-05", "To: 2026-10-06");
        assertThat(cells(rowNumber(rows, 4))).containsExactly("Day", "Orders", "Gross sales (INR)", "Discounts (INR)",
                "Refunds (INR)", "Net sales (INR)");
        Row first = rowNumber(rows, 5);
        assertThat(first.getCellAsDate(0)).hasValueSatisfying(day -> assertThat(day).hasToString("2026-10-05T00:00"));
        assertThat(first.getCellAsNumber(1)).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo("3"));
        assertThat(first.getCellAsNumber(2)).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo("630.00"));
        assertThat(first.getCellAsNumber(5)).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo("380.00"));

        // the overview's figures are listed downwards, one per row
        List<Row> overview = sheet(mvc.perform(get("/api/admin/reports/overview").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "excel"))
                .andExpect(status().isOk()).andExpect(content().contentType(XLSX)).andReturn());
        assertThat(cells(rowNumber(overview, 4))).containsExactly("Figure", "Value", "Unit");
        assertThat(figure(overview, "Gross sales (GMV)")).isEqualByComparingTo("741.00");
        assertThat(figure(overview, "Orders (confirmed)")).isEqualByComparingTo("4");
        assertThat(figure(overview, "Cancellation rate (cancelled or rejected)")).isEqualByComparingTo("33.33");
        assertThat(figure(overview, "Coupon discounts funded by the platform")).isEqualByComparingTo("9.00");
        assertThat(figure(overview, "Coupon discounts funded by stores")).isEqualByComparingTo("40.00");

        // promotions: the coupon's name and who funds it, in words
        List<Row> promotions = sheet(mvc.perform(get("/api/admin/reports/promotions").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "xlsx"))
                .andExpect(status().isOk()).andExpect(content().contentType(XLSX)).andReturn());
        assertThat(cells(rowNumber(promotions, 4))).startsWith("Coupon code", "Coupon name", "Funded by", "Orders");
        assertThat(cells(rowNumber(promotions, 5))).startsWith("FREEDEL", ReportDataset.FREEDEL_NAME, "Store");
        assertThat(cells(rowNumber(promotions, 6))).startsWith("SAVE10", ReportDataset.SAVE10_NAME, "Platform");
    }

    @Test
    void pdfShowsTheReportWithItsPeriodAndFilters() throws Exception {
        new ReportDataset().all().forEach(this::dispatch);

        MvcResult stores = mvc.perform(get("/api/admin/reports/stores/top").with(admin())
                        .param("from", ReportDataset.FROM).param("to", ReportDataset.TO).param("format", "pdf")
                        .param("type", "GROCERY"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("X-Export-Rows", "2"))
                .andReturn();
        String page = new PdfTextExtractor(new PdfReader(stores.getResponse().getContentAsByteArray()))
                .getTextFromPage(1);
        assertThat(page).contains("Top stores", "From: 2026-10-05", "To: 2026-10-06", "Store type: Grocery",
                "Average order", "Green Basket", "380.00", "Grocery");
        assertThat(page).doesNotContain("Spice Route"); // a restaurant: filtered out, as in the JSON
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "pdf"})
    void everyReportIsAFileInEveryFormatEvenWhenEmpty(String format) throws Exception {
        for (String path : REPORTS) {
            mvc.perform(get(path).with(admin()).param("from", "2020-01-01").param("to", "2020-01-31")
                            .param("format", format))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(format.equals("pdf") ? "application/pdf" : XLSX))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    private static List<Row> sheet(MvcResult result) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            return workbook.getFirstSheet().read();
        }
    }

    /** The row with this (1-based) Excel number. */
    private static Row rowNumber(List<Row> rows, int number) {
        return rows.stream().filter(row -> row.getRowNum() == number).findFirst().orElseThrow();
    }

    private static List<String> cells(Row row) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < row.getCellCount(); i++) {
            cells.add(row.getCellText(i));
        }
        return cells;
    }

    private static BigDecimal figure(List<Row> rows, String name) {
        return rows.stream().filter(row -> row.getRowNum() > 4 && name.equals(row.getCellText(0))).findFirst()
                .flatMap(row -> row.getCellAsNumber(1)).orElseThrow();
    }

    private static List<String> lines(MvcResult result) throws Exception {
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).endsWith("\r\n");
        return List.of(csv.split("\r\n"));
    }

    // ---------------------------------------------------------------- roles

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER"})
    void everyAdminRoleMayReadEveryReport(String role) throws Exception {
        for (String path : REPORTS) {
            mvc.perform(get(path).with(as(role))).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "VENDOR", "RESTAURANT_OWNER", "DELIVERY_PARTNER"})
    void nonAdminsAreForbidden(String role) throws Exception {
        for (String path : REPORTS) {
            for (String format : List.of("json", "csv", "xlsx", "pdf")) {
                mvc.perform(get(path).with(as(role)).param("format", format))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            }
        }
    }

    @Test
    void withoutATokenIs401() throws Exception {
        for (String path : REPORTS) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
