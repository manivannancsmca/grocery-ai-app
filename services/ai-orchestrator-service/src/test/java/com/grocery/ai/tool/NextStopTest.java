package com.grocery.ai.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** NextStop: where a partner goes next, and the straight-line distance from their last position (Chennai data). */
class NextStopTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Adyar Chettinad Kitchen → a home in Thiruvanmiyur. */
    private static JsonNode delivery(String status) {
        return JSON.readTree("""
                {"orderNumber":"GA-RY6PNP8G","status":"%s",
                 "pickup":{"name":"Adyar Chettinad Kitchen","city":"Chennai","latitude":13.0063,"longitude":80.2574},
                 "drop":{"recipientName":"Divya Krishnan","line1":"22 Kamaraj Nagar 3rd Street","line2":"Thiruvanmiyur",
                         "city":"Chennai","latitude":12.9830,"longitude":80.2594}}
                """.formatted(status));
    }

    private static final JsonNode PARTNER_IN_ADYAR = JSON.readTree("{\"latitude\":13.0110,\"longitude\":80.2550}");
    /** On the way, past the Adyar bridge towards Thiruvanmiyur. */
    private static final JsonNode PARTNER_ON_THE_WAY = JSON.readTree("{\"latitude\":12.9990,\"longitude\":80.2580}");

    @Test
    void beforeThePickupItIsTheRestaurantAfterwardsTheCustomer() {
        NextStop pickup = NextStop.of(delivery("ASSIGNED"), PARTNER_IN_ADYAR);
        assertThat(pickup).isEqualTo(new NextStop("PICKUP", "Adyar Chettinad Kitchen", 0.6));
        assertThat(pickup.describe()).isEqualTo("the pickup at Adyar Chettinad Kitchen, 0.6 km away in a straight line "
                + "from your last reported position");

        NextStop customer = NextStop.of(delivery("PICKED_UP"), PARTNER_ON_THE_WAY);
        assertThat(customer).isEqualTo(new NextStop("CUSTOMER", "Thiruvanmiyur, Chennai", 1.8));
        assertThat(customer.describe()).startsWith("the customer in Thiruvanmiyur, Chennai, 1.8 km away");
    }

    @Test
    void withoutAPositionThereIsNoDistance() {
        NextStop next = NextStop.of(delivery("ASSIGNED"), null);
        assertThat(next.distanceKm()).isNull();
        assertThat(next.describe()).isEqualTo("the pickup at Adyar Chettinad Kitchen");
    }
}
