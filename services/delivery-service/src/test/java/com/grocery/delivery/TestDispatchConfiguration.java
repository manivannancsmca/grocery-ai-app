package com.grocery.delivery;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application's clock with one tests can pin, and the delivery.location producer with a recorder. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDispatchConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    RecordingLocationSender recordingLocationSender() {
        return new RecordingLocationSender();
    }
}
