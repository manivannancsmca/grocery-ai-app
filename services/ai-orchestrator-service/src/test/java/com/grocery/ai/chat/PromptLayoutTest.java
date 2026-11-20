package com.grocery.ai.chat;

import com.grocery.ai.agent.AgentDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatService.prompt: the model's messages start with the assistant's rules, the same for every user and every turn,
 * so Ollama reuses the rules and the tool definitions it read last time (they make up ~3,200 of the shopping
 * assistant's tokens: 83–96 s to read again on CPU). The user's context line changes (cart, latest order, the date),
 * so it comes last, right before the new message.
 */
class PromptLayoutTest {

    private static final AgentDefinition SHOPPING = new AgentDefinition("shopping", "Shopping assistant", "Shops",
            List.of(), Set.of("CUSTOMER"), "You are the shopping assistant.", List.of());

    @Test
    void theRulesComeFirstAndTheContextLineRightBeforeTheNewMessage() {
        List<Message> history = List.of(new UserMessage("add rice"), new AssistantMessage("Added 1 × Rice."));

        List<Message> messages = ChatService.prompt(SHOPPING, history, "Context (from the server, trustworthy): x",
                "what's in my cart?");

        assertThat(messages).extracting(Message::getText).containsExactly("You are the shopping assistant.",
                "add rice", "Added 1 × Rice.", "Context (from the server, trustworthy): x", "what's in my cart?");
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        // a system message: a customer cannot type something that passes for the server's context
        assertThat(messages.get(3)).isInstanceOf(SystemMessage.class);
        assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
    }

    @Test
    void anotherCartOrAnotherCustomerKeepsTheStartOfThePrompt() {
        List<Message> asha = ChatService.prompt(SHOPPING, List.of(), "Context: first name Asha; cart is empty", "hi");
        List<Message> ravi = ChatService.prompt(SHOPPING, List.of(),
                "Context: first name Ravi; cart: 2 items from Fresh Mart", "hi");

        assertThat(ravi.getFirst().getText()).isEqualTo(asha.getFirst().getText());
        assertThat(asha).hasSize(3);
    }
}
