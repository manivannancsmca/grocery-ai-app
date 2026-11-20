# ADR-0008: AI agents with ToolGuard and human approval

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

Agentic AI is a major requirement: five role-specific assistants using Spring AI tool calling. AI must never bypass
authorization, never access databases directly, never approve its own actions, and never execute sensitive
operations without a human. Ollama is the development LLM. The production provider is not yet chosen.

## Decision

- **One ai-orchestrator-service** (Spring AI 2.0.2) hosts five agent definitions. Each definition has a system prompt,
  a tool allowlist, the allowed role and model options. **The agent is chosen from the user's role**, never by the LLM.
- **Tools** are thin REST clients that call business services **through the gateway with the user's JWT**.
  The AI has exactly the user's rights.
- **ToolGuard** runs before every tool call:
  1. Is the tool in this agent's allowlist?
  2. Does the user have the role and permission?
  3. Are the arguments valid (schema and business limits)?
  4. What is the risk class?
     - `READ` and `LOW_RISK_WRITE` run, with audit.
     - `SENSITIVE` creates an **approval request** instead of running.
- **Approval lifecycle:** `PENDING_APPROVAL → APPROVED → EXECUTING → COMPLETED`, plus `REJECTED`, `EXPIRED`,
  `FAILED`, `CANCELLED`.
  - The approval is bound to the canonical parameters by their SHA-256 hash.
  - The summary shown to the approver is rendered by the server.
  - Approval is only possible through a REST call with a human JWT. There is no approve tool.
- **Execution:**
  - Optimistic lock, so it runs once.
  - The call carries a service token, a signed **`X-Approval-Token`** and `Idempotency-Key = approvalId`.
  - The **target service verifies** the signature, action, body hash and single use before executing.
- **Chat memory** uses JDBC in `ai_db`. Every tool call is audited.
- **Provider:** Ollama 0.40.15 container in development. Spring AI makes the production provider a configuration switch.

## Consequences

- ✅ No uncontrolled autonomy. Every capability is explicit, authorized and audited.
- ✅ Backend authorization is the final gate, even if the model is manipulated by prompt injection.
- ✅ AI is outside the ordering critical path. If the LLM fails, only the chat degrades.
- ⚠️ Small local models are weaker at tool calling. ToolGuard validation catches malformed calls, and prompts may need
  retuning for the production model.
- ⚠️ Approval tokens add a verification filter to sensitive endpoints in target services (provided by `grocery-common`).

## Alternatives considered

- **Five separate agent microservices:** more deployables with no business benefit. Rejected.
- **AI with a privileged service account:** bypasses user authorization. Rejected.
- **Target services calling back to verify approvals:** adds a synchronous dependency. A signed token plus a
  consumed-ID record gives the same assurance.

## Refinements (Phase 11 design)

- **Execution runs with the approver's authority, not a service token.** The approve request carries the deciding
  human's JWT. The orchestrator forwards it, together with the `X-Approval-Token`, to the target through the gateway.
  - Normal authorization still applies: a refund only works if the approver may refund.
  - The token's `sub` must equal the JWT's `sub`, so an approval cannot run under someone else's rights.
  - The platform has no client-credentials tokens, and none are needed. A privileged AI account stays rejected, as
    above.
  - For a customer's own order, the customer is the decider, so the order is placed with the customer's rights as usual.
- **What the token binds:** the exact method, path and body hash fixed at proposal time, so the parameters shown to
  the approver are exactly what runs. The target refuses anything else, and a second use.
  - **It protects against** a manipulated model or orchestrator changing parameters after the human saw them.
  - **It does not protect against** an attacker who can rewrite the stored approval, including its hash. That attacker
    already controls the orchestrator.
- **Orders bind the cart:** an approved order sends `expectedTotal` and `expectedCartUpdatedAt` from the preview the
  customer saw. A cart changed after the proposal gives `CART_CHANGED`, and the assistant proposes again.
- **A deterministic `scripted` model** (local and docker profiles only, like the payment simulator) drives the same
  ToolGuard and API path without an LLM, for smoke tests and CI. Real-model behaviour is checked by hand with Ollama.
- **MVP agents:** Shopping and Admin BI. Vendor, Restaurant and Delivery follow on the same framework.
