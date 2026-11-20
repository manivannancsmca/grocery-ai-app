-- Phase 13 schema lint (tests/db-tests): every table has a primary key. Spring AI's JDBC chat-memory table (V2, the
-- library's own MySQL schema) has none, so row-based replication, online schema changes and the InnoDB clustered index
-- fall back to a hidden row id nobody can address.
-- The fix is what MySQL itself does in GIPK mode (sql_generate_invisible_primary_key): an INVISIBLE auto-increment
-- column as the primary key. Expand-only and safe for running code: Spring AI 2.0.2 names its columns in every
-- statement (INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, `timestamp`, sequence_id) ...,
-- SELECT content, type, `timestamp` ...), and an invisible column is not returned by SELECT *. Existing rows get their
-- numbers from the ALTER (a table rebuild; the table is small: the last 20 messages of each conversation).
ALTER TABLE SPRING_AI_CHAT_MEMORY
    ADD COLUMN memory_row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT INVISIBLE FIRST,
    ADD PRIMARY KEY (memory_row_id);
