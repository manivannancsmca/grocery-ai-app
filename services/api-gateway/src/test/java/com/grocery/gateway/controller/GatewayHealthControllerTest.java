package com.grocery.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The load balancer's readiness check on the public port (the actuator listens only on the management port). */
class GatewayHealthControllerTest {

    @Test
    void upWhileTheGatewayAcceptsTraffic() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        var response = new GatewayHealthController(availability).health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void outOfServiceWhileItRefusesTrafficSoTheLoadBalancerDrainsIt() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        var response = new GatewayHealthController(availability).health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "OUT_OF_SERVICE");
    }
}
