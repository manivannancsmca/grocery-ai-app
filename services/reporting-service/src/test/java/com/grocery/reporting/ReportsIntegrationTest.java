package com.grocery.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static com.grocery.reporting.ReportDataset.FROM;
import static com.grocery.reporting.ReportDataset.TO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report's numbers on the fixed dataset of {@link ReportDataset} (range 2026-10-05 … 2026-10-06, India), all
 * computed by hand there.
 */
class ReportsIntegrationTest extends AbstractIntegrationTest {

    private ReportDataset data;

    @BeforeEach
    void load() {
        data = new ReportDataset();
        data.all().forEach(this::dispatch);
    }

    @Test
    void overview() throws Exception {
        JsonNode o = report("/api/admin/reports/overview", "from", FROM, "to", TO);

        assertThat(o.get("from").asString()).isEqualTo(FROM);
        assertThat(o.get("to").asString()).isEqualTo(TO);
        assertThat(o.get("timezone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(o.get("currency").asString()).isEqualTo("INR");
        assertThat(o.get("grossSales").toString()).isEqualTo("741.00");  // 170 + 210 + 250 + 111
        assertThat(o.get("netSales").toString()).isEqualTo("446.00");
        assertThat(o.get("orders").asLong()).isEqualTo(4);                // confirmed: O1 … O4
        assertThat(o.get("averageOrderValue").toString()).isEqualTo("185.25");
        assertThat(o.get("cancellationRate").toString()).isEqualTo("0.3333");   // 2 of 6 placed
        assertThat(o.get("refunds").toString()).isEqualTo("295.00");     // 250 (O3) + 45 (return); LATE_CAPTURE excluded
        JsonNode coupons = o.get("couponDiscounts");
        assertThat(coupons.get("platform").toString()).isEqualTo("9.00");   // O4; O2 (20.00) predates the funding
        assertThat(coupons.get("store").toString()).isEqualTo("40.00");     // O3, Spice Route's FREEDEL
        assertThat(coupons.get("total").toString()).isEqualTo("69.00");
        assertThat(coupons.get("items").toString()).isEqualTo("29.00");
        assertThat(coupons.get("deliveryFee").toString()).isEqualTo("40.00");
        assertThat(o.get("newCustomers").asLong()).isEqualTo(2);           // C2, C3 (C1 ordered on 10-01, C4 never paid)
        assertThat(o.get("placedOrders").asLong()).isEqualTo(6);          // O1 … O6
        assertThat(o.get("cancelledOrders").asLong()).isEqualTo(2);       // O3 (after payment), O5 (timeout)
        assertThat(o.get("rejectedOrders").asLong()).isEqualTo(1);        // O6
        assertThat(o.get("refundCount").asLong()).isEqualTo(2);
        assertThat(o.get("couponOrders").asLong()).isEqualTo(3);
        assertThat(o.get("returnsApproved").asLong()).isEqualTo(1);
        assertThat(o.get("returnedAmount").toString()).isEqualTo("45.00");
    }

    @Test
    void overviewOfAnEmptyRangeIsAllZeros() throws Exception {
        JsonNode o = report("/api/admin/reports/overview", "from", "2026-09-01", "to", "2026-09-30");

        assertThat(o.get("placedOrders").asLong()).isZero();
        assertThat(o.get("grossSales").toString()).isEqualTo("0.00");
        assertThat(o.get("netSales").toString()).isEqualTo("0.00");
        assertThat(o.get("averageOrderValue").toString()).isEqualTo("0.00");
        assertThat(o.get("cancellationRate").toString()).isEqualTo("0.0000");
        assertThat(o.get("couponDiscounts").get("total").toString()).isEqualTo("0.00");
        assertThat(o.get("couponDiscounts").get("platform").toString()).isEqualTo("0.00");
        assertThat(o.get("couponDiscounts").get("store").toString()).isEqualTo("0.00");
        assertThat(o.get("newCustomers").asLong()).isZero();
    }

    @Test
    void dailySales() throws Exception {
        JsonNode days = report("/api/admin/reports/sales/daily", "from", FROM, "to", TO);

        assertThat(days.isArray()).isTrue();
        assertThat(days(days)).containsExactly(
                "2026-10-05 orders=3 gmv=630.00 discounts=60.00 refunds=250.00 net=380.00",
                "2026-10-06 orders=1 gmv=111.00 discounts=9.00 refunds=45.00 net=66.00");
    }

    @Test
    void dailySalesByTypeAndByStore() throws Exception {
        JsonNode grocery = report("/api/admin/reports/sales/daily", "from", FROM, "to", TO, "type", "grocery");
        assertThat(days(grocery)).containsExactly(
                "2026-10-05 orders=2 gmv=380.00 discounts=20.00 refunds=0.00 net=380.00",
                "2026-10-06 orders=1 gmv=111.00 discounts=9.00 refunds=45.00 net=66.00");

        JsonNode restaurant = report("/api/admin/reports/sales/daily", "from", FROM, "to", TO,
                "storeId", data.spiceRoute.toString());
        assertThat(days(restaurant)).containsExactly(
                "2026-10-05 orders=1 gmv=250.00 discounts=40.00 refunds=250.00 net=0.00",
                "2026-10-06 orders=0 gmv=0.00 discounts=0.00 refunds=0.00 net=0.00");
    }

    @Test
    void topStores() throws Exception {
        JsonNode byGmv = report("/api/admin/reports/stores/top", "from", FROM, "to", TO);
        assertThat(stores(byGmv)).containsExactly(
                "1 Green Basket GROCERY orders=2 gmv=380.00 aov=190.00",
                "2 Spice Route RESTAURANT orders=1 gmv=250.00 aov=250.00",
                "3 Fresh, \"Daily\" Mart GROCERY orders=1 gmv=111.00 aov=111.00");
        assertThat(byGmv.get(0).get("storeId").asString()).isEqualTo(data.greenBasket.toString());

        JsonNode byOrders = report("/api/admin/reports/stores/top", "from", FROM, "to", TO, "metric", "orders",
                "limit", "2");
        assertThat(stores(byOrders)).containsExactly(
                "1 Green Basket GROCERY orders=2 gmv=380.00 aov=190.00",
                "2 Spice Route RESTAURANT orders=1 gmv=250.00 aov=250.00");

        JsonNode restaurants = report("/api/admin/reports/stores/top", "from", FROM, "to", TO,
                "type", "RESTAURANT");
        assertThat(stores(restaurants)).containsExactly("1 Spice Route RESTAURANT orders=1 gmv=250.00 aov=250.00");
    }

    @Test
    void topProducts() throws Exception {
        JsonNode byUnits = report("/api/admin/reports/products/top", "from", FROM, "to", TO);
        assertThat(products(byUnits)).containsExactly(
                "1 Apples PRODUCT Green Basket units=6 revenue=300.00 orders=2 returned=1",
                "2 Bread, brown PRODUCT Fresh, \"Daily\" Mart units=3 revenue=90.00 orders=1 returned=0",
                "3 Biryani MENU_ITEM Spice Route units=1 revenue=250.00 orders=1 returned=0",
                "4 Milk PRODUCT Green Basket units=1 revenue=40.00 orders=1 returned=0");
        assertThat(byUnits.get(0).get("itemId").asString()).isEqualTo(data.apples.toString());
        assertThat(byUnits.get(0).get("storeId").asString()).isEqualTo(data.greenBasket.toString());

        JsonNode byRevenue = report("/api/admin/reports/products/top", "from", FROM, "to", TO,
                "metric", "revenue", "limit", "3");
        assertThat(products(byRevenue)).containsExactly(
                "1 Apples PRODUCT Green Basket units=6 revenue=300.00 orders=2 returned=1",
                "2 Biryani MENU_ITEM Spice Route units=1 revenue=250.00 orders=1 returned=0",
                "3 Bread, brown PRODUCT Fresh, \"Daily\" Mart units=3 revenue=90.00 orders=1 returned=0");

        JsonNode dishes = report("/api/admin/reports/products/top", "from", FROM, "to", TO, "type", "RESTAURANT");
        assertThat(products(dishes)).containsExactly(
                "1 Biryani MENU_ITEM Spice Route units=1 revenue=250.00 orders=1 returned=0");
    }

    @Test
    void deliveryPerformance() throws Exception {
        JsonNode r = report("/api/admin/reports/delivery/performance", "from", FROM, "to", TO);

        // O1: confirm 10:02 → first assignment 10:12 (10), latest assignment 10:17 → pickup 10:32 (15), → 11:02 (30)
        // O2: confirm 12:02 → 12:22 (20), 12:22 → 12:32 (10), 12:32 → 13:12 (40); O4 failed on 10-06
        List<String> days = new ArrayList<>();
        r.forEach(d -> days.add(d.get("date").asString() + " deliveries=" + d.get("deliveries").asLong()
                + " " + d.get("avgConfirmToAssignedMinutes") + "/" + d.get("avgAssignedToPickedUpMinutes") + "/"
                + d.get("avgPickedUpToDeliveredMinutes") + " failed=" + d.get("failed").asLong()));
        assertThat(days).containsExactly(
                "2026-10-05 deliveries=2 15.0/12.5/35.0 failed=0",
                "2026-10-06 deliveries=0 null/null/null failed=1");
    }

    @Test
    void promotions() throws Exception {
        JsonNode r = report("/api/admin/reports/promotions", "from", FROM, "to", TO);

        List<String> coupons = new ArrayList<>();
        r.forEach(c -> coupons.add(c.get("couponCode").asString() + " name=" + c.get("couponName")
                + " orders=" + c.get("orders") + " given=" + c.get("discountGiven") + " gmv=" + c.get("gmv")
                + " fundedBy=" + c.get("fundedBy") + " discount=" + c.get("discount")
                + " fee=" + c.get("deliveryFeeDiscount") + " stores=" + c.get("stores")));
        // SAVE10: name and funding of its newest order that has them (O4; O2 was placed before schemas 1.7.0)
        assertThat(coupons).containsExactly(
                "FREEDEL name=\"Free delivery at Spice Route\" orders=1 given=40.00 gmv=250.00 fundedBy=\"STORE\" "
                        + "discount=0.00 fee=40.00 stores=1",
                "SAVE10 name=\"Save 10%\" orders=2 given=29.00 gmv=321.00 fundedBy=\"PLATFORM\" discount=29.00 "
                        + "fee=0.00 stores=2");
    }

    @Test
    void promotionsOfOrdersPlacedBeforeTheFundingWasRecordedHaveNoNameOrFunding() throws Exception {
        // only O2 (SAVE10, placed before schemas 1.7.0) on 2026-10-05 has a coupon
        JsonNode r = report("/api/admin/reports/promotions", "from", FROM, "to", FROM);

        assertThat(r).hasSize(2);
        JsonNode save10 = r.get(1);
        assertThat(save10.get("couponCode").asString()).isEqualTo("SAVE10");
        assertThat(save10.get("couponName").isNull()).isTrue();
        assertThat(save10.get("fundedBy").isNull()).isTrue();
        JsonNode overview = report("/api/admin/reports/overview", "from", FROM, "to", FROM).get("couponDiscounts");
        assertThat(overview.get("total").toString()).isEqualTo("60.00");          // O2 20.00 + O3 40.00
        assertThat(overview.get("platform").toString()).isEqualTo("0.00");
        assertThat(overview.get("store").toString()).isEqualTo("40.00");
    }

    @Test
    void ratings() throws Exception {
        JsonNode r = report("/api/admin/reports/ratings", "from", FROM, "to", TO);

        assertThat(targets(r.get("lowest"))).containsExactly(
                "1 MENU_ITEM Biryani 2.00 (1) [0, 1, 0, 0, 0]",
                "2 DELIVERY_PARTNER Ravi Kumar 3.00 (1) [0, 0, 1, 0, 0]",
                "3 PRODUCT Apples 4.50 (2) [0, 0, 0, 1, 1]",       // version 2 kept, the late version 1 ignored
                "4 VENDOR Green Basket 5.00 (3) [0, 0, 0, 0, 3]"); // Spice Route (0 ratings) never listed
        assertThat(targets(r.get("highest"))).containsExactly(
                "1 VENDOR Green Basket 5.00 (3) [0, 0, 0, 0, 3]",
                "2 PRODUCT Apples 4.50 (2) [0, 0, 0, 1, 1]",
                "3 DELIVERY_PARTNER Ravi Kumar 3.00 (1) [0, 0, 1, 0, 0]",
                "4 MENU_ITEM Biryani 2.00 (1) [0, 1, 0, 0, 0]");
        assertThat(r.get("reviewsSubmitted").asLong()).isEqualTo(3);
        assertThat(r.get("reviewsPublished").asLong()).isEqualTo(1);
        assertThat(r.get("reviewsFlagged").asLong()).isEqualTo(2);           // the edit re-flagged one
        assertThat(r.get("lowest").get(0).get("targetId").asString()).isEqualTo(data.biryani.toString());

        JsonNode products = report("/api/admin/reports/ratings", "from", FROM, "to", TO, "targetType", "product");
        assertThat(products.get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(targets(products.get("lowest"))).containsExactly("1 PRODUCT Apples 4.50 (2) [0, 0, 0, 1, 1]");
        assertThat(products.get("reviewsSubmitted").asLong()).isEqualTo(1);

        JsonNode popular = report("/api/admin/reports/ratings", "from", FROM, "to", TO, "minCount", "2",
                "limit", "1");
        assertThat(targets(popular.get("lowest"))).containsExactly("1 PRODUCT Apples 4.50 (2) [0, 0, 0, 1, 1]");
        assertThat(targets(popular.get("highest"))).containsExactly("1 VENDOR Green Basket 5.00 (3) [0, 0, 0, 0, 3]");
    }

    @Test
    void purchasing() throws Exception {
        JsonNode r = report("/api/admin/reports/purchasing", "from", FROM, "to", TO);

        List<String> rows = new ArrayList<>();
        r.forEach(row -> rows.add(row.get("date").asString() + " " + row.get("vendorName").asString()
                + " units=" + row.get("units") + " cost=" + row.get("cost") + " receipts=" + row.get("receipts")
                + " pos=" + row.get("purchaseOrders")));
        assertThat(rows).containsExactly(
                "2026-10-05 Green Basket units=20 cost=577.50 receipts=2 pos=1",
                "2026-10-06 Fresh, \"Daily\" Mart units=20 cost=245.00 receipts=1 pos=1");
        assertThat(r.get(0).get("vendorId").asString()).isEqualTo(data.greenBasket.toString());

        JsonNode oneVendor = report("/api/admin/reports/purchasing", "from", FROM, "to", TO,
                "vendorId", data.freshDaily.toString());
        assertThat(oneVendor).hasSize(1);
        assertThat(oneVendor.get(0).get("vendorId").asString()).isEqualTo(data.freshDaily.toString());
        assertThat(oneVendor.get(0).get("cost").toString()).isEqualTo("245.00");
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> days(JsonNode days) {
        List<String> result = new ArrayList<>();
        days.forEach(d -> result.add(d.get("date").asString() + " orders=" + d.get("orders").asLong()
                + " gmv=" + d.get("gmv") + " discounts=" + d.get("discounts") + " refunds=" + d.get("refunds")
                + " net=" + d.get("net")));
        return result;
    }

    private static List<String> stores(JsonNode stores) {
        List<String> result = new ArrayList<>();
        stores.forEach(s -> result.add(s.get("rank").asInt() + " " + s.get("storeName").asString()
                + " " + s.get("storeType").asString() + " orders=" + s.get("orders").asLong() + " gmv="
                + s.get("gmv") + " aov=" + s.get("averageOrderValue")));
        return result;
    }

    private static List<String> products(JsonNode products) {
        List<String> result = new ArrayList<>();
        products.forEach(p -> result.add(p.get("rank").asInt() + " " + p.get("name").asString()
                + " " + p.get("itemType").asString() + " " + p.get("storeName").asString() + " units="
                + p.get("units").asLong() + " revenue=" + p.get("revenue") + " orders=" + p.get("orders").asLong()
                + " returned=" + p.get("returnedUnits").asLong()));
        return result;
    }

    private static List<String> targets(JsonNode list) {
        List<String> result = new ArrayList<>();
        list.forEach(t -> {
            List<Integer> distribution = new ArrayList<>();
            t.get("distribution").forEach(n -> distribution.add(n.asInt()));
            result.add(t.get("rank").asInt() + " " + t.get("targetType").asString() + " "
                    + t.get("targetName").asString() + " " + t.get("averageRating") + " ("
                    + t.get("ratingCount").asLong() + ") " + distribution);
        });
        return result;
    }
}
