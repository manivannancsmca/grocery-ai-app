package com.grocery.payment.gateway;

import com.grocery.payment.gateway.PaymentGateway.NewOrder;
import com.grocery.payment.gateway.PaymentGateway.NewRefund;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefundStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signatures, paise conversion, Razorpay JSON reading and the simulator, without Spring. */
class GatewayUnitTest {

    @Test
    void checkoutSignatureIsHexHmacOfOrderPipePayment() {
        // HMAC_SHA256(orderId + "|" + paymentId, keySecret) — hmacHex itself is checked against RFC 4231 below
        String expected = Signatures.hmacHex("secret", "order_abc|pay_xyz".getBytes(StandardCharsets.UTF_8));
        assertThat(Signatures.checkout("order_abc", "pay_xyz", "secret")).isEqualTo(expected).hasSize(64)
                .matches("[0-9a-f]+");
        assertThat(Signatures.checkoutMatches("order_abc", "pay_xyz", expected, "secret")).isTrue();
        assertThat(Signatures.checkoutMatches("order_abc", "pay_xyz", expected.toUpperCase(), "secret")).isTrue();
        assertThat(Signatures.checkoutMatches("order_abc", "pay_xyz", expected, "other")).isFalse();
        assertThat(Signatures.checkoutMatches("order_abc", "pay_other", expected, "secret")).isFalse();
        assertThat(Signatures.checkoutMatches("order_abc", "pay_xyz", null, "secret")).isFalse();
        assertThat(Signatures.checkoutMatches("order_abc", "pay_xyz", expected, "")).isFalse();
    }

    @Test
    void knownHmacVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertThat(Signatures.hmacHex("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void webhookSignatureCoversTheRawBytes() {
        byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String signature = Signatures.hmacHex("wh", body);
        assertThat(Signatures.webhookMatches(body, signature, "wh")).isTrue();
        assertThat(Signatures.webhookMatches("{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8),
                signature, "wh")).isFalse();
        assertThat(Signatures.webhookMatches(body, signature, null)).isFalse();      // no secret: nothing accepted
        assertThat(Signatures.webhookMatches(body, null, "wh")).isFalse();
    }

    @Test
    void paise() {
        assertThat(Paise.of(new BigDecimal("125.00"))).isEqualTo(12500);
        assertThat(Paise.of(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(Paise.of(new BigDecimal("49.9"))).isEqualTo(4990);
        assertThat(Paise.toRupees(12550)).isEqualByComparingTo("125.50");
        assertThatThrownBy(() -> Paise.of(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void razorpayJsonShapes() {
        JsonMapper json = JsonMapper.builder().build();
        ProviderPayment payment = RazorpayJson.payment(json.readTree("""
                {"id":"pay_1","entity":"payment","amount":12500,"currency":"INR","status":"failed","order_id":"order_1",
                 "method":"card","error_code":"BAD_REQUEST_ERROR","error_description":"Declined","extra":{"x":1}}"""));
        assertThat(payment.id()).isEqualTo("pay_1");
        assertThat(payment.orderId()).isEqualTo("order_1");
        assertThat(payment.status()).isEqualTo(ProviderPaymentStatus.FAILED);
        assertThat(payment.amount()).isEqualByComparingTo("125.00");
        assertThat(payment.errorDescription()).isEqualTo("Declined");
        assertThat(ProviderPaymentStatus.of("refunded").wasCaptured()).isTrue();
        assertThat(ProviderPaymentStatus.of(null)).isEqualTo(ProviderPaymentStatus.CREATED);
        // captured, then refunded: still a capture; an authorization Razorpay released: never paid
        assertThat(RazorpayJson.payment(json.readTree("{\"id\":\"pay_2\",\"status\":\"refunded\",\"captured\":true}"))
                .status()).isEqualTo(ProviderPaymentStatus.REFUNDED);
        assertThat(RazorpayJson.payment(json.readTree("{\"id\":\"pay_3\",\"status\":\"refunded\",\"captured\":false}"))
                .status()).isEqualTo(ProviderPaymentStatus.CREATED);

        var refund = RazorpayJson.refund(json.readTree("""
                {"id":"rfnd_1","payment_id":"pay_1","amount":5000,"status":"processed","notes":{"refundId":"r-1"}}"""));
        assertThat(refund.status()).isEqualTo(ProviderRefundStatus.PROCESSED);
        assertThat(refund.amount()).isEqualByComparingTo("50.00");
        assertThat(refund.refundId()).isEqualTo("r-1");
        assertThat(RazorpayJson.refund(json.readTree("{\"id\":\"rfnd_2\",\"notes\":[]}")).status())
                .isEqualTo(ProviderRefundStatus.PENDING);
    }

    @Test
    void simulatorBehavesLikeRazorpayWithAutoCapture() {
        SimulatorGateway simulator = new SimulatorGateway();
        var order = simulator.createOrder(new NewOrder(new BigDecimal("125.00"), "INR", "GA-1", Map.of()));
        assertThat(order.id()).startsWith("order_");

        ProviderPayment declined = simulator.simulatePayment(order.id(), order.amount(), "INR", false);
        assertThat(declined.status()).isEqualTo(ProviderPaymentStatus.FAILED);
        ProviderPayment paid = simulator.simulatePayment(order.id(), order.amount(), "INR", true);
        assertThat(paid.status()).isEqualTo(ProviderPaymentStatus.CAPTURED);
        assertThat(simulator.listOrderPayments(order.id())).hasSize(2);
        assertThatThrownBy(() -> simulator.simulatePayment(order.id(), order.amount(), "INR", true))
                .isInstanceOf(ProviderRejectedException.class);

        var refund = simulator.createRefund(new NewRefund(paid.id(), new BigDecimal("25.00"), "key-1",
                Map.of("refundId", "r-1")));
        assertThat(refund.status()).isEqualTo(ProviderRefundStatus.PROCESSED);
        assertThat(simulator.createRefund(new NewRefund(paid.id(), new BigDecimal("25.00"), "key-1", Map.of())).id())
                .isEqualTo(refund.id());                                   // same idempotency key: same refund
        assertThatThrownBy(() -> simulator.createRefund(new NewRefund(paid.id(), new BigDecimal("100.01"), "key-2",
                Map.of()))).isInstanceOf(ProviderRejectedException.class);
        // an order unknown after a restart can still be paid (a payment row created before the restart)
        assertThat(simulator.simulatePayment("order_beforeRestart", BigDecimal.TEN, "INR", true).status())
                .isEqualTo(ProviderPaymentStatus.CAPTURED);
    }

    /** Phase 10 reconciliation asks the provider for an order's payments: the simulator answers from its memory. */
    @Test
    void theSimulatorListsAnOrdersPaymentsForReconciliation() {
        SimulatorGateway simulator = new SimulatorGateway();
        var order = simulator.createOrder(new NewOrder(new BigDecimal("359.00"), "INR", "GA-2", Map.of()));
        var other = simulator.createOrder(new NewOrder(new BigDecimal("10.00"), "INR", "GA-3", Map.of()));
        assertThat(simulator.listOrderPayments(order.id())).isEmpty();               // nobody paid yet

        ProviderPayment paid = simulator.simulatePayment(order.id(), order.amount(), "INR", true);
        simulator.simulatePayment(other.id(), other.amount(), "INR", false);

        assertThat(simulator.listOrderPayments(order.id())).singleElement().satisfies(payment -> {
            assertThat(payment.id()).isEqualTo(paid.id());
            assertThat(payment.orderId()).isEqualTo(order.id());
            assertThat(payment.status()).isEqualTo(ProviderPaymentStatus.CAPTURED);
            assertThat(payment.amount()).isEqualByComparingTo("359.00");
        });
        assertThat(simulator.listOrderPayments("order_unknown")).isEmpty();
    }
}
