package com.grocery.vendor;

import com.grocery.common.id.Ids;
import com.grocery.vendor.entity.RejectionReason;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sales report (contract §3) on a small data set whose numbers can be checked by hand. Times are India time
 * (UTC+05:30); orders count on the India-time day they were received.
 */
class SalesReportIntegrationTest extends StoreOrderTestSupport {

    // Items (unit prices): Rice 50.00, Dal 120.00, Ghee 300.00, Salt 20.00, Tea 150.00, Oil 200.00
    private final UUID rice = Ids.newId();
    private final UUID dal = Ids.newId();
    private final UUID ghee = Ids.newId();
    private final UUID salt = Ids.newId();
    private final UUID tea = Ids.newId();
    private final UUID oil = Ids.newId();

    /** An order of {@code store} received at {@code at} (UTC). */
    private UUID receivedAt(Store store, String at, Line... lines) {
        clock.set(Instant.parse(at));
        return confirm(store, List.of(lines));
    }

    /** Accepts the order one minute after it was received (well before its deadline). */
    private void acceptLater(Store store, UUID orderId, String receivedAt) {
        clock.set(Instant.parse(receivedAt).plusSeconds(60));
        storeOrders.accept(vendorUser(store), orderId, null);
    }

    private ResultActions report(Store store, String from, String to) throws Exception {
        var request = get("/api/vendor/reports/sales").with(vendor(store));
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        return mvc.perform(request);
    }

    private static List<String> daily(JsonNode report) {
        return StreamSupport.stream(report.get("daily").spliterator(), false)
                .map(day -> day.get("date").asString() + " " + day.get("ordersReceived").asLong() + "/"
                        + day.get("ordersAccepted").asLong() + " " + money(day.get("grossSales")))
                .toList();
    }

    private static List<String> topItems(JsonNode report) {
        return StreamSupport.stream(report.get("topItems").spliterator(), false)
                .map(item -> item.get("name").asString() + " " + item.get("quantity").asLong() + " "
                        + money(item.get("sales")))
                .toList();
    }

    /** A money amount with 2 decimals (the JSON tree reads numbers as doubles; the raw text is checked separately). */
    private static String money(JsonNode amount) {
        return amount.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Test
    void theReportCountsTheOrdersReceivedInTheRangeByTheContractRules() throws Exception {
        Store store = newStore();
        Store neighbour = newStore();

        // 1) 01 Oct 10:00 — accepted, ready: sold (Rice 2 + Dal 1 = 220.00, 3 units)
        String t1 = "2026-10-01T04:30:00Z";
        UUID o1 = receivedAt(store, t1, line(rice, "Basmati Rice 1kg", 2, "50.00"), line(dal, "Toor Dal", 1, "120.00"));
        acceptLater(store, o1, t1);
        storeOrders.ready(vendorUser(store), o1);
        // 2) 01 Oct 12:00 — accepted, then cancelled by an admin: accepted and cancelled, not sold (Ghee 300.00)
        String t2 = "2026-10-01T06:30:00Z";
        UUID o2 = receivedAt(store, t2, line(ghee, "Ghee", 1, "300.00"));
        acceptLater(store, o2, t2);
        orderListener.on(orderEvent("OrderCancelled", newEventId(), o2, cancelledPayload(o2, store.vendorId(),
                GROCERY, "ACCEPTED", "ADMIN_CANCELLED", "ADMIN", "2026-10-01T07:00:00Z")));
        // 3) 02 Oct 09:00 — rejected by the store (Salt 5 = 100.00)
        UUID o3 = receivedAt(store, "2026-10-02T03:30:00Z", line(salt, "Salt", 5, "20.00"));
        storeOrders.reject(vendorUser(store), o3, RejectionReason.TOO_BUSY, null);
        // 4) 02 Oct 23:59:59.999999 — the last moment of the day: accepted, packing: sold (Tea 2 = 300.00)
        String t4 = "2026-10-02T18:29:59.999999Z";
        UUID o4 = receivedAt(store, t4, line(tea, "Masala Tea", 2, "150.00"));
        acceptLater(store, o4, t4);
        storeOrders.preparing(vendorUser(store), o4);
        // 5) 03 Oct 00:00:00 — the first moment of the next day: accepted: sold (Rice 4 + Oil 1 = 400.00, 5 units)
        String t5 = "2026-10-02T18:30:00Z";
        UUID o5 = receivedAt(store, t5, line(rice, "Basmati Rice", 4, "50.00"), line(oil, "Sunflower Oil", 1, "200.00"));
        acceptLater(store, o5, t5);
        // 6) 03 Oct 11:00 — nobody accepted it: auto-rejected at the deadline (Dal 1 = 120.00)
        String t6 = "2026-10-03T05:30:00Z";
        UUID o6 = receivedAt(store, t6, line(dal, "Toor Dal", 1, "120.00"));
        pollerAt(Instant.parse(t6).plus(Duration.ofMinutes(6))).rejectExpired();
        assertThat(orderStatus(o6)).isEqualTo("REJECTED");
        // 7) 04 Oct 15:00 — cancelled by the customer before acceptance (Rice 1 = 50.00)
        UUID o7 = receivedAt(store, "2026-10-04T09:30:00Z", line(rice, "Basmati Rice", 1, "50.00"));
        cancel(newEventId(), store, o7, "2026-10-04T09:31:00Z");
        // 8) 05 Oct 18:00 — still NEW (never accepted): only received (Oil 2 + Salt 1 = 420.00)
        receivedAt(store, "2026-10-05T12:30:00Z", line(oil, "Sunflower Oil", 2, "200.00"), line(salt, "Salt", 1, "20.00"));
        // 9) 07 Oct 20:00 — accepted: sold (Ghee 2 + Salt 1 = 620.00, 3 units)
        String t9 = "2026-10-07T14:30:00Z";
        UUID o9 = receivedAt(store, t9, line(ghee, "Ghee", 2, "300.00"), line(salt, "Salt", 1, "20.00"));
        acceptLater(store, o9, t9);
        // Outside the range: 30 Sep 23:00 and 08 Oct 00:30, both accepted; and another vendor's order inside it
        String before = "2026-09-30T17:30:00Z";
        acceptLater(store, receivedAt(store, before, line(rice, "Basmati Rice", 10, "50.00")), before);
        String after = "2026-10-07T19:00:00Z";
        acceptLater(store, receivedAt(store, after, line(ghee, "Ghee", 1, "300.00")), after);
        String theirs = "2026-10-02T04:30:00Z";
        acceptLater(neighbour, receivedAt(neighbour, theirs, line(rice, "Basmati Rice", 7, "50.00")), theirs);
        clock.reset();

        String response = report(store, "2026-10-01", "2026-10-07")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode week = json.readTree(response);
        assertThat(fieldNames(week)).containsExactly("from", "to", "timezone", "currency", "ordersReceived",
                "ordersAccepted", "ordersRejected", "autoRejected", "ordersCancelled", "acceptanceRate", "grossSales",
                "itemsSold", "averageOrderValue", "daily", "topItems");
        assertThat(week.get("from").asString()).isEqualTo("2026-10-01");
        assertThat(week.get("to").asString()).isEqualTo("2026-10-07");
        assertThat(week.get("timezone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(week.get("currency").asString()).isEqualTo("INR");
        assertThat(week.get("ordersReceived").asLong()).isEqualTo(9);       // 1–9
        assertThat(week.get("ordersAccepted").asLong()).isEqualTo(5);       // 1, 2 (later cancelled), 4, 5, 9
        assertThat(week.get("ordersRejected").asLong()).isEqualTo(2);       // 3, 6
        assertThat(week.get("autoRejected").asLong()).isEqualTo(1);         // 6
        assertThat(week.get("ordersCancelled").asLong()).isEqualTo(2);      // 2 (after acceptance), 7 (before)
        assertThat(week.get("acceptanceRate").decimalValue()).isEqualByComparingTo("0.56");   // 5 / 9
        assertThat(week.get("grossSales").decimalValue()).isEqualByComparingTo("1540.00");  // 220 + 300 + 400 + 620
        assertThat(week.get("itemsSold").asLong()).isEqualTo(13);           // 3 + 2 + 5 + 3
        assertThat(week.get("averageOrderValue").decimalValue()).isEqualByComparingTo("385.00");   // 1540 / 4
        assertThat(response).contains("\"acceptanceRate\":0.56", "\"grossSales\":1540.00",
                "\"averageOrderValue\":385.00");
        assertThat(daily(week)).containsExactly(
                "2026-10-01 2/2 220.00",
                "2026-10-02 2/1 300.00",                                     // 3, 4 (23:59:59.999999)
                "2026-10-03 2/1 400.00",                                     // 5 (00:00:00), 6
                "2026-10-04 1/0 0.00",
                "2026-10-05 1/0 0.00",
                "2026-10-06 0/0 0.00",
                "2026-10-07 1/1 620.00");
        assertThat(fieldNames(week.get("daily").get(0)))
                .containsExactly("date", "ordersReceived", "ordersAccepted", "grossSales");
        // Units in the sold orders 1, 4, 5, 9; Salt (1 unit, 20.00) is sixth; the latest name of Rice is shown
        assertThat(topItems(week)).containsExactly(
                "Basmati Rice 6 300.00",
                "Ghee 2 600.00",
                "Masala Tea 2 300.00",
                "Sunflower Oil 1 200.00",
                "Toor Dal 1 120.00");
        assertThat(fieldNames(week.get("topItems").get(0))).containsExactly("itemId", "name", "quantity", "sales");
        assertThat(week.get("topItems").get(0).get("itemId").asString()).isEqualTo(rice.toString());

        // One India-time day: 02 Oct ends at 23:59:59.999999 and 03 Oct starts at 00:00
        JsonNode second = body(report(store, "2026-10-02", "2026-10-02").andExpect(status().isOk()));
        assertThat(second.get("ordersReceived").asLong()).isEqualTo(2);
        assertThat(second.get("ordersRejected").asLong()).isEqualTo(1);
        assertThat(second.get("autoRejected").asLong()).isZero();
        assertThat(second.get("grossSales").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(daily(second)).containsExactly("2026-10-02 2/1 300.00");
        JsonNode third = body(report(store, "2026-10-03", "2026-10-03").andExpect(status().isOk()));
        assertThat(third.get("ordersReceived").asLong()).isEqualTo(2);
        assertThat(third.get("ordersAccepted").asLong()).isEqualTo(1);
        assertThat(third.get("autoRejected").asLong()).isEqualTo(1);
        assertThat(third.get("acceptanceRate").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(third.get("itemsSold").asLong()).isEqualTo(5);
        assertThat(third.get("averageOrderValue").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(topItems(third)).containsExactly("Basmati Rice 4 200.00", "Sunflower Oil 1 200.00");

        // Default: the last 7 days including today (India time) — pinned to 07 Oct 20:30
        clock.set(Instant.parse("2026-10-07T15:00:00Z"));
        JsonNode lastWeek = body(report(store, null, null).andExpect(status().isOk()));
        assertThat(lastWeek.get("from").asString()).isEqualTo("2026-10-01");
        assertThat(lastWeek.get("to").asString()).isEqualTo("2026-10-07");
        assertThat(lastWeek.get("ordersReceived").asLong()).isEqualTo(9);
        assertThat(lastWeek.get("grossSales").decimalValue()).isEqualByComparingTo("1540.00");
        // Just before midnight UTC it is already the next day in India
        clock.set(Instant.parse("2026-10-07T18:45:00Z"));                              // 08 Oct 00:15 IST
        JsonNode shifted = body(report(store, null, null));
        assertThat(shifted.get("from").asString()).isEqualTo("2026-10-02");
        assertThat(shifted.get("to").asString()).isEqualTo("2026-10-08");
        assertThat(shifted.get("ordersReceived").asLong()).isEqualTo(8);                // 3–9 and the 08 Oct one
        // Only "to": the 7 days ending then
        JsonNode upTo = body(report(store, null, "2026-10-03"));
        assertThat(upTo.get("from").asString()).isEqualTo("2026-09-27");
        assertThat(upTo.get("ordersReceived").asLong()).isEqualTo(7);                   // 30 Sep + 1–6

        // The neighbour sees only its own order
        JsonNode theirsReport = body(mvc.perform(get("/api/vendor/reports/sales").param("from", "2026-10-01")
                .param("to", "2026-10-07").with(vendor(neighbour))).andExpect(status().isOk()));
        assertThat(theirsReport.get("ordersReceived").asLong()).isEqualTo(1);
        assertThat(theirsReport.get("grossSales").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(topItems(theirsReport)).containsExactly("Basmati Rice 7 350.00");
    }

    @Test
    void aStoreWithoutOrdersGetsZerosForEveryDay() throws Exception {
        Store store = newStore();
        String response = report(store, "2026-10-01", "2026-10-03").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode empty = json.readTree(response);
        assertThat(empty.get("ordersReceived").asLong()).isZero();
        assertThat(empty.get("acceptanceRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("grossSales").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("averageOrderValue").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("itemsSold").asLong()).isZero();
        assertThat(daily(empty)).containsExactly("2026-10-01 0/0 0.00", "2026-10-02 0/0 0.00", "2026-10-03 0/0 0.00");
        assertThat(empty.get("topItems")).isEmpty();
        assertThat(response).contains("\"grossSales\":0.00", "\"acceptanceRate\":0.00");

        clock.set(Instant.parse("2026-11-11T06:00:00Z"));
        JsonNode defaults = body(report(store, null, null).andExpect(status().isOk()));
        assertThat(defaults.get("from").asString()).isEqualTo("2026-11-05");
        assertThat(defaults.get("to").asString()).isEqualTo("2026-11-11");
        assertThat(defaults.get("daily")).hasSize(7);
    }

    @Test
    void theRangeIsValidated() throws Exception {
        Store store = newStore();
        report(store, "2026-10-08", "2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        report(store, "2026-07-01", "2026-09-30")                                    // 92 days
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(92));
        report(store, "2026-06-30", "2026-09-30")                                    // 93 days
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        report(store, "2026-13-01", "2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        report(store, "01-10-2026", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        clock.set(Instant.parse("2026-10-07T06:00:00Z"));
        report(store, "2026-10-09", null)                                             // only "from", after today
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        report(store, "2026-10-05", null)                                             // only "from": up to today
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("2026-10-07"));
    }

    @Test
    void onlyAnApprovedVendorGetsItsReport() throws Exception {
        Store store = newStore();
        mvc.perform(get("/api/vendor/reports/sales").with(as(store.ownerUserId(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/reports/sales").with(as(UUID.randomUUID(), store.vendorId(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/reports/sales")).andExpect(status().isUnauthorized());
    }
}
