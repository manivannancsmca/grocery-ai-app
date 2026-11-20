-- Phase 11 (docs/api/phase-11-agentic-ai-and-approvals.md). IDs: UUIDv7 as BINARY(16); money DECIMAL(12,2) in INR;
-- timestamps DATETIME(6) in UTC. This service never reads another service's database: everything else comes through
-- the gateway with the user's own token.

-- One user's conversation with one agent.
CREATE TABLE conversation (
    id          BINARY(16)  NOT NULL,
    user_id     BINARY(16)  NOT NULL,
    agent       VARCHAR(20) NOT NULL,                 -- shopping | admin-bi
    title       VARCHAR(80) NOT NULL,                 -- the first message, at most 80 characters
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    INDEX idx_conversation_user_updated (user_id, updated_at),
    INDEX idx_conversation_user_agent_updated (user_id, agent, updated_at),
    INDEX idx_conversation_created (created_at)
) ENGINE = InnoDB;

-- The transcript (what GET /api/ai/conversations/{id} shows). A USER message keeps error_code when its turn failed
-- (the user message is stored even when the model fails); an ASSISTANT message keeps the finish reason and latency.
CREATE TABLE ai_message (
    id               BINARY(16)  NOT NULL,
    conversation_id  BINARY(16)  NOT NULL,
    user_id          BINARY(16)  NOT NULL,
    role             VARCHAR(10) NOT NULL,            -- USER | ASSISTANT
    content          TEXT        NOT NULL,
    finish_reason    VARCHAR(40) NULL,
    error_code       VARCHAR(40) NULL,                -- AI_UNAVAILABLE | AI_TIMEOUT | AI_TOOL_LIMIT | AI_ERROR | CLIENT_GONE
    latency_ms       INT         NULL,
    created_at       DATETIME(6) NOT NULL,
    CONSTRAINT pk_ai_message PRIMARY KEY (id),
    INDEX idx_ai_message_conversation (conversation_id, created_at),
    INDEX idx_ai_message_user_role_created (user_id, role, created_at),   -- AI_MESSAGES_PER_HOUR
    INDEX idx_ai_message_created (created_at)
) ENGINE = InnoDB;

-- The model's memory: Spring AI's JDBC chat memory repository (MySQL dialect, Spring AI 2.0.2 schema), kept to the
-- last 20 messages of each conversation by MessageWindowChatMemory. Created here, never by schema initialisation.
CREATE TABLE SPRING_AI_CHAT_MEMORY (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content`         TEXT        NOT NULL,
    `type`            ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp`       TIMESTAMP   NOT NULL,
    `sequence_id`     BIGINT      NOT NULL,
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`),
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX` (`conversation_id`, `sequence_id`)
) ENGINE = InnoDB;

-- Every tool call (and every blocked agent access, tool NULL), contract §3. Never updated.
CREATE TABLE ai_tool_invocation (
    id               BINARY(16)   NOT NULL,
    conversation_id  BINARY(16)   NULL,               -- NULL: agent access refused, or server-side use (insights)
    message_id       BINARY(16)   NULL,               -- the assistant message of the turn
    user_id          BINARY(16)   NOT NULL,
    roles            VARCHAR(200) NOT NULL,           -- comma-separated, as in the token
    agent            VARCHAR(40)  NOT NULL,
    tool             VARCHAR(100) NULL,
    arguments        TEXT         NULL,               -- the model's arguments (JSON)
    status           VARCHAR(20)  NOT NULL,           -- SUCCEEDED | FAILED | BLOCKED | APPROVAL_REQUIRED
    reason           VARCHAR(60)  NULL,               -- TOOL_NOT_ALLOWED, ROLE_NOT_ALLOWED, INVALID_ARGUMENTS, AI_TOOL_LIMIT,
                                                      -- AGENT_NOT_ALLOWED, or the API's error code
    result_summary   VARCHAR(500) NULL,
    http_status      INT          NULL,
    latency_ms       INT          NULL,
    approval_id      BINARY(16)   NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_ai_tool_invocation PRIMARY KEY (id),
    INDEX idx_ai_tool_invocation_created (created_at),
    INDEX idx_ai_tool_invocation_agent_created (agent, created_at),
    INDEX idx_ai_tool_invocation_user_created (user_id, created_at),
    INDEX idx_ai_tool_invocation_status_created (status, created_at),
    INDEX idx_ai_tool_invocation_message (message_id)
) ENGINE = InnoDB;

-- Sensitive actions waiting for (or after) a human decision, contract §4. method/path/body are fixed at proposal
-- time and executed byte for byte; body_sha256 (hex) is the token's bsh. Status changes use the optimistic lock
-- (version). Approvals are marked as used by the target services (consumed_approval), not here.
CREATE TABLE approval_request (
    id                   BINARY(16)     NOT NULL,
    action               VARCHAR(30)    NOT NULL,     -- PLACE_ORDER | CANCEL_ORDER | REFUND_PAYMENT | ADMIN_CANCEL_ORDER |
                                                      -- SUSPEND_VENDOR | SUSPEND_RESTAURANT
    status               VARCHAR(20)    NOT NULL,     -- PENDING_APPROVAL | EXECUTING | COMPLETED | FAILED | REJECTED |
                                                      -- EXPIRED | CANCELLED (APPROVED is passed through)
    agent                VARCHAR(20)    NOT NULL,
    conversation_id      BINARY(16)     NULL,
    message_id           BINARY(16)     NULL,
    requested_by         BINARY(16)     NOT NULL,
    requester_roles      VARCHAR(200)   NOT NULL,
    on_behalf_of         BINARY(16)     NULL,         -- the customer (customer actions)
    approver_user_id     BINARY(16)     NULL,         -- the one user who decides (the customer), else NULL
    approver_roles       VARCHAR(200)   NOT NULL,     -- comma-separated; '' when approver_user_id decides
    four_eyes            BOOLEAN        NOT NULL,
    target_service       VARCHAR(40)    NOT NULL,     -- the token's aud
    http_method          VARCHAR(10)    NOT NULL,
    http_path            VARCHAR(300)   NOT NULL,
    request_body         TEXT           NOT NULL,     -- canonical JSON (keys sorted), the exact bytes sent
    body_sha256          CHAR(64)       NOT NULL,
    title                VARCHAR(200)   NOT NULL,
    summary_lines        TEXT           NOT NULL,     -- JSON [{label, value}], rendered by the server
    amount               DECIMAL(12, 2) NULL,
    currency             CHAR(3)        NULL,
    rationale            VARCHAR(500)   NULL,         -- the assistant's own note, shown separately
    created_at           DATETIME(6)    NOT NULL,
    expires_at           DATETIME(6)    NOT NULL,
    decided_by           BINARY(16)     NULL,
    decided_at           DATETIME(6)    NULL,         -- also set when it EXPIRED
    decision_comment     VARCHAR(300)   NULL,
    executing_since      DATETIME(6)    NULL,
    executed_at          DATETIME(6)    NULL,
    result_json          TEXT           NULL,
    result_reference     VARCHAR(100)   NULL,
    failure_code         VARCHAR(60)    NULL,
    failure_message      VARCHAR(500)   NULL,
    failure_http_status  INT            NULL,
    failed_at            DATETIME(6)    NULL,
    updated_at           DATETIME(6)    NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    -- duplicate proposals: at most one PENDING request per requester, action and body
    pending_key          VARCHAR(120) GENERATED ALWAYS AS (
                             IF(status = 'PENDING_APPROVAL', CONCAT(HEX(requested_by), ':', action, ':', body_sha256),
                                NULL)) STORED,
    CONSTRAINT pk_approval_request PRIMARY KEY (id),
    CONSTRAINT uk_approval_request_pending UNIQUE (pending_key),
    INDEX idx_approval_status_expires (status, expires_at),
    INDEX idx_approval_status_executing (status, executing_since),
    INDEX idx_approval_requested_by (requested_by, created_at),
    INDEX idx_approval_on_behalf_of (on_behalf_of, created_at),
    INDEX idx_approval_approver_user (approver_user_id, status),
    INDEX idx_approval_message (message_id),
    INDEX idx_approval_created (created_at)
) ENGINE = InnoDB;
