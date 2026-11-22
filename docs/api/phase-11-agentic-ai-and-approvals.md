# Phase 11 contract — Agentic AI & human approval

Two assistants built on Spring AI. Both work only through explicitly defined backend tools that call the business
APIs with the user's own rights. Anything with money or operational impact becomes an **approval request**: a human
decides it, and the target service verifies it before running it.

- **Customer Shopping assistant** (`shopping`, role CUSTOMER).
- **Admin Business-Intelligence assistant** (`admin-bi`, any admin role).

The Vendor, Restaurant and Delivery assistants (`vendor`, `restaurant`, `delivery`) and the shopping tool
`requestReturn` came in Phase 12 ([§6](phase-12-post-mvp-features.md#6-vendor-restaurant-and-delivery-assistants-ai-orchestrator-service)).
They use the same framework: an agent definition and its tools; the lists below include them. Public paths go through
the gateway (`http://localhost:8080`); conventions are unchanged
([Phase 2](phase-2-identity-and-stakeholders.md#1-conventions)). Conflict errors carry `currentStatus`. The new
events' schemas are in `libs/grocery-event-schemas` **1.4.0**. Design: [ADR-0008](../adr/0008-ai-agents-with-tool-guard-and-approval.md).

## 1. ai-orchestrator-service (port 8115, `ai_db`)

- Spring Boot 4.1.2 + **Spring AI 2.0.2** (`ChatClient`, tool calling, structured output, chat memory). It is a JWT
  resource server through grocery-common, like every service.
- **It never touches another service's database.** Tools call the business APIs **through the gateway**
  (`GROCERY_AI_GATEWAY_URL`, `http://api-gateway:8080` in Docker), forwarding the caller's `Authorization` header and a
  correlation id. The AI therefore has exactly the user's rights.
- **Model provider:** `grocery.ai.provider` (`AI_PROVIDER`):

| Provider | What | Where |
|---|---|---|
| `ollama` (default) | Spring AI Ollama, model `OLLAMA_MODEL` (default `qwen3:4b-instruct`, a non-thinking model, temperature 0.2), base URL `OLLAMA_BASE_URL` (`http://ollama:11434`), model timeout `AI_MODEL_TIMEOUT` (180 s in Docker; CPU inference is slow) | development with a real model (`--profile ai`) |
| `anthropic` | Claude through Anthropic's Messages API (Spring AI Anthropic over the official Java SDK): model `ANTHROPIC_MODEL` (default `claude-opus-5-5`; `claude-sonnet-5-5` or `claude-haiku-5-5` answer faster and cost less), key `ANTHROPIC_API_KEY`, temperature 0.2, at most `AI_ANTHROPIC_MAX_TOKENS` (2048) per answer, `AI_ANTHROPIC_MAX_RETRIES` (1) retry of a refused request (429, 529, 5xx). The rules and the tool definitions are cached on Anthropic's side (cache marks on the last tool and on the rules; the customer's context line after them is not cached) | **production** (2026-11-20, DEVELOPMENT_PLAN decision 12), and locally with a key in `infrastructure/local/.env` |
| `scripted` | A deterministic, rule-based stand-in model (§7). It makes real tool calls through the same ToolGuard and APIs. Available only in the `local` and `docker` profiles (like the payment simulator), never in production | smoke tests, CI, demos without a model |

  **Production provider (2026-11-20): `anthropic`.** On AWS the task definition sets `AI_PROVIDER=anthropic` and
  `ANTHROPIC_MODEL`, and reads `ANTHROPIC_API_KEY` from Secrets Manager (`grocery/<env>/ai-orchestrator-service/anthropic`,
  key `api_key`). Terraform creates that secret with the placeholder `REPLACE_ME`. Until a real key is set, the status
  says unavailable, and the chat answers `AI_UNAVAILABLE` **without calling Anthropic**, so no customer data leaves with
  a key that cannot work. Insights fall back to the rules. Anthropic's errors map like Ollama's: a wrong key (401/403),
  an unknown model (404), the rate limit (429), overloaded (529) or a server error give `AI_UNAVAILABLE`; a request
  Anthropic cannot take (400, 413, 422) gives `AI_ERROR`. Claude's finish reasons are normalized
  (`end_turn`/`stop_sequence` → `STOP`, `max_tokens` → `LENGTH`). The customer's messages and their context line
  (name, address label, cart summary) go to Anthropic with each model call. Simple shopping commands and price
  questions (§3) are answered in code and never reach the model.
- Tables: `conversation`, chat memory (Spring AI JDBC repository, MySQL), `ai_tool_invocation`, `approval_request`
  and `outbox_event`. Debezium connector `ai-outbox` → `ai.events`. Approvals are marked as used by the target
  services, not here (§5).

## 2. Chat API

| Method & path | Roles | Body → Response |
|---|---|---|
| `GET /api/ai/agents` | any signed-in user | → `[ { "id", "name", "description", "examplePrompts": [ … ] } ]` — only the agents the caller may use (CUSTOMER → `shopping`; admin roles → `admin-bi`; Phase 12: VENDOR → `vendor`, RESTAURANT_OWNER → `restaurant`, DELIVERY_PARTNER → `delivery`, DELIVERY_MANAGER → `admin-bi` and `delivery`) |
| `GET /api/ai/status` | any signed-in user | → `{ "provider", "model", "available", "checkedAt" }` (probe cached 30 s; Ollama: reachable and model pulled; Anthropic: a key is set and `GET /v1/models/{model}` knows the model, no tokens used) |
| `POST /api/ai/agents/{agent}/chat` | the agent's role | `{ "conversationId"?: "…", "message": "…" (1–2000) }` → **`text/event-stream`** (below). 404 `AGENT_NOT_FOUND`; 403 `AGENT_NOT_ALLOWED` (audited); 404 `CONVERSATION_NOT_FOUND` (another user's or another agent's); 429 `AI_RATE_LIMITED` (`AI_MESSAGES_PER_HOUR`, default 30 per user) |
| `GET /api/ai/conversations?agent=&page=&size=` | signed-in | → `PageResponse<ConversationSummary>` own conversations, newest first: `{ id, agent, title (first message ≤ 80), createdAt, updatedAt }` |
| `GET /api/ai/conversations/{id}` | owner | → `{ id, agent, title, messages: [ { id, role: USER\|ASSISTANT, content, createdAt, tools: [ToolActivity], approvals: [ApprovalView] } ] }` · 404 `CONVERSATION_NOT_FOUND` |
| `DELETE /api/ai/conversations/{id}` | owner | → 204 (messages and memory deleted; audit and approvals are kept) |
| `POST /api/ai/agents/admin-bi/insights` | any admin | `{ "date"?: "2026-11-13" }` → `InsightReport` (§6, structured output) |

**Stream events** (`text/event-stream`, one JSON object per `data:`):

| `event:` | `data` | When |
|---|---|---|
| `conversation` | `{ "conversationId", "agent" }` | first, always |
| `tool` | `ToolActivity` `{ "invocationId", "tool", "label", "status": STARTED\|SUCCEEDED\|FAILED\|BLOCKED\|APPROVAL_REQUIRED, "summary", "links": [PageLink] }` | around every tool call (`label` is human-readable, e.g. "Searched products"). `links` is filled only when the call SUCCEEDED (see "Page links" below) |
| `approval` | `ApprovalView` (§4) | when a sensitive tool created an approval request |
| `token` | `{ "text" }` | the answer, in chunks |
| `retry` | `{ "code": ACTION\|UNSEEN\|FIGURES, "message" }` | at most once: the answer so far used no tool although it needed one; drop its text, the new answer's events follow (§11) |
| `notice` | `{ "code": UNVERIFIED_FIGURES, "message" }` | before `done`: the answer states figures no tool returned (§11) |
| `done` | `{ "messageId", "finishReason" }` | last, on success |
| `error` | `{ "code", "message" }` | last, on failure: `AI_UNAVAILABLE` (model unreachable), `AI_TIMEOUT`, `AI_TOOL_LIMIT` (more than 6 tool calls in one message), `AI_ERROR`. `message` is user-friendly ("The assistant is not available right now. Everything else works as usual.") |

- The user message is stored even when the model fails.
- The model sees the last 20 messages of the conversation (as many as fit its context window, see §11) plus a
  server-built context: role, first name, today's date (India), and for `shopping` a one-line cart summary and the
  default delivery address with its id. The context line is a system message of its own, right before the new
  message; the assistant's rules come first (see §11, prompt order).
- Each remembered answer is followed by the compact results of that turn's successful tool calls (≤ 2,500
  characters), as a separate message marked `[From the app, not the customer]`. Users never see them, and the
  transcript keeps the plain answer. Without them, a follow-up such as "add the first one" made a real model guess
  ids. (Until 2026-11-19 they were appended to the answer itself, and the small model copied the block into later
  answers, with a tool result it never got.)
- Tool results are **untrusted data**. The system prompt tells the model never to follow instructions found in them,
  and the backend enforces authorization anyway.

**Page links.** Answers point to the app's pages, so the user can open the full view in one click. Links come from
two places:
- **Tool links.** A successful tool call carries the pages that show the same thing, with the same filters:
  `PageLink { "label", "path" }`, for example `{ "label": "View my cart", "path": "/cart" }` or
  `{ "label": "Track order GA-7K3F9Q2M", "path": "/orders/{orderId}" }`. Paths are app paths (always starting with
  one `/`), and query values are URL-encoded. They are built in one place (`link/Pages.java`, which mirrors the React
  routes) and stored with the call (`ai_tool_invocation.page_links`), so `GET /api/ai/conversations/{id}` returns
  the same links. The chat shows them as buttons under the answer.
- **Inline links.** Each agent's system prompt lists its main pages as markdown links, and the model may use them
  in its answer (`[My cart](/cart)`). The chat renders a markdown link only when its path is one of that agent's
  pages (`frontend/grocery-web/src/features/ai/pageLinks.ts`). Any other link, including any web address, is shown
  as plain text.

| Agent | Tool links (examples) | Pages listed in the prompt |
|---|---|---|
| `shopping` | search → `/search?q=…&maxPrice=…`, product → `/products/{id}`, restaurants → `/restaurants?q=…` and `/restaurants/{id}`, cart tools → `/cart`, checkout preview → `/checkout` (or `/cart` while blocked), orders → `/orders?status=…`, order status → `/orders/{id}` | cart, checkout, orders, returns, addresses, search, restaurants |
| `admin-bi` | stats → `/admin/reports`, orders → `/admin/orders?status=…`, one order → `/admin/orders?order={id}`, stuck orders → `/admin/operations?tab=stuck-orders`, payments and reconciliation → `/admin/payments` | orders, payments, stuck orders, reports, AI approvals |
| `vendor` | stock → `/vendor/inventory?q=…&filter=…`, prices → `/vendor/pricing?q=…`, products, store orders, purchase orders (a new draft → `/vendor/purchase-orders/{id}`), suppliers, returns, reviews, sales → `/vendor/…` | inventory, prices, products, store orders, purchase orders, sales reports |
| `restaurant` | order queue, menu, sales, reported problems, reviews → `/restaurant/…` | order queue, menu, sales reports, reviews |
| `delivery` | partner tools → `/delivery` and `/delivery/history`; manager tools → `/admin/deliveries?status=…&noPartner=1` or `?q={orderNumber}` | my deliveries, history, delivery monitor |

Sensitive tools (proposals) carry no links: their approval card in the chat is where the user acts.

## 3. Tools, risk classes and ToolGuard

The agent is chosen from the URL and checked against the caller's role. **The model never chooses an agent.**

**`shopping` (CUSTOMER)**

| Tool | Risk | Calls (as the customer) |
|---|---|---|
| `searchProducts(query?, inStockOnly?, minPrice?, maxPrice?, sort?)` | READ | MCP tool `searchProducts` on catalog-query (`POST /api/mcp/catalog`, the MCP pilot below), else `GET /api/search/products` (size 5; `totalFound` counts every match, so a query-less price search answers "how many products under ₹50") |
| `getProduct(productId)` | READ | `GET /api/catalog/products/{id}` |
| `searchRestaurants(query?, openNow?)` | READ | `GET /api/search/restaurants` near the default address |
| `getRestaurantMenu(restaurantId)` | READ | `GET /api/catalog/restaurants/{id}` |
| `getCart()` | READ | `GET /api/cart` |
| `addToCart(productId \| restaurantId+menuItemId, quantity 1–20, replaceCart?)` | LOW_RISK_WRITE | `POST /api/cart/items` |
| `updateCartItem(itemId, quantity 0–20)` | LOW_RISK_WRITE | `PUT /api/cart/items/{itemId}` |
| `previewCheckout(addressId?)` | READ | `GET /api/cart/preview` |
| `listMyOrders(status?)` | READ | `GET /api/orders` |
| `getOrderStatus(orderNumber)` | READ | MCP tool `getOrderStatus` on order-service (`POST /api/mcp/orders`), else `GET /api/orders` → `GET /api/orders/{id}` |
| `placeOrder(addressId?, note?)` | **SENSITIVE** → `PLACE_ORDER`, decided by the customer | `POST /api/orders` |
| `cancelOrder(orderNumber, reason?)` | **SENSITIVE** → `CANCEL_ORDER`, decided by the customer | `POST /api/orders/{id}/cancel` |
| `requestReturn(orderNumber, items[{name \| itemId, quantity}], reason, note?)` (Phase 12) | **SENSITIVE** → `REQUEST_RETURN`, decided by the customer | `POST /api/orders/{id}/returns` |

**MCP pilot (2026-11-20; DEVELOPMENT_PLAN "REST vs MCP").** Two domain services publish a tool on their own MCP
server, owned next to their code and usable by any MCP client acting for a signed-in user:

| Service | MCP endpoint (through the gateway) | Tool | Answers like |
|---|---|---|---|
| catalog-query-service | `POST /api/mcp/catalog` | `searchProducts(q?, categoryId?, vendorIds?, inStock?, minPrice?, maxPrice?, sort?, size?)` | `GET /api/search/products` (same controller method and validation) |
| order-service | `POST /api/mcp/orders` | `getOrderStatus(orderNumber)` (any case) | `GET /api/orders/{id}`: customers only, their own orders only (another customer's: `ORDER_NOT_FOUND`) |

- **Transport:** Spring AI's MCP server, **stateless** Streamable HTTP (`spring.ai.mcp.server.protocol=STATELESS`):
  every POST is a self-contained JSON-RPC request (no session), so any replica answers it. In a servlet app the tool
  runs on the request's own thread, so it sees the caller's verified token.
- **Security:** neither endpoint is a public path; the gateway and the service both require a valid token (a POST is
  never a public read). A tool's business error is a result with `isError: true` whose text is
  `{"code","message","status"}`, the code and status the REST API's problem would carry.
- **Client:** the assistant (`McpGateway`) is an MCP client per server, shared by every user: the MCP SDK takes the
  current caller's token for each call and sends it as that call's `Authorization` header, with the correlation id.
  The call still goes through ToolGuard (allowlist, role, arguments, risk) and is audited; `ai_tool_invocation.transport`
  (V8) says `MCP` or `REST`. A server that cannot be reached (not deployed yet, down) falls back to the REST API for
  that call (audited `REST`). `AI_MCP_ENABLED=false` uses REST only.
- The order lookup by number is one call over MCP, where the REST path pages through the customer's order list first.

The Phase 12 agents `vendor` (VENDOR), `restaurant` (RESTAURANT_OWNER) and `delivery` (DELIVERY_PARTNER tools and
DELIVERY_MANAGER tools, split by step 2 below) and their tools are listed in the
[service README](../../services/ai-orchestrator-service/README.md#the-phase-12-assistants) and Phase 12 §6.

**`admin-bi` (SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER)**

| Tool | Risk | May propose | Calls (as the admin) |
|---|---|---|---|
| `getOrderStats(date?)` | READ | any admin | `GET /api/admin/stats` |
| `listOrders(status?, type?, from?, to?)` | READ | any admin | `GET /api/admin/orders` (size 20) |
| `getOrder(orderNumber)` | READ | any admin | `GET /api/admin/orders?q=` → `/{id}` |
| `listStuckOrders()` | READ | any admin | `GET /api/admin/orders/stuck` |
| `listPayments(status?, refundStatus?)` | READ | any admin | `GET /api/admin/payments` (size 20) |
| `getReconciliationSummary()` | READ | any admin | `GET /api/admin/payments/reconciliation` |
| `refundOrder(orderNumber, amount?, note)` | **SENSITIVE** → `REFUND_PAYMENT` | SUPER_ADMIN, FINANCE_ADMIN, OPS_ADMIN, SUPPORT | `POST /api/admin/refunds` |
| `cancelOrderAsAdmin(orderNumber, reason)` | **SENSITIVE** → `ADMIN_CANCEL_ORDER` | SUPER_ADMIN, OPS_ADMIN, SUPPORT | `POST /api/admin/orders/{id}/cancel` |
| `suspendVendor(vendorId, reason)` | **SENSITIVE** → `SUSPEND_VENDOR` | SUPER_ADMIN, OPS_ADMIN | `POST /api/admin/vendors/{id}/suspend` |
| `suspendRestaurant(restaurantId, reason)` | **SENSITIVE** → `SUSPEND_RESTAURANT` | SUPER_ADMIN, OPS_ADMIN | `POST /api/admin/restaurants/{id}/suspend` |

**What the model sees.** The APIs answer in UTC. The order tools above, `getOrderStatus`/`listMyOrders` and every
`admin-bi` list hand the model India time written out, e.g. "18 Nov, 8:37 pm IST" (`IndiaTime`). An order's time is
`placedAt`. A cancellation carries its reason as a code and in words (`reasonText`, e.g. "the store could not accept
it"). Given raw UTC instants and codes, the small local model read them out to people as they were ("cancelled at
15:07", "STORE_REJECTED").

**ToolGuard runs before every tool call, in this order:**
1. **Is the tool in this agent's allowlist?** A tool name the model invents or takes from another agent becomes
   `BLOCKED` `TOOL_NOT_ALLOWED`.
2. **May the caller's role propose it?** If not, `BLOCKED` `ROLE_NOT_ALLOWED`.
3. **Are the arguments valid?** Types, ranges and lengths (query ≤ 100, reason and note 1–300, amount 0.01–100000.00);
   if not, `BLOCKED` `INVALID_ARGUMENTS`. A placeholder word such as `"default"` or `"none"` in an **optional** id
   argument counts as "not given" (an absent `addressId` means the default address). Any other non-id is refused.
4. **What is the risk class?**
   - READ and LOW_RISK_WRITE run.
   - SENSITIVE **does not run**: it creates an approval request (§4) and returns `APPROVAL_REQUIRED` to the model.

- **What the model receives back** (JSON):
  - the API result, trimmed to what it needs;
  - `{ "status": "APPROVAL_REQUIRED", "approvalId", "title", "expiresAt" }`;
  - `{ "status": "BLOCKED", "reason", "message" }`;
  - `{ "status": "ERROR", "code", "message" }`, carrying the API's error code (e.g. `TOTAL_CHANGED`).
- **At most 6 tool calls per user message.** A seventh ends the turn with `error` `AI_TOOL_LIMIT`.
- **Every call is audited in `ai_tool_invocation`:** id, conversation, user, roles, agent, tool, arguments, status
  (SUCCEEDED, FAILED, BLOCKED, APPROVAL_REQUIRED), reason, result summary (≤ 500), HTTP status, latency, approval id
  and time.
  - Blocked agent access (`AGENT_NOT_ALLOWED`) is audited too, with `tool` null.
  - Tool HTTP timeout: 5 s.

## 4. Approvals

**Lifecycle:** `PENDING_APPROVAL → APPROVED → EXECUTING → COMPLETED | FAILED`. From `PENDING_APPROVAL` a request can
also become `REJECTED` (by a decider), `EXPIRED` (nobody decided in time) or `CANCELLED` (by the requester or the
customer it is for).

**Proposal** (when ToolGuard reaches a SENSITIVE tool):
- **The parameters are built by the server, not by the model.**
  - `PLACE_ORDER` takes a fresh checkout preview: `expectedTotal` and `expectedCartUpdatedAt` come from it, plus the
    model's `addressId` and `note`.
  - `REFUND_PAYMENT` resolves the order and its payment first. An amount larger than what is still refundable becomes
    `INVALID_ARGUMENTS`.
- **They are stored as canonical JSON:** the exact HTTP method, path and body sent later, plus the body's
  **SHA-256**. All of it is fixed at proposal time.
- **The summary is rendered by the server from those parameters,** never by the model. Examples:
  - `PLACE_ORDER`: store, items (name, quantity, unit price, line total), subtotal, delivery fee, total, address.
  - `REFUND_PAYMENT`: order, paid amount, already refunded, amount, note.
- **The model's own explanation is stored separately as `rationale`** (≤ 500) and shown as "The assistant's note".
- **Duplicates:** a pending request of the same requester with the same action and body hash is returned instead of
  a new one.

| Action | Decided by | Four-eyes (decider ≠ requester) | Expires after |
|---|---|---|---|
| `PLACE_ORDER`, `CANCEL_ORDER` | that customer only | no (it is the customer's own purchase) | `AI_APPROVAL_TTL_CUSTOMER` (15m) |
| `REFUND_PAYMENT` | SUPER_ADMIN, FINANCE_ADMIN | when amount > `AI_FOUR_EYES_REFUND_ABOVE` (2000.00) | `AI_APPROVAL_TTL_ADMIN` (24h) |
| `ADMIN_CANCEL_ORDER` | SUPER_ADMIN, OPS_ADMIN | no | 24h |
| `SUSPEND_VENDOR`, `SUSPEND_RESTAURANT` | SUPER_ADMIN, OPS_ADMIN | always | 24h |
| `REQUEST_RETURN` (Phase 12) | that customer only | no | 15m |
| `ADJUST_STOCK`, `CHANGE_PRICE`, `SEND_PURCHASE_ORDER` (Phase 12) | that vendor only (`approverUserId` = requester, no `obo`) | no | 15m |
| `SET_MENU_ITEM_AVAILABILITY`, `ACCEPT_STORE_ORDER`, `REJECT_STORE_ORDER` (Phase 12) | that restaurant owner only | no | 15m |
| `REASSIGN_DELIVERY` (Phase 12) | DELIVERY_MANAGER, OPS_ADMIN, SUPER_ADMIN | no | 24h |

Self-decided requests (the customer's, vendor's or owner's own) are visible only to that user; admins see every request
decided by admin roles.

**The AI cannot approve anything.** There is no approve tool. A decision is a REST call with a human's JWT:

| Method & path | Who | Body → Response |
|---|---|---|
| `GET /api/approvals?scope=mine\|to-decide&status=&page=&size=` | signed-in | → `PageResponse<ApprovalView>`, newest first. `mine` (default) = requested by me or for me; `to-decide` = `PENDING_APPROVAL` ones I may decide |
| `GET /api/approvals/{id}` | requester, the customer it is for, an eligible decider, any admin for admin actions | → `ApprovalView` · 404 `APPROVAL_NOT_FOUND` |
| `POST /api/approvals/{id}/approve` | an eligible decider | `{ "comment"? (≤ 300) }` → `ApprovalView` after execution (`COMPLETED` with `result`, or `FAILED` with `failure`) |
| `POST /api/approvals/{id}/reject` | an eligible decider | `{ "comment" (required ≤ 300) }` → `REJECTED` |
| `POST /api/approvals/{id}/cancel` | requester / the customer it is for | → `CANCELLED` |

- **Errors:**
  - 404 `APPROVAL_NOT_FOUND`: also for a request the caller may not see (e.g. an admin and a customer's own order);
    its existence is not revealed.
  - 403 `APPROVAL_FORBIDDEN`: the caller may see it but not decide it (e.g. an OPS_ADMIN and a refund).
  - 403 `FOUR_EYES_REQUIRED`: the requester tries to decide a four-eyes request.
  - 409 `APPROVAL_NOT_PENDING` with `currentStatus`: already decided, or expired (an approve after `expiresAt` marks it
    `EXPIRED` and answers this).
- **Exactly once:**
  - The status changes with an optimistic lock, so of two simultaneous approvals one wins and the other gets 409.
  - The target additionally refuses a reused approval (§5), and `Idempotency-Key = approvalId` is sent where the
    endpoint takes one.
- **Execution runs synchronously in the approve request, with the approver's authority:**
  - The orchestrator calls the stored method, path and body **through the gateway**.
  - It forwards the approver's `Authorization` header, a signed **`X-Approval-Token`** (§5), `Idempotency-Key` and
    `X-Correlation-Id`.
  - The target's answer decides `COMPLETED` (with `result`, e.g. `{ "orderId", "orderNumber" }`) or `FAILED` (with
    `failure: { code, message, httpStatus }`, e.g. `TOTAL_CHANGED`, `CART_CHANGED`, `APPROVAL_BODY_MISMATCH`,
    `TARGET_UNAVAILABLE`).
  - A failed request is never retried. The assistant proposes a new one.
  - A request left in `EXECUTING` for more than 2 minutes (crash) becomes `FAILED` `EXECUTION_INTERRUPTED`. The target's
    single-use record shows whether it ran.
- **Expiry:** a job every `AI_APPROVAL_EXPIRY_INTERVAL` (30s) marks overdue requests `EXPIRED`.

```jsonc
// ApprovalView
{ "id": "…", "action": "PLACE_ORDER", "status": "PENDING_APPROVAL", "agent": "shopping", "conversationId": "…",
  "requestedBy": "…", "onBehalfOf": "…", "approverUserId": "…", "approverRoles": [], "fourEyes": false,
  "summary": { "title": "Place order: 2 items from Fresh Mart", "lines": [ { "label": "Smart Rice × 2", "value": "₹200.00" }, … ],
               "amount": 225.00, "currency": "INR" },
  "rationale": "You asked me to check out.", "fingerprint": "3f9a1c2e",   // first 8 hex of the body hash
  "canDecide": true, "createdAt": "…", "expiresAt": "…",
  "decidedBy": null, "decidedAt": null, "comment": null, "executedAt": null, "result": null, "failure": null }
```

## 5. Approval token — verified by the target (grocery-common 0.8.0)

`X-Approval-Token` is a JWS (RS256, header `typ: approval+jwt`, `kid`) signed by ai-orchestrator-service. Its claims:

| Claim | Value |
|---|---|
| `iss` | `ai-orchestrator-service` |
| `aud` | the target service (`order-service`, `payment-service`, `vendor-service`, `restaurant-service`; Phase 12 adds `inventory-service` for `ADJUST_STOCK`, `pricing-service` for `CHANGE_PRICE` and `delivery-service` for `REASSIGN_DELIVERY`) |
| `jti` | the approval id |
| `sub` | the approver's user id; it must equal the request JWT's `sub` |
| `obo` | the customer the action is for (absent for admin actions and for a vendor's or restaurant owner's own change) |
| `act` | the action (`PLACE_ORDER`, …) |
| `htm`, `htu` | the exact HTTP method and path (`POST`, `/api/orders`) |
| `bsh` | base64url SHA-256 of the exact body bytes, fixed at proposal time |
| `iat`, `exp` | `exp` = `iat` + 60 s |

- **Public keys:** `GET /.well-known/approval-jwks.json` on ai-orchestrator-service (port 8115, internal; the gateway
  does not route it).
  - The key pair comes from `AI_APPROVAL_SIGNING_KEY` (PEM). Without it, a key is generated at startup, which is fine
    for one local replica.
  - Production: a shared key from the secrets store.
- **Target filter** (`grocery.approvals.enabled=true` in order, payment, vendor and restaurant services, and from
  Phase 12 in inventory, pricing and delivery services; JWKS URI
  `APPROVAL_JWKS_URI`):
  - **It acts only when the header is present.** Without it, a request is an ordinary user request, unchanged.
  - With it, all of these must hold:
    1. the signature is valid with a known key;
    2. `aud` is this service, `iss` matches and the token has not expired (5 s skew);
    3. `htm` and `htu` equal the request's method and path;
    4. `bsh` equals the hash of the received body;
    5. `sub` equals the authenticated user;
    6. `jti` was never used before: it is inserted into `consumed_approval` in its own transaction, before the handler
       runs.
  - **Answers:**
    - 403 `APPROVAL_TOKEN_INVALID` (`reason`: `SIGNATURE`, `EXPIRED`, `AUDIENCE`, `METHOD_OR_PATH`, `SUBJECT`);
    - 403 `APPROVAL_BODY_MISMATCH`;
    - 409 `APPROVAL_ALREADY_USED`;
    - 503 `APPROVAL_KEYS_UNAVAILABLE`.
  - A consumed approval whose business call then fails stays consumed, so a new approval is needed.
- **order-service:** `PlaceOrderRequest` gains an optional `expectedCartUpdatedAt`. When it is present and the cart's
  `updatedAt` differs, the answer is 409 `CART_CHANGED`, so an approved order contains exactly the items the customer
  saw.

## 6. Admin insights (structured output) and AI monitoring

- `POST /api/ai/agents/admin-bi/insights`:
  1. The server collects today's stats, stuck orders and the reconciliation summary itself, through the same tools
     and with the admin's rights.
  2. It asks the model for a typed `InsightReport` (Spring AI `.entity(...)`):
     `{ date, headline, kpis: [ { name, value, note? } ], observations: [ … ], risks: [ … ], suggestedActions: [ … ],
     generatedBy: { provider, model }, generatedAt }`.
  3. **Fallback:** if the model is unavailable or returns invalid output, the report is built by rules from the same
     data, with `generatedBy.provider = "rules"`, and answered 200.
- **Audit:** `GET /api/admin/ai/tool-invocations?agent=&userId=&tool=&status=&from=&to=&page=&size=` (any admin) →
  `PageResponse<ToolInvocationView>`, newest first.
- **Monitoring:** `GET /api/admin/ai/summary?date=` (any admin) → `{ date, conversations, messages, toolCalls, blocked,
  failed, approvals: { requested, completed, rejected, expired, failed, cancelled, pending }, modelErrors,
  avgModelLatencyMs, provider, model, available }`.

## 7. The `scripted` model (local and docker profiles only)

A deterministic stand-in for an LLM. It reads the latest user message (case-insensitive; the first rule that matches
wins), makes the listed tool calls through ToolGuard exactly as a real model would, then answers from a template that
summarises the tool results.

| Agent | Message | Tool calls |
|---|---|---|
| shopping | `add <n> <words>` | `searchProducts(<words>, inStockOnly=true)`, then `addToCart(first result, n)` |
| shopping | `[how many / show me / find] <words> below\|under\|less than [₹\|rs] <amount> [rupees]` (e.g. `how many products below 50 rupees`) | `searchProducts(maxPrice=<amount>)` with sort PRICE_ASC when `<words>` is just "products" / "items"; otherwise `searchProducts(<words>, maxPrice=<amount>)` |
| shopping | `search <words>` / `find <words>` | `searchProducts(<words>)` |
| shopping | `cart` / `show my cart` | `getCart()` |
| shopping | `checkout` / `place my order` | `previewCheckout()`, then `placeOrder()` |
| shopping | `cancel order <ORDER-NUMBER>` | `cancelOrder(<number>, "Requested through the assistant")` |
| shopping | `status of <ORDER-NUMBER>` | `getOrderStatus(<number>)` |
| admin-bi | `stats` / `sales today` | `getOrderStats()` |
| admin-bi | `stuck orders` | `listStuckOrders()` |
| admin-bi | `refund <amount\|all> for <ORDER-NUMBER> because <note>` | `refundOrder(<number>, <amount or none>, <note>)` |
| admin-bi | `cancel <ORDER-NUMBER> because <reason>` | `cancelOrderAsAdmin(<number>, <reason>)` |
| admin-bi | `suspend vendor <id> because <reason>` / `suspend restaurant <id> because <reason>` | `suspendVendor` / `suspendRestaurant` |
| shopping (Phase 12) | `return <n> <item words> from <ORDER-NUMBER> because <reason>` | `requestReturn(<number>, [{name: <words>, quantity: n}], <reason classified: DAMAGED, WRONG_ITEM, MISSING, QUALITY or OTHER>, note = <reason>)` |
| vendor (Phase 12) | `low stock` | `listInventory(filter=LOW_STOCK)` |
| vendor | `sales` / `sales report` | `getSalesReport()` |
| vendor | `set stock <product words> to <n>` | `listInventory(query=<words>)`, then `adjustStock(first result, SET, n, STOCK_COUNT)` |
| vendor | `price <product words> <amount>` | `listProducts(query=<words>)`, then `changePrice(first result, <amount>)` |
| restaurant (Phase 12) | `orders` / `order queue` | `getOrderQueue()` |
| restaurant | `sales` / `sales report` | `getSalesReport()` |
| restaurant | `mark <dish words> sold out` | `getMenu()`, then `setMenuItemAvailability(<the dish>, false)` |
| restaurant | `accept <ORDER-NUMBER> in <n> min` | `acceptOrder(<number>, n)` |
| delivery (Phase 12) | `active delivery` | `getActiveDelivery()` (partners) |
| delivery | `deliveries` | `listDeliveries()` (managers) |
| delivery | `reassign <ORDER-NUMBER>` | `reassignDelivery(<number>, reason "Requested through the assistant")` (managers) |
| every agent | `use tool <name> <json>` | exactly that tool call (to exercise ToolGuard) |
| every agent | anything else | no tool; a help answer listing what it understands |

## 8. Events (`ai.events`, key approvalId, schemas 1.4.0) and notifications

| Event | Payload |
|---|---|
| ApprovalRequested | approvalId, action, agent, requestedBy, onBehalfOf, title, approverUserId, approverRoles, fourEyes, amount, currency, expiresAt, requestedAt |
| ApprovalDecided | … + decision (APPROVED, REJECTED, EXPIRED, CANCELLED), decidedBy, comment, decidedAt |
| ApprovalExecuted | … + decidedBy, resultReference (e.g. the order number), executedAt |
| ApprovalFailed | … + decidedBy, errorCode, errorMessage, httpStatus, failedAt |

notification-service (`notification-service.dispatch` also consumes `ai.events`):

| Event | Recipient | `type` · severity | Title · body · link |
|---|---|---|---|
| ApprovalRequested | `approverUserId`, or the `approverRoles` | `AI_APPROVAL_REQUESTED` · INFO | "Approval needed: {title}" · "Proposed by the {agent label} assistant. Expires at {HH:mm}." · customer `/assistant/approvals?id={id}`, admin `/admin/ai/approvals?id={id}` |
| ApprovalDecided REJECTED / EXPIRED | requester (and the customer it is for, if different) | `AI_APPROVAL_DECIDED` · WARNING | "Not approved: {title}" / "Expired: {title}" · "{comment}" / "Nobody decided in time." · same link |
| ApprovalExecuted | requester and the customer it is for | `AI_ACTION_COMPLETED` · SUCCESS | "Done: {title}" · "{resultReference}" · same link |
| ApprovalFailed | requester and the customer it is for | `AI_ACTION_FAILED` · ERROR | "Could not complete: {title}" · "{errorMessage}" · same link |

Phase 12: the events carry the new actions and agents (`vendor`, `restaurant`, `delivery`). Links for the deciding
user (Phase 12 §6): customer `/assistant/approvals`, vendor `/vendor/assistant`, restaurant `/restaurant/assistant`,
delivery `/delivery/assistant`, admins `/admin/ai/approvals`, all with `?id=`.

Every ai event also sends the signal **`AI_APPROVALS_CHANGED {approvalId}`** to the requester, to the customer it is
for, and to the approver user or roles. The notification `data` carries `approvalId` and `action`.

## 9. Gateway, compose, frontend

- **Gateway:**
  - `/api/ai/agents/*/chat` is streamed (`response-timeout: -1`).
  - `/api/ai/**`, `/api/approvals/**` and `/api/admin/ai/**` → ai-orchestrator-service.
  - `GET /api/health/ai` is public.
- **Compose:**
  - `ai-orchestrator-service` (8115) is in the profiles `assistant` (orchestrator only), `ai` (plus Ollama and the
    model pull) and `all`.
  - Settings: `AI_PROVIDER` (default `ollama`), `OLLAMA_MODEL`, `AI_APPROVAL_TTL_CUSTOMER`, `AI_APPROVAL_TTL_ADMIN`,
    `AI_FOUR_EYES_REFUND_ABOVE`, `AI_MESSAGES_PER_HOUR`, `AI_APPROVAL_SIGNING_KEY`.
  - The target services get `APPROVAL_JWKS_URI`.
- **Frontend:**
  - **Assistant panel** for customers (`shopping`) and admins (`admin-bi`): conversations, streamed answers, tool
    activity chips, inline approval cards (summary, fingerprint, countdown, Approve / Reject for an eligible decider;
    for a customer's own order: "Confirm order" / "Not now"), and an offline state from `/api/ai/status`.
  - **Customer:** `/assistant/approvals`.
  - **Admin:**
    - `/admin/ai/approvals`: To decide, plus history.
    - `/admin/ai/audit`: tool invocations, blocked attempts and the summary.
    - An "AI insights" card on the dashboard.
  - **Notifications:** the four new types and `AI_APPROVALS_CHANGED`. A tool event `addToCart` or `updateCartItem`
    refreshes the cart.

## 10. Acceptance (phase plan)

- **The agent searches and adds items to the cart.**
- **"Place order" produces an approval showing the exact items and total** (equal to the checkout preview).
- **Approving executes once:** a double approve does not duplicate (409, exactly one order).
- **Reject or expiry means nothing runs.**
- **Tampered parameters are refused by the target service:** the stored body is changed after the proposal, so
  approving it gives `FAILED` with `APPROVAL_BODY_MISMATCH` from the target, and nothing is changed.
- **Cross-role tool attempts are blocked and audited:**
  - a customer using `admin-bi` → 403 `AGENT_NOT_ALLOWED`;
  - a customer asking for `refundOrder` → `TOOL_NOT_ALLOWED`;
  - SUPPORT proposing a suspension → `ROLE_NOT_ALLOWED`.
- **With the LLM down, the chat degrades gracefully:** `error` `AI_UNAVAILABLE` and `available: false`. Cart and
  orders keep working, and insights fall back to rules.

## 11. Addendum — stakeholder-aware answers, chat history, voice

**Who is asking.** The JWT alone decides: the agent (URL, role-checked), the tools offered to the model (only those the
caller's role may use; any other name is BLOCKED and audited) and the server-built context line: role and what it
may see in plain words, first name, India date and the user's own business, read live with the user's token (cart and
default address; `GET /api/vendors/me`; `GET /api/restaurants/me`; `GET /api/delivery/partners/me`). Every agent's
prompt shares the same conversation rules: casual wording and typos, follow-ups, one clarifying question when
ambiguous, the answer first, numbers only from tool results, never another person's private details.

**Live snapshot and grounding.** The context line also carries what each role asks most, read just now: the
customer's latest order, an approved vendor's / restaurant's orders today (and the vendor's low-stock count), an
approved delivery partner's active delivery and waiting offers. When a turn returned no tool data and its answer
states a figure (a number of two or more digits, or a GA-…/PO-… reference) found nowhere in the question, the
conversation, the context line or the tools' refusals, the stream sends `event: notice`
`{ "code": "UNVERIFIED_FIGURES", "message": "…" }` before `done`, and the stored message has `unverified: true`
(`ai_message.unverified_figures`, migration V7). The web app shows such an answer as not checked against the app's data.

**Answers the tool calls do not back are asked again (2026-11-19).** The local model (qwen3:4b-instruct) sometimes
answers without the tool call it needed: "I've removed the tomatoes" or "Added 1 kg of Tomato" with the cart untouched
(even right after a search), "I don't have access to the details of order GA-…", a delivery fee from nowhere.
`SkippedTools` sends an answer back once when:

- `ACTION`: it claims a change (first person, "has been added", or a sentence starting "Added …", "Removed …"; not a
  question or an offer such as "If yes, I'll add it") and no tool that changes something ran in the turn (a cart
  change or a prepared approval; reads do not count);
- `UNSEEN`: it says it cannot see or find something, and no tool was called at all;
- `FIGURES`: no tool returned data (a prepared approval counts as data) and it states figures as above.

The stream then sends `event: retry` `{ "code": "ACTION" | "UNSEEN" | "FIGURES", "message": "Checking that with the
app…" }`, then the new answer's `tool` / `token` events. The web app drops the text streamed so far and shows the
message under the typing indicator. The model sees its own answer and a note from the app saying why it is not backed,
naming its tools and repeating what its tools returned in the turn; the note is never stored, and the transcript and
the memory keep only the final answer. An answer still not backed after that is marked like an answer with
unverified figures (`notice` UNVERIFIED_FIGURES, `unverified: true`). Figures count as grounded only in the customer's
messages, the context line and tool results: the assistant's own earlier answers are not a source (an invented price
was "confirmed" by the next turn). Counted in `grocery_ai_retried_answers_total{agent,reason}`.

**Simple shopping commands are handled in code (2026-11-20).** Before the model, `ShoppingCommands`
(`grocery.ai.shopping-commands`, `AI_SHOPPING_COMMANDS`, default true) recognises what customers type most:

| Command | Examples | Tools (through ToolGuard) |
|---|---|---|
| show the cart | "show my cart", "what's in my cart?" | `getCart` |
| add | "add 2 basmati rice", "add a kg of tomatoes and 2 milk", "rice 2 add pannu", "add 2 of that", "add the second one" | `addToCart` |
| remove, set, clear | "remove the tomatoes", "remove 1 milk", "set rice to 3", "clear my cart" | `updateCartItem` |
| total | "checkout", "how much will it cost with delivery?" | `previewCheckout` |
| place the order | "place my order" | `previewCheckout`, `placeOrder` (an approval, as from the model) |
| product questions | "do you have basmati rice?", "which biscuits do you have?", "find paneer", "atta irukka?"; the whole list (2026-11-21: up to 20 different products, not 5): "show me all available rice varieties", "list all the dals", "what rice varieties do you have?", "which brands of ghee do you have?". Each line quotes the cheapest store for that pack size, in stock first, the nearer on a tie ("Ponni Rice (1 kg) – ₹62.00 at T. Nagar Daily Needs"), and "add 2 of the first one" adds that store's listing | `searchProducts` |
| prices | "what's the cheapest milk?", "any ghee under ₹400?", "rice between ₹100 and ₹200", "the most expensive ghee", "how much is basmati rice?", "milk price" | `searchProducts` (with `minPrice` / `maxPrice` / `sort`) |
| add by price | "add the cheapest milk", "add 2 packets of the cheapest milk", "add 2 milk under ₹50" (asks which one when several qualify) | `addToCart` |

They answer in under a second without a model call, with the same permissions, audit, `tool` / `approval` events
and memory as the model's own tool calls. Products are matched by their words (exact name first, then a name whose
other words are its brand, and "milk" prefers names ending in "milk"), in the cart's store or else the stores that
deliver home, nearest first. A product already in the cart gets more. When a product is unclear the answer asks
("Which one would you like?", "Do you mean Tomato Ketchup?"), and so does a weight that is not a whole number of packs
("Wheat Atta comes in 5 kg packs. How many packs?"). The question is remembered with the answer (`quickChoice`), so a
bare reply ("the second one", "2", "yes") is understood. A cart holds one store, so a product found only at another
store is explained, not added. Other conditions ("the best rice", "from Adyar", "snacks" or another category of
several kinds), restaurants and their food, a product search with no results and anything else go to the model.
Counted as outcome `COMMAND` in `grocery_ai_model_latency_seconds`.

**Prices in code (2026-11-20).** Asked "any ghee under ₹400?", the model answered "There is no ghee under ₹400. The
cheapest ghee available is ₹340.00": sorted by price, the search put Halwa and Ladoo ("made with ghee") before the
ghee. Price questions are now answered from the search (up to 50 results, filtered and sorted by the catalogue). A
product counts when its name or brand has the words (the same "milk is not Milk Bread" rule as above), when its
category is exactly that kind ("fruits", "dairy"), or when it is in a category of several kinds with a part that is
these words and its own description has them: in "Biscuits and Cookies", Britannia Marie Gold ("Light, crisp Marie
tea biscuits") is a biscuit. The description comes from the search card's `shortDescription` (catalog-query, since
2026-11-20: no extra calls); only a description cut short at 300 characters, or a card without the field, is read from
the product's detail (at most 8 per question). The rest
of such a category never counts (chicken in "Eggs, Meat and Seafood" is not an egg, Halwa is not a chocolate), and
pet products count only when the words ask for a pet (Dog Biscuits). "Which biscuits do you have?" lists the same
products. One listing per product and pack size, in stock, the cheapest store first, then the nearest. Nothing within the
bounds gets the nearest price ("No “ghee” costs ₹300.00 or less … The cheapest is Ghee (500 ml) at ₹340.00"). The
listings shown are remembered with their ids, so "add 1 of that" adds the listing whose price was given, unless the
cart holds another store. A price question about a product that no store has goes to the model.

**Context window.** The service asks Ollama for an 8192-token window (`AI_OLLAMA_CONTEXT_TOKENS`, sent as `num_ctx`).
The shopping prompt and its tools take about 3,100 tokens, so with Ollama's default of 4096, one search plus one cart
change overflowed it, and Ollama dropped the start of the prompt: the rules and the tools. The chat also keeps the
history within the window: the newest exchanges that fit next to the prompt, the tools, the new message and 3,000
tokens for the turn's tool results and answer (about 3 characters a token). Ollama runs with flash attention and an
f16 KV cache (about 1.1 GB for qwen3:4b). An 8-bit cache would halve that, but it read prompts at 30 tokens/s on this
CPU against 46 tokens/s.

**Prompt order (2026-11-20).** The model is sent: the assistant's rules (one system message, the same for every user),
the remembered conversation, the user's context line as a second system message, then the new message. Ollama
(0.40, llama.cpp server) keeps what it read last, and a RAM store of earlier prompts (up to 8,192 tokens), and reads
only what follows the longest start a new prompt shares with one of them. qwen3's template puts the tool definitions
right after the first system message. With the context line inside that message, every changed cart, another customer
or a new day changed the start of the prompt. For the shopping assistant only the first 1,132 tokens were reused, and
Ollama read the context line, ~2,100 tokens of tools and the conversation again: 83–96 s on this CPU (i5-12500, 6
cores, ~50 tokens/s), against 1–5 s when the start was reused. Now the rules and the tools are reused across users
and turns. The context line is a system message rather than a user message so that a customer cannot type something
that passes for it. (Measured directly: a second system message is not merged into the first, so it stays after the
tools.) Live, with two Chennai customers taking turns: another customer's first question reused 3,078 tokens and
was read in 5.1 s; after a cart change the next question read 17.5 s (its earlier messages included), and answers
that needed the model took 34–59 s in all, against 60–170 s before. Only the first question after the model loads
still reads everything (75 s). The rest is writing: about 4 tokens/s, slower with a longer prompt (8.2 tokens/s after
a short prompt, 4.0 after a 3,600-token one), and a tool call means two model calls.

**New tools.** `admin-bi` (any admin): `getStakeholderCounts(type?)` and `listStakeholders(type, status?, query?)` over
`GET /api/admin/{vendors|restaurants|delivery-partners}` and `GET /api/admin/customers` (counts per onboarding status;
names, cities, dates and ids, never contact data). `shopping`: `searchProducts` takes `category` by name
(an unknown name with a text search searches every category, with a `note` saying so; without a text search, `CATEGORY_NOT_FOUND` lists every category). Like the storefront, it searches only the stores that deliver to the
customer's default address (`GET /api/stores/nearby`, up to 50 `vendorId`s; the result's `stores` says so), every store
when the address has no location or the store list fails, and answers `NO_STORE_DELIVERS` when no store delivers there.
A text search in a category that finds nothing is repeated in every category, with a `note` saying so: the small
model adds categories nobody named (tomatoes in Fruits, then "no tomatoes in stock").

**History.** `PATCH /api/ai/conversations/{id}` `{ title }` (owner only, 1–80 characters after trimming) renames a
conversation. The web app reopens the user's latest conversation when the assistant starts (after a reload or a new
sign-in), from `GET /api/ai/conversations`; nothing is kept in the browser.

**Voice** (`AI_VOICE_MODE`: `server`, `browser`, `off`):

| | |
|---|---|
| `GET /api/ai/voice/status` | `{ mode, transcription, speech, maxRecordingSeconds, maxAudioBytes, maxSpeechCharacters }` |
| `POST /api/ai/voice/transcriptions` | multipart `audio` (≤ 10 MB; WebM, Ogg, WAV, MP3, MP4/M4A or FLAC, checked from the bytes), `language?` → `{ text }` |
| `POST /api/ai/voice/speech` | `{ text }` (≤ 4000 characters of plain text) → `audio/mpeg` |

Errors (`application/problem+json`): 400 `AUDIO_EMPTY` / `VALIDATION_FAILED`, 403 `VOICE_NOT_ALLOWED`, 409
`VOICE_DISABLED`, 413 `AUDIO_TOO_LARGE`, 415 `UNSUPPORTED_AUDIO_FORMAT`, 422 `AUDIO_UNREADABLE` / `NO_SPEECH_DETECTED`,
429 `VOICE_RATE_LIMITED` (+ `Retry-After`), 503 `VOICE_UNAVAILABLE`, 504 `VOICE_TIMEOUT`. A transcript is put into the
chat input and sent through the normal chat API; the answer to a spoken question is read aloud.

**Acceptance.**
- A SUPER_ADMIN asking "How many vendors have been approved by the admin?" gets the vendor service's counts per
  status, read with the admin's token, and links to `/admin/vendors` and `/admin/approvals?type=vendors`.
- A vendor, restaurant owner and delivery partner see their own business in the context; a store waiting for approval
  is told why its store tools refuse.
- A customer cannot reach the partner directories (`TOOL_NOT_ALLOWED`); SUPPORT is not offered suspensions.
- Another user's conversation is 404 for reading, renaming, continuing and deleting, an admin's request included.
- A recording that is not audio is refused before the speech service is called; the user's token never reaches it.
