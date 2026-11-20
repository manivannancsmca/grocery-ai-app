package com.grocery.payment;

import com.grocery.payment.gateway.SimulatorGateway;
import org.springframework.test.context.TestPropertySource;

/**
 * The same setup in <b>simulator mode</b> (contract §3.3): no Razorpay keys, {@code grocery.payment.provider=simulator}.
 * Without the {@code docker} / {@code local} profile simulator mode refuses to start, so these tests allow it
 * explicitly ({@code grocery.payment.simulator.allow-any-profile}). Its own application context.
 */
@TestPropertySource(properties = {
        "grocery.payment.provider=simulator",
        "grocery.payment.simulator.allow-any-profile=true",
        // explicit, although inherited: this context, too, publishes Avro against the in-memory Schema Registry
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
public abstract class AbstractSimulatorIntegrationTest extends AbstractIntegrationTest {

    @Override
    protected String keySecret() {
        return SimulatorGateway.KEY_SECRET;
    }

    @Override
    protected String webhookSecret() {
        return SimulatorGateway.WEBHOOK_SECRET;
    }
}
