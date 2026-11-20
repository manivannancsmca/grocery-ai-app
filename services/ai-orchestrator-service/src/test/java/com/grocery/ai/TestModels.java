package com.grocery.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The model used by the integration tests: the real scripted model by default; a test can switch to a minimal fake
 * ({@link #use}) where it needs exact control (a failing model, a model that never stops calling tools, a slow one).
 * Every prompt the model received is recorded.
 */
public final class TestModels {

    private TestModels() {
    }

    /** The application's ChatModel in the tests (primary): delegates to the scripted model or a fake. */
    public static final class Switchable implements ChatModel {

        private final ChatModel scripted;
        private volatile ChatModel override;
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        public Switchable(ChatModel scripted) {
            this.scripted = scripted;
        }

        public void use(ChatModel fake) {
            override = fake;
        }

        public void reset() {
            override = null;
            prompts.clear();
        }

        public List<Prompt> prompts() {
            return List.copyOf(prompts);
        }

        private ChatModel current() {
            ChatModel fake = override;
            return fake != null ? fake : scripted;
        }

        @Override
        public ChatOptions getOptions() {
            return current().getOptions();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return current().call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return current().stream(prompt);
        }
    }

    /** Base for fakes: tool-calling options so the tool loop runs exactly as with a real model. */
    public abstract static class Fake implements ChatModel {

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("fake").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<ChatResponse> all = stream(prompt).collectList().block();
            StringBuilder text = new StringBuilder();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (ChatResponse response : all) {
                AssistantMessage output = response.getResult().getOutput();
                if (output.getText() != null) {
                    text.append(output.getText());
                }
                calls.addAll(output.getToolCalls());
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text.toString())
                    .toolCalls(calls).build(), ChatGenerationMetadata.builder().finishReason("STOP").build())));
        }
    }

    /** Fails like an unreachable model. */
    public static ChatModel failing(RuntimeException failure) {
        return new Fake() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(failure);
            }
        };
    }

    /** Never answers (the model timeout ends the turn). */
    public static ChatModel silent() {
        return new Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.never();
            }
        };
    }

    /** Answers with fixed text (e.g. JSON for structured output, or garbage). */
    public static ChatModel text(String answer) {
        return new Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(response(answer, "STOP"));
            }
        };
    }

    /** Calls {@code tool} with {@code arguments} again and again, after every tool result (a runaway loop). */
    public static ChatModel toolLoop(String tool, String arguments) {
        AtomicInteger ids = new AtomicInteger();
        return new Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                AssistantMessage call = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("loop_" + ids.incrementAndGet(), "function",
                                tool, arguments)))
                        .build();
                return Flux.just(new ChatResponse(List.of(new Generation(call))));
            }
        };
    }

    /** Text chunks, each one waiting until {@code gate} allows it (to prove tokens are flushed one by one). */
    public static ChatModel gated(List<String> chunks, java.util.function.IntConsumer gate) {
        return new Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.range(0, chunks.size()).map(i -> {
                    gate.accept(i);
                    return response(chunks.get(i), i == chunks.size() - 1 ? "STOP" : null);
                });
            }
        };
    }

    static ChatResponse response(String text, String finishReason) {
        ChatGenerationMetadata metadata = finishReason == null ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }

    /**
     * What the model was told by the app in a prompt: the assistant's rules and the user's context line (two system
     * messages since the context line moved after the conversation, see ChatService.prompt), joined as they used to
     * be in a single system message.
     */
    public static String systemText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                .collect(Collectors.joining("\n\n"));
    }

    /** The text of the last user message of a prompt. */
    public static String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.getText();
            }
        }
        return null;
    }
}
