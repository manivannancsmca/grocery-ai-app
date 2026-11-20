# ai-orchestrator-service

Phase 11: the **Shopping** (customers) and **Admin BI** (every admin role) assistants on Spring AI 2.0.2, and **human
approval** of sensitive actions. Phase 12 (0.12.0) adds the **Vendor**, **Restaurant** and **Delivery** assistants and
the shopping tool `requestReturn`. The assistants only act through explicit backend tools that call the business APIs
**through the gateway with the user's own token** (the AI has exactly the user's rights); every tool call passes
**ToolGuard** and is audited. Anything with money or operational impact becomes an **approval request**: a human decides
it, the action runs once with the approver's authority and a signed `X-Approval-Token`, and the target service verifies
the token (grocery-common 0.8.0) before running it.

| | |
|---|---|
| Port | 8115 |
| Database | `ai_db` (user `ai_user`) — tables `conversation`, `ai_message` (V7: `unverified_figures`), `SPRING_AI_CHAT_MEMORY`, `ai_tool_invocation` (V8: `transport`), `approval_request`, `outbox_event` |
| Publishes | `ai.events` (aggregate `Approval`, key approvalId): `ApprovalRequested`, `ApprovalDecided`, `ApprovalExecuted`, `ApprovalFailed` (Avro schemas 1.7.0) |
| Consumes | nothing |
| Calls | the API gateway (`GROCERY_AI_GATEWAY_URL`) as the caller: REST, and the MCP pilot's servers `/api/mcp/catalog` and `/api/mcp/orders`; the model: Ollama (`OLLAMA_BASE_URL`), or Anthropic (`AI_PROVIDER=anthropic`), or none (`scripted`) |
| API | `docs/api/phase-11-agentic-ai-and-approvals.md`, `docs/api/phase-12-post-mvp-features.md` §6; ADR-0008 |
| Debezium | `deploy/debezium-connector.json` (connector `ai-outbox`, server id 5115) |

## Endpoints

| Method & path | Who | |
|---|---|---|
| `GET /api/ai/agents` | signed in | the agents the caller may use (CUSTOMER → `shopping`, admin roles → `admin-bi`, VENDOR → `vendor`, RESTAURANT_OWNER → `restaurant`, DELIVERY_PARTNER → `delivery`, DELIVERY_MANAGER → `admin-bi` + `delivery`) |
| `GET /api/ai/status` | signed in | `{ provider, model, available, checkedAt }` — Ollama probed (`/api/tags`, model pulled) at most every 30 s |
| `POST /api/ai/agents/{agent}/chat` | the agent's role | `{ conversationId?, message }` → `text/event-stream`: `conversation`, `tool`*, `approval`*, `token`*, then `done` or `error`. Before the stream: 404 `AGENT_NOT_FOUND`, 403 `AGENT_NOT_ALLOWED` (audited), 400 `VALIDATION_FAILED`, 404 `CONVERSATION_NOT_FOUND`, 429 `AI_RATE_LIMITED` (+ `Retry-After`) — always `application/problem+json` |
| `GET /api/ai/conversations?agent=&page=&size=` | signed in | own conversations, most recently active first |
| `GET /api/ai/conversations/{id}` / `DELETE …` | owner | transcript with each answer's `tools` and `approvals` / 204 (messages and memory deleted; audit and approvals kept) |
| `PATCH /api/ai/conversations/{id}` | owner | `{ title }` → `ConversationSummary`; trimmed, whitespace collapsed, 1–80 characters (400 `VALIDATION_FAILED`); the list order (last activity) is kept. Another user's conversation is 404 `CONVERSATION_NOT_FOUND`, an admin's included |
| `GET /api/ai/voice/status` | signed in | `{ mode: SERVER\|BROWSER\|OFF, transcription, speech, maxRecordingSeconds, maxAudioBytes, maxSpeechCharacters }` (OFF for a user without an assistant) |
| `POST /api/ai/voice/transcriptions` | a user with an assistant | multipart `audio` (+ optional `language`, e.g. `hi`) → `{ text }`. 400 `AUDIO_EMPTY`, 413 `AUDIO_TOO_LARGE`, 415 `UNSUPPORTED_AUDIO_FORMAT` (checked from the bytes: WebM, Ogg, WAV, MP3, MP4/M4A, FLAC), 422 `AUDIO_UNREADABLE` / `NO_SPEECH_DETECTED`, 429 `VOICE_RATE_LIMITED`, 503 `VOICE_UNAVAILABLE`, 504 `VOICE_TIMEOUT`, 409 `VOICE_DISABLED` (mode not SERVER), 403 `VOICE_NOT_ALLOWED` |
| `POST /api/ai/voice/speech` | a user with an assistant | `{ text }` (≤ 4000 characters after markdown is removed) → `audio/mpeg`, `Cache-Control: no-store`; errors as above, always `application/problem+json` |
| `POST /api/ai/agents/admin-bi/insights` | any admin | `{ date? }` → `InsightReport` (model via structured output, else `generatedBy.provider = "rules"`) |
| `GET /api/approvals?scope=mine\|to-decide&status=&page=&size=` | signed in | `PageResponse<ApprovalView>`, newest first |
| `GET /api/approvals/{id}` | requester, the customer it is for, an eligible decider, any admin for admin actions | `ApprovalView` · 404 `APPROVAL_NOT_FOUND` |
| `POST /api/approvals/{id}/approve` | eligible decider | `{ comment? }` → executes synchronously → `COMPLETED` (`result`) or `FAILED` (`failure`) · 403 `APPROVAL_FORBIDDEN` / `FOUR_EYES_REQUIRED` · 409 `APPROVAL_NOT_PENDING` (`currentStatus`) |
| `POST /api/approvals/{id}/reject` | eligible decider | `{ comment }` (required ≤ 300) → `REJECTED` |
| `POST /api/approvals/{id}/cancel` | requester / the customer it is for | → `CANCELLED` |
| `GET /api/admin/ai/tool-invocations?agent=&userId=&tool=&status=&from=&to=&page=&size=` | any admin | audit, newest first; `from`/`to` India dates, inclusive; `arguments` always a JSON object |
| `GET /api/admin/ai/summary?date=` | any admin | the day's conversations, messages, tool calls, blocked, failed, approvals by outcome, model errors, average latency, provider/model/available |
| `GET /.well-known/approval-jwks.json` | **public, internal** (the gateway does not route it) | the approval-token public key(s) for the target services (`APPROVAL_JWKS_URI`) |

## Who is asking: the stakeholder context

Every turn knows the signed-in user from the validated JWT alone, never from the request body or the conversation:

- **The agent** comes from the URL and is checked against the token's roles (403 `AGENT_NOT_ALLOWED`, audited).
- **The tools offered to the model** are only those the caller's role may use (`ToolGuard.callbacks`): a SUPPORT
  admin is not offered suspensions, a delivery partner not the managers' tools. A name the model calls anyway is
  resolved to a guarded callback and BLOCKED (`TOOL_NOT_ALLOWED` / `ROLE_NOT_ALLOWED`), audited as before.
- **The context line** (`ContextBuilder`) states the role and what it may see in plain words ("a grocery vendor: sees
  only their own store's …"), the first name, today's date in India and the user's own business, read live with the
  user's token: the customer's cart and default address, the vendor's store (`GET /api/vendors/me`: name, status),
  the restaurant (`GET /api/restaurants/me`: name, status, accepting orders) or the delivery partner's profile status.
  A store that is not approved yet is said so, and the model explains why its store tools answer `VENDOR_NOT_ACTIVE`.
- **A live snapshot** of what each role asks most, in the same context line: the customer's latest order
  (`GET /api/orders?size=1`), an approved vendor's or restaurant's orders today (`…/reports/sales?from=today&to=today`)
  and the vendor's low-stock count, an approved delivery partner's active delivery and waiting offers. Small local
  models sometimes answer "where is my latest order?" without calling a tool; with the snapshot they answer from real
  data. Admins get none (their questions need the tools, and the model calls them).
- **Grounding safety net** (`Grounding`): when a turn returned no tool data, every figure in the answer — numbers of
  two or more digits and references such as GA-…/PO-… — must appear in the question, the conversation so far, the
  context line or the tools' refusals. Otherwise (the live failure it was written for: a copied example order number)
  the stream sends `notice` `{ code: UNVERIFIED_FIGURES, message }` before `done`, the answer is stored with
  `ai_message.unverified_figures` (V7, shown again when the conversation is reopened as `messages[].unverified`), and
  `grocery_ai_unverified_answers_total{agent}` counts it. The answer was already streamed; it is marked, not hidden.
- **Every tool call** goes through the gateway with the user's own token, so each business service applies that
  user's rights (a vendor only ever gets their own store's orders). The prompt also forbids revealing other people's
  private details, and the admin directory tools never pass contact data to the model.
- **Natural language** (`Agents.CONVERSATION`, shared by every agent): casual wording and typos, follow-ups that
  continue the previous question, one short clarifying question when a request is ambiguous, the answer first in plain
  words, and numbers only from tool results (otherwise "I can't see that", with the page that shows it).

## A chat turn

```
POST …/chat ─► agent from the URL (never the model) ─► role check (403, audited) ─► rate limit (429)
            ─► user message stored (kept even if the model fails) ─► SSE response, turn on a virtual thread:
   system prompt + server context line (role and scope, first name, India date, the user's own business) + last 19
   memory messages + message
   ─► ChatClient.stream() with the agent's tools, each a GuardedToolCallback, and a per-turn ToolCallingAdvisor
       (DefaultToolCallingManager: max 6 calls, unknown names resolved to a guarded callback)
   ─► tokens forwarded as they arrive; ToolGuard streams tool / approval events
   ─► done (answer stored, memory updated) | error AI_UNAVAILABLE / AI_TIMEOUT / AI_TOOL_LIMIT / AI_ERROR
```

- **Memory**: Spring AI's JDBC chat memory repository (`SPRING_AI_CHAT_MEMORY`, created by Flyway) in a
  `MessageWindowChatMemory` of 20; only completed exchanges are added. The full transcript is `ai_message`.
- **Timeouts**: the model's next token (or the insights answer) within `AI_MODEL_TIMEOUT` (120 s); every tool call 5 s;
  Ollama connect 5 s; Spring AI retries are off (`spring.ai.retry.max-attempts=0`), so a down model fails fast.
- **Tool results are untrusted data**: the system prompt says so; the backend authorizes every call anyway.

## ToolGuard (contract §3)

1. Tool in the agent's allowlist? Else `BLOCKED TOOL_NOT_ALLOWED` (another agent's tool or an invented name — never an
   exception).
2. May the caller's role use / propose it? Else `BLOCKED ROLE_NOT_ALLOWED`.
3. Arguments valid (types, ranges, lengths; lenient about `"2"` vs `2`)? Else `BLOCKED INVALID_ARGUMENTS`.
4. READ / LOW_RISK_WRITE run through the gateway; SENSITIVE builds a proposal → `APPROVAL_REQUIRED`.

The model receives the trimmed API result, or `{status: APPROVAL_REQUIRED, approvalId, title, expiresAt}`,
`{status: BLOCKED, reason, message}`, `{status: ERROR, code, message}`. A 7th call in one message ends the turn with
`AI_TOOL_LIMIT` (audited as `BLOCKED AI_TOOL_LIMIT`). Every call is a row in `ai_tool_invocation`.

## Approvals (contract §4–§5)

- **Server-built parameters**: `PLACE_ORDER` reads a fresh checkout preview → `{addressId (the previewed one),
  expectedCartUpdatedAt, expectedTotal, note?}`; `REFUND_PAYMENT` resolves the order and payment (`amount` defaults to
  everything still refundable; more is `INVALID_ARGUMENTS`); cancellations / suspensions resolve the order / business
  and refuse what cannot be done now. Stored as canonical JSON (keys sorted) with its SHA-256 (hex; `fingerprint` = 8
  chars); the summary is rendered from them; the model's note is `rationale`.
- **Duplicates**: same requester + action + body hash while pending → the same request (also enforced by a unique
  generated column, so concurrent duplicates collapse).
- **Rules**: `PLACE_ORDER` / `CANCEL_ORDER` / `REQUEST_RETURN` — the customer only, 15 min; `REFUND_PAYMENT` —
  SUPER_ADMIN / FINANCE_ADMIN, four-eyes above `AI_FOUR_EYES_REFUND_ABOVE`; `ADMIN_CANCEL_ORDER` — SUPER_ADMIN /
  OPS_ADMIN; suspensions — SUPER_ADMIN / OPS_ADMIN, always four-eyes; `REASSIGN_DELIVERY` — DELIVERY_MANAGER / OPS_ADMIN
  / SUPER_ADMIN, no four-eyes; role-decided actions expire after 24 h. The vendor's and owner's own changes
  (`ADJUST_STOCK`, `CHANGE_PRICE`, `SEND_PURCHASE_ORDER`, `SET_MENU_ITEM_AVAILABILITY`, `ACCEPT_STORE_ORDER`,
  `REJECT_STORE_ORDER`) are **self-decided** like a customer's order: `approverUserId` = requester, no `onBehalfOf`,
  15 min, visible only to that user (`ApprovalAction.Decider`: CUSTOMER, REQUESTER or ROLES).
- **Exactly once**: approve = PENDING → APPROVED → EXECUTING in one transaction under the `@Version` lock (a concurrent
  approve gets 409), then the HTTP call outside any transaction, then COMPLETED / FAILED. Never retried. The call carries
  the approver's `Authorization`, `X-Approval-Token`, `Idempotency-Key = approvalId` (orders, refunds) and
  `X-Correlation-Id`. 502/503/504 from the gateway or no answer → `TARGET_UNAVAILABLE`.
- **Token**: RS256 JWS, `typ approval+jwt`, `kid` = RFC 7638 thumbprint; claims `iss ai-orchestrator-service`, `aud`
  target (`order-service`, `payment-service`, `vendor-service`, `restaurant-service`, `inventory-service`,
  `pricing-service`, `delivery-service`), `jti` approvalId, `sub` approver, `obo` customer (customer actions only),
  `act`, `htm`, `htu`, `bsh` =
  base64url(SHA-256(body fixed at proposal)), `exp = iat + 60 s`. Key from `AI_APPROVAL_SIGNING_KEY` (PKCS#8 PEM, `\n`
  escapes allowed) or generated at startup (one local replica only).
- **Pollers** (`AI_APPROVAL_EXPIRY_INTERVAL`, 30 s, `SKIP LOCKED`, safe with replicas): overdue PENDING → `EXPIRED`;
  `EXECUTING` for more than 2 min → `FAILED EXECUTION_INTERRUPTED`.

## Admin BI: vendors, restaurants, delivery partners and customers

Read tools of `admin-bi` (any admin role), on the existing admin directories, as the admin:

| Tool | Calls | Gives the model |
|---|---|---|
| `getStakeholderCounts(type? VENDOR\|RESTAURANT\|DELIVERY_PARTNER)` | `GET /api/admin/{vendors\|restaurants\|delivery-partners}?status=X&size=1` for each of APPROVED, PENDING_APPROVAL, SUSPENDED, REJECTED, DRAFT (the page's `totalElements`); without a type all three and `GET /api/admin/customers?size=1` | `{ vendors: { total, byStatus }, …, customers, statusMeaning }`; links to the directory and, when something waits, the approval queue (`/admin/approvals?type=vendors`) |
| `listStakeholders(type, status?, query?)` | `GET /api/admin/{…}?status=&query=&size=10` | `{ totalFound, listed, vendors: [{ id, name, status, city, submittedAt, approvedAt, statusReason }] }` — no e-mail, phone, GSTIN or street address; the ids feed `suspendVendor` / `suspendRestaurant` |

`searchProducts` (shopping) takes `category` by name ("fruits", "Fruit", "vegitables", "dairy"): `CategoryMatcher`
finds it in `GET /api/catalog/categories` (same name → singular/plural → contained → up to 2 letters off, the largest
category winning) and searches with its `categoryId`; an unknown name is `CATEGORY_NOT_FOUND` listing the categories.

## Voice

Voice is another way to type and to read: a transcript goes into the chat input and the user sends it through the
normal chat API (agent, role, rate limit, ToolGuard all apply); nothing in the voice API reaches the model or the
business services. `grocery.ai.voice.mode` (`AI_VOICE_MODE`):

- `server`: this service calls an OpenAI-compatible audio API (`AI_VOICE_BASE_URL` including `/v1`):
  `POST /audio/transcriptions` (multipart file, model, language?, response_format=json) and `POST /audio/speech`
  (model, voice, input, response_format=mp3). Locally that is speaches (compose profile `voice`: faster-whisper +
  Kokoro on CPU); in production OpenAI or a self-hosted speaches. The provider gets the service's own key, never the
  user's token.
- `browser` (default): the web app uses the browser's speech recognition and synthesis; the voice endpoints answer
  409 `VOICE_DISABLED`.
- `off`: no voice (the default of the `aws` profile until an operator chooses).

Checks before the provider is called: the caller has an assistant, at most 10 MB, a real audio container (magic
bytes, not the Content-Type), a language code if given, 1–4000 characters of plain text to speak, and
`AI_VOICE_PER_HOUR` (120) voice calls per user and hour on a replica (a cost guard; the gateway's rate limit applies
too). Every call has `AI_VOICE_TIMEOUT` (30 s; 60 s for the local CPU speaches in compose and host-run); a call cut off
by it is 504 `VOICE_TIMEOUT` even when the server had already started streaming its answer. The gateway route
`ai-voice` (`/api/ai/voice/**`) waits 75 s instead of the 15 s default. Logs carry sizes, formats and timings, never
audio or words; the timer `grocery_ai_voice_latency_seconds{operation,outcome}` counts them. Locally speaches keeps its
models loaded (`WHISPER__TTL=-1`) and `speaches-init` warms both up with a speak-then-transcribe self-test.

## The Phase 12 assistants

All tools call the gateway with the caller's token; SENSITIVE ones become approvals whose parameters and summary the
server builds from fresh data (`ProposalBuilder`, `StoreProposals`).

**`vendor` (VENDOR)** — `VendorTools`

| Tool | Risk | Calls |
|---|---|---|
| `getSalesReport(from?, to?)` | READ | `GET /api/vendor/reports/sales` |
| `listInventory(filter? LOW_STOCK\|OUT_OF_STOCK, query?)` | READ | `GET /api/vendor/inventory?q=&filter=&size=20` |
| `getStock(productId)` | READ | `GET /api/vendor/inventory/{productId}` |
| `listProducts(query?)` | READ | `GET /api/vendor/products?q=&size=20` |
| `getPrice(productId)` | READ | `GET /api/vendor/prices/{productId}` |
| `listStoreOrders(status?)` | READ | `GET /api/vendor/orders?status=&size=20` |
| `listPurchaseOrders(status?)` | READ | `GET /api/vendor/purchase-orders?status=&size=20` |
| `listSuppliers()` | READ | `GET /api/vendor/suppliers` (array or page) |
| `listReturns(status?)` / `listReviews()` | READ | `GET /api/store/returns` / `GET /api/store/reviews` (size 20) |
| `draftPurchaseOrder(supplierId, lines[{productId, quantity, unitCost}], notes?)` | LOW_RISK_WRITE | `POST /api/vendor/purchase-orders` `{supplierId, lines[{productId, orderedQuantity, unitCost}], notes?}` (a DRAFT) |
| `adjustStock(productId, type, quantity, reason, note?)` | SENSITIVE → `ADJUST_STOCK` | `POST /api/vendor/inventory/{productId}/adjustments` `{type, quantity, reason, note?}` · aud `inventory-service` · summary: product, current stock, change, new stock, reason (a decrease beyond `available` or a count below `reserved` is INVALID_ARGUMENTS) |
| `changePrice(productId, sellingPrice, mrp?, note?)` | SENSITIVE → `CHANGE_PRICE` | `PUT /api/vendor/prices/{productId}` `{mrp (current kept), sellingPrice, note?}` · aud `pricing-service` · summary: old / new price, change %, whether pricing-service will still wait for an admin (above `approvalThresholdPercent`); the result says `APPLIED` or `PENDING_APPROVAL` |
| `sendPurchaseOrder(purchaseOrderNumber)` | SENSITIVE → `SEND_PURCHASE_ORDER` | `POST /api/vendor/purchase-orders/{id}/send` `{}` · aud `vendor-service` · summary: supplier, lines, total (DRAFT only); the result carries `purchaseOrderId`, `purchaseOrderNumber`, `status` |

**`restaurant` (RESTAURANT_OWNER)** — `RestaurantTools`

| Tool | Risk | Calls |
|---|---|---|
| `getOrderQueue(status?)` | READ | `GET /api/restaurant/orders?status=(ACTIVE)&size=20` |
| `getSalesReport(from?, to?)` / `getMenu()` | READ | `GET /api/restaurant/reports/sales` / `GET /api/restaurant/menu` |
| `listReturns(status?)` / `listReviews()` | READ | `GET /api/store/returns` / `GET /api/store/reviews` |
| `setMenuItemAvailability(menuItemId, available)` | SENSITIVE → `SET_MENU_ITEM_AVAILABILITY` | `PATCH /api/restaurant/menu/items/{id}/availability` `{available}` · aud `restaurant-service` · summary: dish, category, price, now → new |
| `acceptOrder(orderNumber, readyInMinutes 5–180)` | SENSITIVE → `ACCEPT_STORE_ORDER` | `POST /api/restaurant/orders/{orderId}/accept` `{readyInMinutes}` · NEW orders only |
| `rejectOrder(orderNumber, reason OUT_OF_STOCK\|STORE_CLOSED\|TOO_BUSY\|OTHER, note?)` | SENSITIVE → `REJECT_STORE_ORDER` | `POST /api/restaurant/orders/{orderId}/reject` `{reason, note?}` (note required for OTHER) |

**`delivery` (DELIVERY_PARTNER, DELIVERY_MANAGER)** — `DeliveryTools`; ToolGuard's role check splits the tools
(the other role's tool → `BLOCKED ROLE_NOT_ALLOWED`).

| Tool | Role | Risk | Calls |
|---|---|---|---|
| `getActiveDelivery()` | partner | READ | `GET /api/delivery/deliveries/active` (204 = none; the drop phone is not given to the model) |
| `getMyOffers()` / `getMyHistory()` / `getMySummary()` | partner | READ | `GET /api/delivery/offers` / `GET /api/delivery/history?size=10` / `GET /api/delivery/history/summary` |
| `listDeliveries(status?, assignmentFailed?)` | manager | READ | `GET /api/admin/deliveries?status=&assignmentFailed=&size=20` |
| `getDelivery(orderNumber)` | manager | READ | `GET /api/admin/deliveries?q=` → `/{deliveryId}` |
| `reassignDelivery(orderNumber, partnerId?, reason?)` | manager | SENSITIVE → `REASSIGN_DELIVERY` | `POST /api/admin/deliveries/{deliveryId}/reassign` `{partnerId?, reason?}` · aud `delivery-service` · SEARCHING / ASSIGNED only; a given partner must be online and free (`GET /api/admin/delivery-partners/online`) · summary: order, store, current and new partner |

**`shopping`** gains `requestReturn(orderNumber, items[{name | itemId, quantity}], reason, note?)` → `REQUEST_RETURN`:
`POST /api/orders/{id}/returns` `{items[{itemId, quantity}], reason, note?}` · aud `order-service`, `obo` the customer.
The DELIVERED (and `returnable`) order is resolved by number, names are matched to its lines (ambiguous / unknown /
too many units → INVALID_ARGUMENTS), and the summary estimates the refund (prorated line total less the coupon share,
plus the paid delivery fee when the whole order comes back); order-service computes the real refund. A COMPLETED
request's `result` is the created return: `{ returnId, returnNumber, refundAmount, orderId, orderNumber, status }`
(from the ReturnView).

Insights stay admin-only (`admin-bi`).

## The scripted model (contract §7, Phase 12 §6)

`AI_PROVIDER=scripted` (refused unless the `local` or `docker` profile is active) replaces Ollama with
`ScriptedChatModel`, a real Spring AI `ChatModel` (call and stream) that emits tool-call messages and template answers,
so ToolGuard, the advisor loop and the APIs run unchanged. It recognises the agent from the request's tools and
understands: `add <n> <words>`, `search|find <words>`, `cart|show my cart`, `checkout|place my order`,
`cancel order <NO>`, `status of <NO>`, `return <n> <item words> from <NO> because <reason>` (shopping);
`stats|sales today`, `stuck orders`, `refund <amount|all> for <NO> because <note>`, `cancel <NO> because <reason>`,
`suspend vendor|restaurant <id> because <reason>` (admin-bi); `low stock`, `sales`, `set stock <product words> to <n>`,
`price <product words> <amount>` (vendor); `orders`, `sales`, `mark <dish words> sold out`, `accept <NO> in <n> min`
(restaurant); `active delivery`, `deliveries`, `reassign <NO>` (delivery); `use tool <name> <json>` (every agent). For
insights it answers the rules' report as JSON.

## Configuration

| Property | Env | Default |
|---|---|---|
| `grocery.ai.provider` | `AI_PROVIDER` | `ollama` (`anthropic`: Claude, the production model; `scripted`: local/docker only) |
| `grocery.ai.anthropic.api-key` / `.model` | `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | empty (= unavailable, nothing sent) / `claude-opus-5-5` |
| `grocery.ai.anthropic.base-url`, `.max-tokens`, `.max-retries` | `ANTHROPIC_BASE_URL`, `AI_ANTHROPIC_MAX_TOKENS`, `AI_ANTHROPIC_MAX_RETRIES` | `https://api.anthropic.com`, `2048`, `1` |
| `grocery.ai.mcp.enabled` | `AI_MCP_ENABLED` | `true`: `searchProducts` and `getOrderStatus` through catalog-query's and order-service's MCP servers (REST when one cannot be reached) |
| `grocery.ai.gateway-url` | `GROCERY_AI_GATEWAY_URL` | `http://localhost:8080` (docker: `http://api-gateway:8080`) |
| `grocery.ai.ollama.base-url` / `.model` | `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` (docker: `http://ollama:11434`) / `qwen3:4b-instruct` |
| `grocery.ai.model-timeout` | `AI_MODEL_TIMEOUT` | `120s` |
| `grocery.ai.messages-per-hour` | `AI_MESSAGES_PER_HOUR` | `30` |
| `grocery.ai.tool-timeout`, `max-tool-calls`, `memory-messages`, `status-cache-ttl` | — | `5s`, `6`, `20`, `30s` |
| `grocery.ai.approvals.ttl-customer` / `ttl-admin` | `AI_APPROVAL_TTL_CUSTOMER` / `AI_APPROVAL_TTL_ADMIN` | `15m` / `24h` |
| `grocery.ai.approvals.four-eyes-refund-above` | `AI_FOUR_EYES_REFUND_ABOVE` | `2000.00` |
| `grocery.ai.approvals.expiry-interval` | `AI_APPROVAL_EXPIRY_INTERVAL` | `30s` |
| `grocery.ai.approvals.signing-key` | `AI_APPROVAL_SIGNING_KEY` | empty = generated |
| `grocery.ai.approvals.execution-timeout` | `AI_APPROVAL_EXECUTION_TIMEOUT` | `10s` |
| `grocery.ai.approvals.executing-timeout`, `token-ttl`, `pollers-enabled` | — | `2m`, `60s`, `true` |
| `grocery.ai.voice.mode` | `AI_VOICE_MODE` | `browser` (`server`, `off`; aws profile: `off`) |
| `grocery.ai.voice.base-url` / `.api-key` | `AI_VOICE_BASE_URL` / `AI_VOICE_API_KEY` | empty (required for `server`) / empty |
| `grocery.ai.voice.transcription-model` / `.speech-model` / `.speech-voice` | `AI_VOICE_TRANSCRIPTION_MODEL` / `AI_VOICE_SPEECH_MODEL` / `AI_VOICE_SPEECH_VOICE` | `whisper-1` / `tts-1` / `alloy` (compose and host-run: speaches' `Systran/faster-whisper-small`, `speaches-ai/Kokoro-82M-v1.0-ONNX`, `af_heart`) |
| `grocery.ai.voice.language`, `.timeout`, `.requests-per-hour` | `AI_VOICE_LANGUAGE`, `AI_VOICE_TIMEOUT`, `AI_VOICE_PER_HOUR` | detected, `30s`, `120` |
| `grocery.ai.voice.max-audio-bytes`, `max-recording-seconds`, `max-speech-characters` | — | `10485760`, `60`, `4000` |

Ollama runs with thinking off (`spring.ai.ollama.chat.think=false`) and temperature 0.2; Spring AI's model choice
follows the provider (`spring.ai.model.chat=${grocery.ai.provider}`), so no Ollama bean exists in scripted mode.

Claude (`AI_PROVIDER=anthropic`, the production model since 2026-11-20): `ModelProviderConfig` builds the
`AnthropicChatModel` from `grocery.ai.anthropic.*` (temperature 0.2, the model timeout, prompt caching of the rules
and tools). Locally, put `AI_PROVIDER=anthropic` and `ANTHROPIC_API_KEY` (optionally `ANTHROPIC_MODEL`) in
`infrastructure/local/.env`: `run-service.cmd` reads them for the AI tab and never prints the key. On AWS the key is the
Secrets Manager secret `grocery/<env>/ai-orchestrator-service/anthropic` (`api_key`).

## Events: Avro (ADR-0011)

The outbox stores each payload as JSON (`payload`) and in the Confluent wire format (`payload_avro`, subjects
`ai.events-com.grocery.events.ai.<EventType>`), which Debezium publishes. `kafka-clients` is a direct dependency only
because Confluent's serializer needs it; this service has no Kafka consumer.

## Package layout

```
com.grocery.ai
├── agent         AgentDefinition, Agents (shopping, admin-bi, vendor, restaurant, delivery: prompts, roles, tool allowlists)
├── tool          ToolSpec, ArgSchema, ToolGuard, GuardedToolCallback, GuardedToolCallingManager, ShoppingTools, AdminTools,
│                 VendorTools, RestaurantTools, DeliveryTools, StoreTools (shared store reads)
├── chat          ChatController, ChatService (SSE turn), SseChatSink, ContextBuilder, ChatException(+Advice)
├── conversation  Conversation, AiMessage, ConversationService (list, view, rename, delete — own conversations only)
├── voice         VoiceController, VoiceService, OpenAiAudioClient, AudioFormat, SpeechText, VoiceRateLimiter,
│                 VoiceException(+Advice)
├── approval      ApprovalRequest, ProposalBuilder, StoreProposals, ApprovalService, ApprovalExecutor, ApprovalTokenSigner,
│                 ApprovalSigningKey, ApprovalJwksController, ApprovalMaintenance, ApprovalEvents, ApprovalController
├── audit         ToolInvocation, AuditLog, AiMonitoringService, AdminAiController
├── insights      InsightReport, InsightRules, InsightsService, InsightsController
├── model         ScriptedChatModel, ModelStatus, ModelErrors
├── gateway       GatewayClient, BusinessApi, CallContext
└── config        AiProperties, AiConfig, ModelProviderConfig
```

## Run / test

```bash
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4; the gateway, Ollama and identity's JWKS are local fakes;
# the scripted model or minimal fakes stand in for the LLM
m() { MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace \
  maven:3.9.16-eclipse-temurin-25-noble "$@"; }
m mvn -q -B -f services/ai-orchestrator-service/pom.xml clean verify

# image (from infrastructure/local)
docker compose build ai-orchestrator-service
```
