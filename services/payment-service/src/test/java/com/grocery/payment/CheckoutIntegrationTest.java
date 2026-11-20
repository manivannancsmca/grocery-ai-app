package com.grocery.payment;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/payments/orders/{orderId}/checkout}, {@code GET /api/payments/orders/{orderId}} and access rules. */
class CheckoutIntegrationTest extends AbstractIntegrationTest {

    @Test
    void checkoutSessionCarriesEverythingRazorpayCheckoutNeeds() throws Exception {
        UUID orderId = payableOrder("125.00");

        JsonNode session = checkout(orderId);

        assertThat(session.get("provider").asString()).isEqualTo("RAZORPAY");
        assertThat(session.get("keyId").asString()).isEqualTo(KEY_ID);
        assertThat(session.get("razorpayOrderId").asString()).isEqualTo(razorpayOrderId(orderId));
        assertThat(session.get("amount").isIntegralNumber()).isTrue();
        assertThat(session.get("amount").asLong()).isEqualTo(12500);
        assertThat(session.get("displayAmount").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(session.get("currency").asString()).isEqualTo("INR");
        assertThat(session.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(session.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
        assertThat(session.get("merchantName").asString()).isEqualTo("Grocery AI");
        assertThat(session.get("description").asString()).isEqualTo("Order " + orderNumber(orderId));
        assertThat(session.get("prefill").get("name").asString()).isEqualTo(nameOf(customer));
        assertThat(session.get("prefill").get("email").asString()).isEqualTo(emailOf(customer));
        assertThat(session.get("prefill").get("contact").asString()).isEqualTo("+919876543210");
        assertThat(Instant.parse(session.get("expiresAt").asString())).isAfter(Instant.now());
        assertThat(session.toString()).doesNotContain(KEY_SECRET);
        assertThat(session.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "provider", "keyId", "razorpayOrderId", "amount", "displayAmount", "currency", "orderId",
                "orderNumber", "merchantName", "description", "prefill", "expiresAt");
    }

    @Test
    void noPaymentYetIsNotReady() throws Exception {
        checkoutRequest(Ids.newId(), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_READY"));
    }

    @Test
    void anotherCustomersOrderIsNotFound() throws Exception {
        UUID orderId = payableOrder("10.00");
        checkoutRequest(orderId, otherCustomer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
        mvc.perform(get("/api/payments/orders/{id}", orderId).with(customer(otherCustomer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void capturedExpiredAndCancelledPaymentsCannotBeCheckedOut() throws Exception {
        UUID paid = payableOrder("20.00");
        String rzp = razorpayOrderId(paid);
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "upi", 2000)).andExpect(status().isOk());
        checkoutRequest(paid, customer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_ALREADY_CAPTURED"));

        UUID overdue = payableOrder("20.00");
        endWindow(overdue);                                         // window over, poller not run yet
        checkoutRequest(overdue, customer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_EXPIRED"));
        expiryPoller.run();                                         // now EXPIRED
        assertThat(paymentStatus(overdue)).isEqualTo("EXPIRED");
        checkoutRequest(overdue, customer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_EXPIRED"));

        UUID cancelled = payableOrder("20.00");
        cancelOrder(cancelled);
        checkoutRequest(cancelled, customer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_CANCELLED"));
    }

    @Test
    void razorpayUnreachableWhileCreatingItsOrderIs503() throws Exception {
        UUID orderId = Ids.newId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_ORDER, 503, 2);
        try {
            orderListener.on(awaitingPayment(newEventId(), orderId, customer, "10.00"));
        } catch (RuntimeException expected) {
            // the consumer retries later
        }
        checkoutRequest(orderId, customer)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertThat(paymentStatus(orderId)).isEqualTo("CREATED");
        // Razorpay is back
        assertThat(checkout(orderId).get("razorpayOrderId").asString()).isEqualTo(razorpayOrderId(orderId));
    }

    @Test
    void onlyCustomersCheckOutAndAnonymousCallersAre401() throws Exception {
        UUID orderId = payableOrder("10.00");
        mvc.perform(get("/api/payments/orders/{id}/checkout", orderId).with(as(financeAdmin, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/orders/{id}/checkout", orderId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/orders/{id}", orderId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/orders/{id}", orderId).with(as(customer, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/razorpay/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/orders/{id}/checkout", "not-a-uuid").with(customer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void paymentViewForTheCustomerAndAnyAdmin() throws Exception {
        UUID orderId = payableOrder("125.00");

        JsonNode view = paymentView(orderId);
        assertThat(view.get("status").asString()).isEqualTo("PENDING");
        assertThat(view.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(view.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
        assertThat(view.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(view.get("amount").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(view.get("refundedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(view.get("refundStatus").asString()).isEqualTo("NONE");
        assertThat(view.get("razorpayPaymentId").isNull()).isTrue();
        assertThat(view.get("method").isNull()).isTrue();
        assertThat(view.get("capturedAt").isNull()).isTrue();
        assertThat(view.get("attempts")).isEmpty();
        assertThat(view.get("refunds")).isEmpty();
        assertThat(view.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "id", "orderId", "orderNumber", "customerId", "status", "amount", "currency", "method",
                "razorpayOrderId", "razorpayPaymentId", "refundedAmount", "refundStatus", "expiresAt", "capturedAt",
                "createdAt", "attempts", "refunds");

        for (String role : new String[]{"SUPPORT", "DELIVERY_MANAGER", "FINANCE_ADMIN"}) {
            mvc.perform(get("/api/payments/orders/{id}", orderId).with(as(UUID.randomUUID(), role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()));
        }
    }

    @Test
    void simulatorEndpointIs404InRazorpayMode() throws Exception {
        UUID orderId = payableOrder("10.00");
        mvc.perform(post("/api/payments/simulator/orders/{id}/pay", orderId).with(customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
