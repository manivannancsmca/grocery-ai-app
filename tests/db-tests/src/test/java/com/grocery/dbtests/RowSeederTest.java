package com.grocery.dbtests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The seeder reads the literal a CHECK constraint demands, as information_schema shows the clause. */
class RowSeederTest {

    @Test
    void literalsOfEqualityAndInChecksWithEscapedOrPlainQuotes() {
        String coupon = "(((`scope` = _utf8mb4\\'PLATFORM\\') and (`store_id` is null)) or ((`scope` = "
                + "_utf8mb4\\'STORE\\') and (`store_id` is not null) and (`store_type` in "
                + "(_utf8mb4\\'GROCERY\\',_utf8mb4\\'RESTAURANT\\'))))";

        assertThat(RowSeeder.checkedLiteral("scope", coupon)).isEqualTo("PLATFORM");
        assertThat(RowSeeder.checkedLiteral("store_type", coupon)).isEqualTo("GROCERY");
        assertThat(RowSeeder.checkedLiteral("status", "(`status` = 'OPEN')")).isEqualTo("OPEN");
        assertThat(RowSeeder.checkedLiteral("store_id", coupon)).isNull();
    }
}
