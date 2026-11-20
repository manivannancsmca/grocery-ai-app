package com.grocery.payment.gateway;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.config.ProviderMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Razorpay's REST API ({@code grocery.payment.razorpay.base-url}, default {@code https://api.razorpay.com/v1}) with
 * HTTP basic auth {@code keyId:keySecret}, short timeouts ({@code grocery.payment.http.*}) and one circuit breaker
 * ({@value #BREAKER}). 4xx answers become {@link ProviderRejectedException} and never open the breaker; timeouts,
 * connection failures, 5xx, 429 and an open breaker become {@link ProviderUnavailableException}.
 *
 * <p>Refunds carry our refund id as {@code receipt}, {@code notes.refundId} and the {@code X-Refund-Idempotency}
 * header, so a retried call after a timeout does not refund twice (header and webhook matching by note: see README).
 */
public class RazorpayGateway implements PaymentGateway {

    public static final String BREAKER = "razorpay";
    static final String REFUND_IDEMPOTENCY_HEADER = "X-Refund-Idempotency";
    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final RestClient client;
    private final CircuitBreaker breaker;
    private final JsonMapper jsonMapper;

    public RazorpayGateway(RestClient.Builder restClientBuilder, PaymentProperties properties,
                           CircuitBreakerFactory<?, ?> breakers, JsonMapper jsonMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.http().connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.http().readTimeout());
        PaymentProperties.Razorpay razorpay = properties.razorpay();
        this.client = restClientBuilder.clone()
                .baseUrl(razorpay.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(razorpay.keyId(), razorpay.keySecret(),
                        StandardCharsets.UTF_8))
                .build();
        this.breaker = breakers.create(BREAKER);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.RAZORPAY;
    }

    @Override
    public ProviderOrder createOrder(NewOrder order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Paise.of(order.amount()));
        body.put("currency", order.currency());
        body.put("receipt", order.receipt());
        body.put("notes", order.notes());
        return RazorpayJson.order(call("create order", () -> client.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class)));
    }

    @Override
    public ProviderPayment fetchPayment(String razorpayPaymentId) {
        return RazorpayJson.payment(call("fetch payment", () -> client.get()
                .uri("/payments/{id}", razorpayPaymentId)
                .retrieve()
                .body(JsonNode.class)));
    }

    @Override
    public ProviderPayment capturePayment(String razorpayPaymentId, BigDecimal amount, String currency) {
        Map<String, Object> body = Map.of("amount", Paise.of(amount), "currency", currency);
        return RazorpayJson.payment(call("capture payment", () -> client.post()
                .uri("/payments/{id}/capture", razorpayPaymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class)));
    }

    @Override
    public List<ProviderPayment> listOrderPayments(String razorpayOrderId) {
        JsonNode collection = call("list order payments", () -> client.get()
                .uri("/orders/{id}/payments", razorpayOrderId)
                .retrieve()
                .body(JsonNode.class));
        List<ProviderPayment> payments = new ArrayList<>();
        JsonNode items = collection.get("items");
        if (items != null && items.isArray()) {
            items.forEach(item -> payments.add(RazorpayJson.payment(item)));
        }
        return payments;
    }

    @Override
    public ProviderRefund createRefund(NewRefund refund) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Paise.of(refund.amount()));
        body.put("receipt", refund.idempotencyKey());
        body.put("notes", refund.notes());
        return RazorpayJson.refund(call("create refund", () -> client.post()
                .uri("/payments/{id}/refund", refund.razorpayPaymentId())
                .header(REFUND_IDEMPOTENCY_HEADER, refund.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class)));
    }

    @Override
    public ProviderRefund fetchRefund(String razorpayRefundId) {
        return RazorpayJson.refund(call("fetch refund", () -> client.get()
                .uri("/refunds/{id}", razorpayRefundId)
                .retrieve()
                .body(JsonNode.class)));
    }

    private JsonNode call(String operation, Supplier<JsonNode> request) {
        return breaker.run(() -> {
            try {
                JsonNode body = request.get();
                if (body == null || body.isNull()) {
                    throw new IllegalStateException("empty response body");
                }
                return body;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw e;                                    // rate limited: temporary
                }
                throw rejected(e);
            }
        }, failure -> {
            ProviderRejectedException rejected = findRejected(failure);
            if (rejected != null) {
                log.info("Razorpay {} rejected: {}", operation, rejected.getMessage());
                throw rejected;
            }
            log.warn("Razorpay {} failed: {}", operation, failure == null ? "unknown" : failure.toString());
            throw new ProviderUnavailableException("Razorpay " + operation + " failed: "
                    + (failure == null ? "unknown" : failure.getClass().getSimpleName()));
        });
    }

    private ProviderRejectedException rejected(HttpClientErrorException e) {
        String code = null;
        String description = e.getStatusText();
        try {
            JsonNode error = jsonMapper.readTree(e.getResponseBodyAsString()).get("error");
            if (error != null) {
                code = RazorpayJson.text(error, "code");
                String text = RazorpayJson.text(error, "description");
                description = text == null ? description : text;
            }
        } catch (RuntimeException unreadable) {
            // keep the status text
        }
        return new ProviderRejectedException(e.getStatusCode().value(), code, description);
    }

    private static ProviderRejectedException findRejected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ProviderRejectedException rejected) {
                return rejected;
            }
        }
        return null;
    }
}
