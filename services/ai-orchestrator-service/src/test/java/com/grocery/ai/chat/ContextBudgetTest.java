package com.grocery.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatService.fitHistory: the conversation history the model is sent fits its context window next to the system
 * prompt, the tools and the new message, so Ollama never has to cut the prompt (it cuts from the start: the rules and
 * the tools). Tokens are estimated at 3 characters each.
 */
class ContextBudgetTest {

    private static Message question(int characters) {
        return new UserMessage("q".repeat(characters));
    }

    private static Message answer(int characters) {
        return new AssistantMessage("a".repeat(characters));
    }

    @Test
    void theNewestExchangesThatFitAreKept() {
        List<Message> history = List.of(question(3000), answer(3000), question(300), answer(300), question(30),
                answer(30));

        // window 5000 − prompt 1500 − reserve 3000 = 500 tokens: the last two exchanges (220) fit, the first (2000) not
        assertThat(ChatService.fitHistory(history, 1500, 5000)).containsExactlyElementsOf(history.subList(2, 6));
        // a large window keeps everything
        assertThat(ChatService.fitHistory(history, 1500, 20000)).containsExactlyElementsOf(history);
    }

    @Test
    void anAnswerWhoseQuestionNoLongerFitsIsLeftOutToo() {
        List<Message> history = List.of(question(30), answer(1500), question(30), answer(30));

        // 520 tokens: the answer of the first exchange (500) fits, its question (10) does not
        assertThat(ChatService.fitHistory(history, 1500, 5020)).containsExactlyElementsOf(history.subList(2, 4));
    }

    @Test
    void theToolResultsOfAnExchangeWhoseQuestionNoLongerFitsAreLeftOutToo() {
        Message results = new UserMessage(ChatService.TOOL_RESULTS_NOTE + "searchProducts → {\"id\":\"x\"}");
        List<Message> history = List.of(question(30), answer(30), results, question(30), answer(30));
        int budget = 10 + ChatService.tokens(results.getText()) + 10 + 10;   // the first exchange's question does not fit

        assertThat(ChatService.fitHistory(history, 1000, 1000 + ChatService.TURN_RESERVE_TOKENS + budget))
                .containsExactlyElementsOf(history.subList(3, 5));
        assertThat(ChatService.appNote(results)).isTrue();
        assertThat(ChatService.appNote(question(30))).isFalse();
    }

    @Test
    void noHistoryWhenThePromptAlreadyFillsTheWindow() {
        List<Message> history = List.of(question(30), answer(30));

        assertThat(ChatService.fitHistory(history, 6000, 8192)).isEmpty();
        assertThat(ChatService.fitHistory(List.of(), 1000, 8192)).isEmpty();
    }

    @Test
    void tokensAreEstimatedAtThreeCharactersEach() {
        assertThat(ChatService.tokens("abcdef")).isEqualTo(2);
        assertThat(ChatService.tokens("abcdefg")).isEqualTo(3);         // rounded up
        assertThat(ChatService.tokens(null)).isZero();
    }
}
