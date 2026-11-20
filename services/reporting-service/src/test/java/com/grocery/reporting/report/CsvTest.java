package com.grocery.reporting.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @Test
    void headerRowThenOneRowPerRecordWithCrlf() {
        String csv = Csv.write(List.of("date", "orders", "gmv"),
                List.of(List.of(LocalDate.parse("2026-10-05"), 3L, new BigDecimal("630.00")),
                        List.of(LocalDate.parse("2026-10-06"), 0L, new BigDecimal("0.00"))));

        assertThat(csv).isEqualTo("date,orders,gmv\r\n2026-10-05,3,630.00\r\n2026-10-06,0,0.00\r\n");
    }

    @Test
    void anEmptyReportIsTheHeaderRowOnly() {
        assertThat(Csv.write(List.of("a", "b"), List.of())).isEqualTo("a,b\r\n");
    }

    @Test
    void textWithCommasQuotesOrLineBreaksIsQuotedAndQuotesAreDoubled() {
        assertThat(Csv.field("Green Basket")).isEqualTo("Green Basket");
        assertThat(Csv.field("Bread, brown")).isEqualTo("\"Bread, brown\"");
        assertThat(Csv.field("Fresh \"Daily\" Mart")).isEqualTo("\"Fresh \"\"Daily\"\" Mart\"");
        assertThat(Csv.field("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(Csv.field("a\r\nb")).isEqualTo("\"a\r\nb\"");
        assertThat(Csv.field("")).isEmpty();
    }

    @Test
    void nullIsAnEmptyFieldAndNumbersArePlain() {
        assertThat(Csv.write(List.of("a", "b", "c"), List.of(Arrays.asList(null, new BigDecimal("1E+3"), 7))))
                .isEqualTo("a,b,c\r\n,1000,7\r\n");
        assertThat(Csv.field(new BigDecimal("-12.50"))).isEqualTo("-12.50");          // a negative net stays a number
        assertThat(Csv.field(UUID.fromString("0190f3c2-8d8e-7c3a-9a3b-6f2f6c1d2e3f")))
                .isEqualTo("0190f3c2-8d8e-7c3a-9a3b-6f2f6c1d2e3f");
    }

    @Test
    void textThatASpreadsheetWouldRunAsAFormulaIsNeutralised() {
        assertThat(Csv.field("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(Csv.field("+91 shop")).isEqualTo("'+91 shop");
        assertThat(Csv.field("-5 off")).isEqualTo("'-5 off");
        assertThat(Csv.field("@admin")).isEqualTo("'@admin");
        assertThat(Csv.field("\tTabbed")).isEqualTo("'\tTabbed");
        assertThat(Csv.field("Shop = good")).isEqualTo("Shop = good");
    }

    @Test
    void theMediaTypeIsUtf8Csv() {
        assertThat(Csv.TEXT_CSV.toString()).isEqualTo("text/csv;charset=UTF-8");
    }
}
