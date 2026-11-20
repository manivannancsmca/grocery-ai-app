package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The safety net against invented data: a model that answers a data question without looking anything up, with a
 * figure that appears nowhere in what it was given, gets its answer marked as not checked (a {@code notice} before
 * {@code done}, stored with the message, shown again when the conversation is reopened).
 */
class UnverifiedFiguresIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anAnswerWithAFigureNoToolReturnedIsMarkedAndStoredAsUnverified() throws Exception {
        UUID admin = UUID.randomUUID();
        model.use(TestModels.text("112 vendors have been approved by the admin."));

        Result result = chat(TOKENS.token(admin, "SUPER_ADMIN"), "admin-bi",
                "How many vendors have been approved by the admin?");

        assertThat(result.names()).containsSubsequence("conversation", "token", "notice", "done");
        assertThat(result.first("notice").text("code")).isEqualTo("UNVERIFIED_FIGURES");
        assertThat(result.first("notice").text("message")).contains("not checked against the app's data");
        UUID conversation = UUID.fromString(result.first("conversation").text("conversationId"));
        JsonNode transcript = body(mvc.perform(get("/api/ai/conversations/{id}", conversation)
                .with(as(admin, "SUPER_ADMIN"))));
        assertThat(transcript.get("messages").get(1).get("unverified").asBoolean()).isTrue();
        assertThat(transcript.get("messages").get(0).get("unverified").asBoolean()).isFalse();
    }

    @Test
    void figuresFromTheQuestionOrFromAToolAreNotMarked() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.text("I can look for paneer under ₹200 for you."));
        Result echo = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "anything under 200 rupees?");
        assertThat(echo.named("notice")).isEmpty();

        model.reset();
        GATEWAY.on("GET", "/api/admin/vendors", request -> FakeGateway.Response.json(200,
                "{\"content\":[],\"page\":0,\"size\":1,\"totalElements\":112,\"totalPages\":112}"));
        Result looked = chat(TOKENS.token(UUID.randomUUID(), "SUPER_ADMIN"), "admin-bi",
                "how many vendors are approved");
        assertThat(looked.answer()).contains("112 approved");
        assertThat(looked.named("notice")).isEmpty();
    }
}
