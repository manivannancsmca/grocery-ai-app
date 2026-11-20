package com.grocery.delivery;

import com.grocery.delivery.dispatch.entity.FailureReason;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery exports: a partner's own history (only theirs, with the list's filters), the admin monitor with its
 * filters and the partner list, where contact details reach only support and operations admins.
 */
class DeliveryExportIntegrationTest extends DispatchTestSupport {

    private List<String> csv(String path, org.springframework.test.web.servlet.request.RequestPostProcessor caller)
            throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path).with(caller)).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=");
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private Order finish(UUID rider, Instant finishedAt, boolean failed) {
        clock.set(finishedAt.minusSeconds(1200));
        report(rider, at(0.5, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        readyForPickup(order);
        UUID deliveryId = deliveryId(order);
        clock.set(finishedAt.minusSeconds(600));
        partnerDeliveries.pickUp(rider, deliveryId);
        clock.set(finishedAt);
        if (failed) {
            partnerDeliveries.fail(rider, deliveryId, FailureReason.CUSTOMER_REFUSED, null);
        } else {
            partnerDeliveries.deliver(rider, deliveryId);
        }
        return order;
    }

    @Test
    void aPartnerExportsTheirOwnHistoryNewestFirst() throws Exception {
        UUID rider = onlinePartnerAt("Meena Raj", at(0.5, 0));
        Order older = finish(rider, T0.minus(Duration.ofDays(2)), false);
        Order newer = finish(rider, T0.minus(Duration.ofHours(1)), true);
        UUID other = onlinePartnerAt("Vijay Kumar", at(0.5, 0));
        Order othersOrder = finish(other, T0.minus(Duration.ofHours(2)), false);

        List<String> lines = csv("/api/delivery/history/export?format=csv", as(rider, PARTNER));

        assertThat(lines.getFirst()).isEqualTo("Order number,Status,Store,Drop area,Trip (km),Picked up,Finished");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith(newer.orderNumber() + ",Failed,");
        assertThat(lines.get(2)).startsWith(older.orderNumber() + ",Delivered,");
        assertThat(String.join("\n", lines)).doesNotContain(othersOrder.orderNumber());

        mvc.perform(get("/api/delivery/history/export?format=csv").with(as(UUID.randomUUID(), CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPartnersHistoryExportHasTheListsFilters() throws Exception {
        UUID rider = onlinePartnerAt("Lakshmi Narayan", at(0.5, 0));
        // T0 = 2026-11-10 12:00 India time
        Order lastWeek = finish(rider, T0.minus(Duration.ofDays(6)), false);
        Order failedToday = finish(rider, T0.minus(Duration.ofHours(1)), true);
        Order deliveredToday = finish(rider, T0.minus(Duration.ofMinutes(30)), false);
        clock.set(T0);

        List<String> today = csv("/api/delivery/history/export?format=csv&from=2026-11-10&to=2026-11-10",
                as(rider, PARTNER));
        assertThat(today).hasSize(3);
        assertThat(today.get(1)).startsWith(deliveredToday.orderNumber() + ",Delivered,");
        assertThat(today.get(2)).startsWith(failedToday.orderNumber() + ",Failed,");

        List<String> delivered = csv("/api/delivery/history/export?format=csv&status=DELIVERED", as(rider, PARTNER));
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(1)).startsWith(deliveredToday.orderNumber() + ",");
        assertThat(delivered.get(2)).startsWith(lastWeek.orderNumber() + ",");

        mvc.perform(get("/api/delivery/history/export?format=csv&status=CANCELLED").with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/delivery/history/export?format=csv&from=2026-11-10&to=2026-11-01")
                        .with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void theAdminMonitorExportFollowsItsFiltersAndHidesContactsFromFinance() throws Exception {
        UUID rider = onlinePartnerAt("Irfan Khan", at(0.5, 0));
        Order delivered = finish(rider, T0.minus(Duration.ofHours(3)), false);
        Order failed = finish(rider, T0.minus(Duration.ofHours(2)), true);

        List<String> support = csv("/api/admin/deliveries/export?format=csv&partnerId=" + rider,
                as(admin, "SUPPORT"));
        assertThat(support.getFirst()).contains(",Recipient,Recipient phone,Drop address,");
        assertThat(support).hasSize(3);
        assertThat(support.get(1)).startsWith(failed.orderNumber() + ",").contains(",Irfan Khan," + rider + ",");
        assertThat(support.get(2)).startsWith(delivered.orderNumber() + ",");

        List<String> finance = csv("/api/admin/deliveries/export?format=csv&status=FAILED&partnerId=" + rider,
                as(admin, "FINANCE_ADMIN"));
        assertThat(finance.getFirst()).doesNotContain("Recipient").doesNotContain("Drop address");
        assertThat(finance).hasSize(2);
        assertThat(finance.get(1)).startsWith(failed.orderNumber() + ",").contains(",Customer refused");

        mvc.perform(get("/api/admin/deliveries/export?format=csv&sort=recipientPhone").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/deliveries/export?format=csv").with(as(rider, PARTNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePartnerListNeverCarriesLicenceNumbers() throws Exception {
        UUID rider = approvedPartner("Gopal Exportwala");

        List<String> ops = csv("/api/admin/delivery-partners/export?format=csv&query=exportwala",
                as(admin, "OPS_ADMIN"));
        assertThat(ops.getFirst()).isEqualTo("Name,Email,Phone,City,Status,Vehicle,Status reason,Submitted,Approved,"
                + "Registered,Last change");
        assertThat(ops).hasSize(2);
        // a value starting with + gets an apostrophe in CSV, so a spreadsheet never runs it as a formula
        assertThat(ops.get(1)).startsWith("Gopal Exportwala,rider-" + rider + "@example.com,'+919800000000,Bengaluru,"
                + "Approved,Scooter,");
        assertThat(String.join("\n", ops)).doesNotContain("KA0120201234567").doesNotContain("KA01AB1234");

        List<String> manager = csv("/api/admin/delivery-partners/export?format=csv&query=exportwala",
                as(admin, "DELIVERY_MANAGER"));
        assertThat(manager.getFirst()).isEqualTo("Name,City,Status,Vehicle,Status reason,Submitted,Approved,"
                + "Registered,Last change");
    }
}
