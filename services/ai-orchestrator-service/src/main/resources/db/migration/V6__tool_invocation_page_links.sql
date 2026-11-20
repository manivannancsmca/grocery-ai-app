-- The app pages a successful tool call pointed to under the answer ("View my cart" → /cart), kept so a reopened
-- conversation shows the same links. A JSON array of {"label": "...", "path": "/..."}; NULL when there were none.
-- Expand-only: code that does not know the column ignores it.
ALTER TABLE ai_tool_invocation
    ADD COLUMN page_links TEXT NULL AFTER approval_id;
