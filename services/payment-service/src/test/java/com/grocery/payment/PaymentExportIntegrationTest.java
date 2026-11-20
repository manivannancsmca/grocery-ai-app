package com.grocery.payment;

import com.grocery.common.export.RowSource;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.service.PaymentQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/payments/export}: the monitor's filters on every batch, its order or a chosen one, the
 * refunds of each batch, and admins only. Each test moves its payments to a day of its own (long ago), so the date
 * filter keeps other tests' payments out of the shared database.
 */
class PaymentExportIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String HEADER = "Order number,Created,Amount (INR),Status,Refund status,Refunded (INR),"
            + "Refunds,Method,Captured,Payment window ends,Razorpay order,Razorpay payment,Order ID,Customer ID,"
            + "Payment ID";

    private final UUID admin = UUID.randomUUID();

    @Autowired
    private PaymentQueryService queries;

    private MockHttpServletResponse export(String query, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get("/api/admin/payments/export?" + query).with(caller))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** A payment of {@link #customer} captured through verify with {@code method}; returns the order id. */
    private UUID paidOrder(String amount, String method) throws Exception {
        UUID orderId = payableOrder(amount);
        String rzp = razorpayOrderId(orderId);
        long paise = new BigDecimal(amount).movePointRight(2).longValueExact();
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", method, paise)).andExpect(status().isOk());
        return orderId;
    }

    /** Moves the payment's creation to {@code hour}:00 India time on {@code day}. */
    private void createdAt(UUID orderId, LocalDate day, int hour) {
        jdbc().sql("UPDATE payment SET created_at = ? WHERE order_id = UUID_TO_BIN(?)")
                .param(LocalDateTime.ofInstant(day.atTime(hour, 0).atZone(INDIA).toInstant(), ZoneOffset.UTC))
                .param(orderId.toString())
                .update();
    }

    private record Three(UUID refunded, UUID open, UUID upi) {
    }

    /** 10:00 captured by card and partly refunded, 11:00 still open, 12:00 captured by UPI. */
    private Three threePayments(LocalDate day) throws Exception {
        UUID refunded = paidOrder("125.50", "card");
        mvc.perform(post("/api/admin/refunds").with(as(financeAdmin, "FINANCE_ADMIN"))
                        .header("Idempotency-Key", "refund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + refunded + "\",\"amount\":25.00,\"note\":\"Damaged\"}"))
                .andExpect(status().isCreated());
        UUID open = payableOrder("44.00");
        UUID upi = paidOrder("300.00", "upi");
        createdAt(refunded, day, 10);
        createdAt(open, day, 11);
        createdAt(upi, day, 12);
        return new Three(refunded, open, upi);
    }

    @Test
    void theExportFollowsTheMonitorsFiltersAndOrder() throws Exception {
        LocalDate day = LocalDate.of(2001, 2, 3);
        Three payments = threePayments(day);
        String onTheDay = "&from=" + day + "&to=" + day;

        MockHttpServletResponse response = export("format=csv" + onTheDay, as(admin, "SUPPORT"));
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"payments-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo(HEADER);
        assertThat(lines).hasSize(4);
        // newest first, like the list; codes as words, money with two decimals, India time
        assertThat(lines.get(1)).startsWith(orderNumber(payments.upi()) + ",2001-02-03 12:00,300.00,Captured,None,"
                + "0.00,0,UPI,").contains("," + razorpayOrderId(payments.upi()) + ",pay_")
                .endsWith("," + payments.upi() + "," + customer + "," + paymentRow(payments.upi()).get("id"));
        assertThat(lines.get(2)).startsWith(orderNumber(payments.open()) + ",2001-02-03 11:00,44.00,")
                .contains(",None,0.00,0,,,");
        assertThat(lines.get(3)).startsWith(orderNumber(payments.refunded()) + ",2001-02-03 10:00,125.50,Captured,"
                + "Partial,25.00,1,Card,");

        List<String> captured = csvLines(export("format=csv&status=CAPTURED" + onTheDay, as(admin, "OPS_ADMIN")));
        assertThat(captured.subList(1, captured.size())).extracting(line -> line.substring(0, line.indexOf(',')))
                .containsExactly(orderNumber(payments.upi()), orderNumber(payments.refunded()));
        List<String> partial = csvLines(export("format=csv&refundStatus=PARTIAL" + onTheDay,
                as(admin, "FINANCE_ADMIN")));
        assertThat(partial.getFirst()).isEqualTo(HEADER);
        assertThat(partial).hasSize(2);
        assertThat(partial.get(1)).startsWith(orderNumber(payments.refunded()) + ",");
        List<String> oneOrder = csvLines(export("format=csv&orderId=" + payments.open(), as(admin, "SUPPORT")));
        assertThat(oneOrder).hasSize(2);
        assertThat(oneOrder.get(1)).startsWith(orderNumber(payments.open()) + ",");
        assertThat(csvLines(export("format=csv&orderId=" + payments.open() + "&to=" + day.minusDays(1),
                as(admin, "SUPPORT")))).hasSize(1);

        // a chosen order (the page's column keys), then the id
        List<String> byAmount = csvLines(export("format=csv&sort=amount,asc" + onTheDay,
                as(admin, "DELIVERY_MANAGER")));
        assertThat(byAmount.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf(',')))
                .containsExactly(orderNumber(payments.open()), orderNumber(payments.refunded()),
                        orderNumber(payments.upi()));
        List<String> byRefunds = csvLines(export("format=csv&sort=refunds,desc" + onTheDay, as(admin, "SUPPORT")));
        assertThat(byRefunds.get(1)).startsWith(orderNumber(payments.refunded()) + ",");
    }

    @Test
    void keysetBatchesReadEveryPaymentOnceInEachOrder() throws Exception {
        LocalDate day = LocalDate.of(2001, 2, 4);
        Three payments = threePayments(day);
        UUID another = paidOrder("44.00", "netbanking");     // the same amount as the open one: the id breaks the tie
        createdAt(another, day, 13);

        for (String sort : new String[]{null, "order", "amount,desc", "amount", "status", "status,desc",
                "refunds,desc"}) {
            List<UUID> whole = orderIds(queries.adminExport(null, null, null, day, day, sort), 1000);
            assertThat(whole).as(sort).hasSize(4).doesNotHaveDuplicates();
            assertThat(orderIds(queries.adminExport(null, null, null, day, day, sort), 1)).as(sort).isEqualTo(whole);
            assertThat(orderIds(queries.adminExport(null, null, null, day, day, sort), 3)).as(sort).isEqualTo(whole);
        }
        assertThat(orderIds(queries.adminExport(null, null, null, day, day, null), 1))
                .containsExactly(another, payments.upi(), payments.open(), payments.refunded());
        assertThat(orderIds(queries.adminExport(null, null, null, day, day, "status"), 1).getLast())
                .isEqualTo(payments.open());
    }

    private static List<UUID> orderIds(RowSource<PaymentView> rows, int batchSize) throws Exception {
        List<UUID> ids = new ArrayList<>();
        rows.forEachBatch(batchSize, batch -> {
            batch.forEach(view -> ids.add(view.orderId()));
            return true;
        });
        return ids;
    }

    @Test
    void onlyAdminsExportPaymentsAndWrongParametersAreRefused() throws Exception {
        UUID orderId = payableOrder("12.00");

        mvc.perform(get("/api/admin/payments/export?format=csv").with(customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/export?format=csv")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/payments/export?format=docx").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/payments/export?format=csv&sort=customerId").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/payments/export?format=csv&status=PAID").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        MockHttpServletResponse pdf = export("format=pdf&orderId=" + orderId, as(admin, "SUPER_ADMIN"));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MockHttpServletResponse xlsx = export("format=xlsx&orderId=" + orderId, as(admin, "FINANCE_ADMIN"));
        assertThat(xlsx.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
