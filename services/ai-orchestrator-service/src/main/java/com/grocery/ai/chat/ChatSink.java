package com.grocery.ai.chat;

import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.tool.ToolActivity;

import java.util.UUID;

/** Where a turn's stream events go (contract §2 "Stream events"); a no-op for server-side tool use (insights). */
public interface ChatSink {

    void conversation(UUID conversationId, String agent);

    void tool(ToolActivity activity);

    void approval(ApprovalView approval);

    void token(String text);

    void done(UUID messageId, String finishReason);

    void error(String code, String message);

    /**
     * Something the user should know about a complete answer, sent just before {@code done}: UNVERIFIED_FIGURES when
     * it states figures that no tool returned (see {@link Grounding}).
     */
    default void notice(String code, String message) {
    }

    /**
     * The text streamed so far is being replaced: the model answered without a tool call although it needed one
     * ({@link SkippedTools}; code ACTION, UNSEEN or FIGURES) and is asked once more. The client drops that text; the
     * new answer's tokens follow.
     */
    default void retry(String code, String message) {
    }

    /** The client went away: stop producing. */
    boolean isClosed();

    void complete();

    ChatSink NONE = new ChatSink() {
        @Override
        public void conversation(UUID conversationId, String agent) {
        }

        @Override
        public void tool(ToolActivity activity) {
        }

        @Override
        public void approval(ApprovalView approval) {
        }

        @Override
        public void token(String text) {
        }

        @Override
        public void done(UUID messageId, String finishReason) {
        }

        @Override
        public void error(String code, String message) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void complete() {
        }
    };
}
