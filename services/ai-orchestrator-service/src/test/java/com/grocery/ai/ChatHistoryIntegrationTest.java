package com.grocery.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat history is stored per user in the database: it survives a reload or a new sign-in (another token of the same
 * user), each user only ever sees and changes their own conversations, and a conversation can be renamed.
 */
class ChatHistoryIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aConversationSurvivesANewSignInAndCanBeContinued() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        UUID conversation = UUID.fromString(chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "hello")
                .first("conversation").text("conversationId"));

        // A new token (logout + login, another device): the history is the server's, keyed by the user id.
        String newSession = TOKENS.token(customer, "CUSTOMER");
        JsonNode list = body(mvc.perform(get("/api/ai/conversations").param("agent", "shopping")
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(list.get("content").get(0).get("id").asString()).isEqualTo(conversation.toString());
        assertThat(chat(newSession, "shopping", "and again", conversation).first("conversation")
                .text("conversationId")).isEqualTo(conversation.toString());
        JsonNode transcript = body(mvc.perform(get("/api/ai/conversations/{id}", conversation)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(transcript.get("messages").valueStream().map(m -> m.get("role").asString()).toList())
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
    }

    @Test
    void anotherUsersConversationIsNeitherVisibleNorUsable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        UUID conversation = UUID.fromString(chat(TOKENS.token(owner, "CUSTOMER"), "shopping", "my private chat")
                .first("conversation").text("conversationId"));

        mvc.perform(get("/api/ai/conversations/{id}", conversation).with(as(other, "CUSTOMER")))
                .andExpect(status().isNotFound());
        // Not even an admin reads a customer's transcript through the chat API.
        mvc.perform(get("/api/ai/conversations/{id}", conversation).with(as(other, "SUPER_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/ai/conversations/{id}", conversation).with(as(other, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"mine now\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_NOT_FOUND"));
        assertThat(chat(TOKENS.token(other, "CUSTOMER"), "shopping", "hi", conversation).status()).isEqualTo(404);
        assertThat(body(mvc.perform(get("/api/ai/conversations").with(as(other, "CUSTOMER"))))
                .get("totalElements").asInt()).isZero();
    }

    @Test
    void theOwnerRenamesAConversationAndTheListKeepsItsOrder() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        UUID older = UUID.fromString(chat(token, "shopping", "first chat").first("conversation")
                .text("conversationId"));
        UUID newer = UUID.fromString(chat(token, "shopping", "second chat").first("conversation")
                .text("conversationId"));

        mvc.perform(patch("/api/ai/conversations/{id}", older).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  Weekly \\n groceries \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(older.toString()))
                .andExpect(jsonPath("$.title").value("Weekly groceries"));

        JsonNode list = body(mvc.perform(get("/api/ai/conversations").with(as(customer, "CUSTOMER"))));
        assertThat(list.get("content").get(0).get("id").asString()).isEqualTo(newer.toString());
        assertThat(list.get("content").get(1).get("title").asString()).isEqualTo("Weekly groceries");
        assertThat(body(mvc.perform(get("/api/ai/conversations/{id}", older).with(as(customer, "CUSTOMER"))))
                .get("title").asString()).isEqualTo("Weekly groceries");
    }

    @Test
    void anEmptyOrTooLongTitleIsRefused() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        UUID conversation = UUID.fromString(chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "hello")
                .first("conversation").text("conversationId"));

        mvc.perform(patch("/api/ai/conversations/{id}", conversation).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        mvc.perform(patch("/api/ai/conversations/{id}", conversation).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + "x".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/ai/conversations/{id}", conversation).with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/ai/conversations/{id}", conversation)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
