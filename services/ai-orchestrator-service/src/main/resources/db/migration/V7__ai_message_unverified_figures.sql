-- An ASSISTANT answer that states figures no tool returned in its turn (ChatService / Grounding: the model answered a
-- data question from nothing). The chat shows such an answer as "not checked against the app's data", also when the
-- conversation is opened again later. FALSE for every earlier message and for USER messages.
-- Expand-only: code that does not know the column ignores it, and the default fills it for older code's inserts.
ALTER TABLE ai_message
    ADD COLUMN unverified_figures BOOLEAN NOT NULL DEFAULT FALSE AFTER latency_ms;
