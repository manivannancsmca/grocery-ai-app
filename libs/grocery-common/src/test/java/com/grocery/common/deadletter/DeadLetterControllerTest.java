package com.grocery.common.deadletter;

import com.grocery.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: paths, roles, error codes and JSON shapes (with grocery-common's ProblemDetail handler). */
class DeadLetterControllerTest {

    private static final String ID = "vendor.events.DLT:0:12";
    private final DeadLetterFakes fakes = new DeadLetterFakes();
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 12).group("notification-service.dispatch")
                .eventType("VendorSubmitted"));
        mvc = MockMvcBuilders.standaloneSetup(new DeadLetterController(fakes.service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String userId, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = "ROLE_" + roles[i];
        }
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(userId, null, authorities));
    }

    @Test
    void anyAdminReadsOnlyOperationsAdminsAct() throws Exception {
        mvc.perform(get("/api/admin/dead-letters")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        as("customer-1", "CUSTOMER");
        mvc.perform(get("/api/admin/dead-letters")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        for (String role : new String[]{"SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER"}) {
            as("admin-1", role);
            mvc.perform(get("/api/admin/dead-letters")).andExpect(status().isOk());
            mvc.perform(get("/api/admin/dead-letters/" + ID)).andExpect(status().isOk());
        }

        as("support-1", "SUPPORT", "FINANCE_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/replay")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/dead-letters/replay-all")).andExpect(status().isForbidden());
        assertThat(fakes.replayer.replayed).isEmpty();
    }

    @Test
    void listsWithTheContractsShape() throws Exception {
        as("admin-1", "SUPPORT");
        String body = mvc.perform(get("/api/admin/dead-letters").param("status", "PENDING")
                        .param("consumerGroup", "notification-service.dispatch").param("eventType", "VendorSubmitted")
                        .param("page", "0").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ID))
                .andExpect(jsonPath("$.content[0].dltTopic").value("vendor.events.DLT"))
                .andExpect(jsonPath("$.content[0].originalTopic").value("vendor.events"))
                .andExpect(jsonPath("$.content[0].originalPartition").value(2))
                .andExpect(jsonPath("$.content[0].originalOffset").value(845))
                .andExpect(jsonPath("$.content[0].consumerGroup").value("notification-service.dispatch"))
                .andExpect(jsonPath("$.content[0].eventVersion").value(1))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2025-11-11T10:05:00.250123Z"))
                .andExpect(jsonPath("$.content[0].deadLetteredAt").value("2025-11-11T10:06:40Z"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].attempts").value(0))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((java.util.List<?>) json.readValue(body, Map.class).get("content")).getFirst();
        assertThat(item.keySet()).containsExactlyInAnyOrder("id", "dltTopic", "originalTopic", "originalPartition",
                "originalOffset", "consumerGroup", "key", "eventId", "eventType", "eventVersion", "occurredAt",
                "deadLetteredAt", "exceptionClass", "exceptionMessage", "payloadPreview", "status", "attempts",
                "lastError", "lastActionBy", "lastActionAt");

        mvc.perform(get("/api/admin/dead-letters").param("status", "LOST")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/dead-letters").param("consumerGroup", "someone-else"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void theDetailAddsPayloadAndStackTrace() throws Exception {
        as("admin-1", "DELIVERY_MANAGER");
        mvc.perform(get("/api/admin/dead-letters/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.view").doesNotExist())
                .andExpect(jsonPath("$.payload.orderId").value("o-1"))
                .andExpect(jsonPath("$.stackTrace").isString());

        mvc.perform(get("/api/admin/dead-letters/vendor.events.DLT:0:99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_NOT_FOUND"));
    }

    @Test
    void replayTwiceIsAConflictWithTheCurrentStatus() throws Exception {
        as("6f1c2b0e-0000-7000-8000-000000000001", "OPS_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.lastActionBy").value("6f1c2b0e-0000-7000-8000-000000000001"))
                .andExpect(jsonPath("$.lastActionAt").value("2026-11-13T10:00:00Z"));

        mvc.perform(post("/api/admin/dead-letters/" + ID + "/replay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_CLOSED"))
                .andExpect(jsonPath("$.currentStatus").value("REPLAYED"));
        assertThat(fakes.replayer.replayed).hasSize(1);
    }

    @Test
    void discardNeedsAReason() throws Exception {
        as("admin-1", "SUPER_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Vendor was deleted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/replay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("DISCARDED"));
        // discarding a closed dead letter is the same 409 as replaying it
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_CLOSED"))
                .andExpect(jsonPath("$.currentStatus").value("DISCARDED"));
    }

    @Test
    void discardOfAReplayedDeadLetterIsAConflict() throws Exception {
        as("admin-1", "OPS_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/replay")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/dead-letters/" + ID + "/discard").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_CLOSED"))
                .andExpect(jsonPath("$.currentStatus").value("REPLAYED"));
    }

    @Test
    void theIdMayArriveUrlEncoded() throws Exception {
        as("admin-1", "OPS_ADMIN");
        String encoded = "vendor.events.DLT%3A0%3A12";
        mvc.perform(get(URI.create("/api/admin/dead-letters/" + encoded)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        mvc.perform(get("/api/admin/dead-letters/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        mvc.perform(get(URI.create("/api/admin/dead-letters/vendor.events.DLT%253A0%253A12")))   // encoded twice
                .andExpect(status().isOk());
        mvc.perform(get(URI.create("/api/admin/dead-letters/vendor.events.DLT%3A0%3A99")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_NOT_FOUND"));

        mvc.perform(post(URI.create("/api/admin/dead-letters/" + encoded + "/replay")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"));
        mvc.perform(post(URI.create("/api/admin/dead-letters/" + encoded + "/discard"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REPLAYED"));
    }

    @Test
    void severalStatusesRepeatedOrCommaSeparated() throws Exception {
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 13).group("notification-service.dispatch"));
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 14).group("notification-service.dispatch"));
        as("admin-1", "OPS_ADMIN");
        fakes.replayer.behaviour = d -> d.dltOffset() == 13 ? "boom" : null;
        mvc.perform(post("/api/admin/dead-letters/vendor.events.DLT:0:13/replay"))
                .andExpect(jsonPath("$.status").value("REPLAY_FAILED"));
        mvc.perform(post("/api/admin/dead-letters/vendor.events.DLT:0:14/replay"))
                .andExpect(jsonPath("$.status").value("REPLAYED"));

        mvc.perform(get("/api/admin/dead-letters").param("status", "PENDING").param("status", "REPLAY_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/dead-letters").param("status", "PENDING,REPLAY_FAILED"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(URI.create("/api/admin/dead-letters?status=PENDING%2CREPLAY_FAILED&status=REPLAYED")))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/admin/dead-letters").param("status", "REPLAYED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value("vendor.events.DLT:0:14"));
        mvc.perform(get("/api/admin/dead-letters").param("status", ""))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/admin/dead-letters").param("status", "PENDING,NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        // counts: size=1
        mvc.perform(get("/api/admin/dead-letters").param("status", "PENDING,REPLAY_FAILED").param("page", "0")
                        .param("size", "1"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void replayAllAcceptsOptionalFilters() throws Exception {
        fakes.add(DeadLetterRecords.record("vendor.events.DLT", 0, 20).group("notification-service.dispatch")
                .eventType("VendorApproved"));
        as("admin-1", "OPS_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/replay-all").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumerGroup\":\"other.group\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(0));
        mvc.perform(post("/api/admin/dead-letters/replay-all").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"VendorApproved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(1))
                .andExpect(jsonPath("$.replayed").value(1));
        mvc.perform(post("/api/admin/dead-letters/replay-all").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumerGroup\":\"notification-service.dispatch\",\"eventType\":\"VendorSubmitted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(1));
        mvc.perform(post("/api/admin/dead-letters/replay-all").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(0))
                .andExpect(jsonPath("$.replayed").value(0))
                .andExpect(jsonPath("$.failed").value(0));
        mvc.perform(post("/api/admin/dead-letters/replay-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(0));
    }

    @Test
    void anEmptyBodyReplayAllTakesEverythingOpen() throws Exception {
        as("admin-1", "SUPER_ADMIN");
        mvc.perform(post("/api/admin/dead-letters/replay-all").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(1))
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.failed").value(0));
    }
}
