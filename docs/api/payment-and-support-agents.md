# Payment agent, Customer support agent and complaints — API contract

Two more assistants for customers, next to the `shopping` assistant:

| Agent id  | Name                       | For        | Helps with                                                                                   |
|-----------|----------------------------|------------|----------------------------------------------------------------------------------------------|
| `payment` | Payment assistant          | `CUSTOMER` | payment status, failed / pending / successful payments, the payment window, refunds          |
| `support` | Customer support assistant | `CUSTOMER` | order status and tracking, cancellations, returns and their status, complaints               |

Both are ordinary agents of the AI orchestrator (ADR-0008): a system prompt, a tool allowlist and the roles that may
use them. Every tool calls the existing business APIs through the gateway **with the customer's own token**, so an
agent only ever sees the customer's own data, and the backend checks every permission again. Nothing sensitive happens
in the chat: cancelling an order and requesting a return stay approval requests the customer confirms in the app
(the same tools as the shopping assistant).

Complaints did not exist before; section 2 adds them to order-service so that the support agent (and the app's own
pages) can record and follow them.

---

## 1. How a customer uses them

The chat panel shows a switcher with **Shopping**, **Payments** and **Support** (the server's `GET /api/ai/agents`
lists the three for a customer). Each assistant keeps its own conversations. Each one says when a question belongs to
another assistant and suggests switching. The tools, prompts and context are described in
[section 3](#3-the-agents-in-detail).

## 2. Complaints (order-service)

A customer raises a complaint about an order (or about the app in general); support admins work through the queue and
resolve it with an answer the customer sees.

### 2.1 Model

`complaint` table in `order_db` (Flyway `V12__complaints.sql`). One open complaint per order is also enforced by the
database (a unique key on a generated `open_order_id` column, like returns).

| Field             | Type / values                                              | Notes                                                        |
|-------------------|------------------------------------------------------------|--------------------------------------------------------------|
| `id`              | UUID                                                       |                                                              |
| `complaintNumber` | `CMP-` + 8 characters (the order-number alphabet)          | unique, shown to the customer                                |
| `customerId`      | UUID                                                       | from the token, never from the request                      |
| `orderId`         | UUID, optional                                             | must be one of the customer's own orders                    |
| `orderNumber`     | string, optional                                           | copied from the order                                        |
| `storeName`       | string, optional                                           | copied from the order                                        |
| `category`        | `DELIVERY`, `PAYMENT`, `ITEMS`, `STORE`, `APP`, `OTHER`    | see 2.2                                                      |
| `description`     | 10–1000 characters (trimmed)                               | the customer's words                                         |
| `status`          | `OPEN` → `IN_PROGRESS` → `RESOLVED`                        | `OPEN` → `RESOLVED` is allowed too; `RESOLVED` is final      |
| `resolution`      | 5–1000 characters, set when resolved                       | the answer the customer sees                                 |
| `handledBy`       | admin user id, optional                                    | who started or resolved it (admin views only)                |
| `createdAt`, `updatedAt`, `resolvedAt` | instants                              |                                                              |

### 2.2 Categories

| Category   | Meaning                                                                                              |
|------------|------------------------------------------------------------------------------------------------------|
| `DELIVERY` | late, not delivered, delivered to the wrong place, the delivery partner's behaviour                   |
| `PAYMENT`  | charged but the order is not confirmed, charged twice, refund not received                            |
| `ITEMS`    | quality, damaged, wrong or missing items (a refund for items is a **return request**, not a complaint) |
| `STORE`    | the store's or restaurant's service                                                                   |
| `APP`      | the app or website does not work                                                                      |
| `OTHER`    | anything else                                                                                        |

### 2.3 Rules

- `orderId`, when given, must be one of the caller's orders: otherwise `404 ORDER_NOT_FOUND` (nobody learns that
  someone else's order exists).
- One open complaint (`OPEN` or `IN_PROGRESS`) per order: `409 COMPLAINT_ALREADY_OPEN`; the problem's `detail` names
  the open complaint's number (also in `complaintNumber` and `complaintId`). Once it is resolved, a new one is allowed.
- At most `grocery.order.complaints.max-open` open complaints per customer (`ORDER_COMPLAINTS_MAX_OPEN`, default 5):
  `409 TOO_MANY_OPEN_COMPLAINTS` (with `maxOpen`).
- `start`: only from `OPEN`; `resolve`: from `OPEN` or `IN_PROGRESS`. Anything else: `409 INVALID_COMPLAINT_STATUS`
  (with `currentStatus`). Two admins changing the same complaint at once: the second gets
  `409 CONCURRENT_MODIFICATION`.
- Validation errors: `400 VALIDATION_FAILED` with field errors (lengths are checked after trimming); an unknown
  category or a malformed `orderId` in the body: `400 REQUEST_ERROR`; an unknown query value: `400 INVALID_PARAMETER`.
- Checks when raising, in order: the body, the order's owner, one open complaint per order, the per-customer limit.

### 2.4 Endpoints

All behind the gateway (route `order-complaints` → order-service).

| Method & path                               | Roles                              | Body / query                                              | Answer                         |
|---------------------------------------------|------------------------------------|-----------------------------------------------------------|--------------------------------|
| `POST /api/complaints`                      | `CUSTOMER`                         | `{ "category", "orderId"?, "description" }`              | `201` `ComplaintView`          |
| `GET /api/complaints`                       | `CUSTOMER`                         | `status?`, `page?`, `size?` — the caller's, newest first   | `PageResponse<ComplaintView>`  |
| `GET /api/complaints/{id}`                  | `CUSTOMER`                         | the caller's own (else `404 COMPLAINT_NOT_FOUND`)          | `ComplaintView`                |
| `GET /api/admin/complaints`                 | any admin                          | `status?` (all when left out), `category?`, `q?` (exact complaint or order number, any case), `page?`, `size?` — newest first | `PageResponse<AdminComplaintView>` |
| `POST /api/admin/complaints/{id}/start`     | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT` | —                                                       | `AdminComplaintView`           |
| `POST /api/admin/complaints/{id}/resolve`   | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT` | `{ "resolution" }`                                      | `AdminComplaintView`           |

```json
// ComplaintView
{
  "id": "0193…",
  "complaintNumber": "CMP-7K3F9Q2M",
  "category": "DELIVERY",
  "status": "OPEN",
  "orderId": "0193…",          // null without an order
  "orderNumber": "GA-28AT309Q", // null without an order
  "storeName": "Velachery Green Grocers",
  "description": "The order came 2 hours late and the milk was warm.",
  "resolution": null,
  "createdAt": "2026-11-19T10:15:00Z",
  "updatedAt": "2026-11-19T10:15:00Z",
  "resolvedAt": null
}
// AdminComplaintView = ComplaintView + "customerId" and "handledBy" (the admin who made the latest change)
```

No events are published for complaints in this version (nothing else reacts to them); the customer sees the answer on
**Account → Help & support** and through the support assistant.

### 2.5 Pages

- Customer: **Help & support** (`/account/complaints`, `?orderId=` preselects an order) — raise a complaint and see
  every complaint with its status and answer.
- Admin: **Complaints** (`/admin/complaints`) — the queue with status tabs, category filter and search; support,
  operations and super admins start and resolve complaints.

---

## 3. The agents in detail

### 3.1 Where the code is

| What                                              | Where (ai-orchestrator-service unless noted)                        |
|---------------------------------------------------|---------------------------------------------------------------------|
| Agent definitions: prompt, tool allowlist, roles  | `agent/Agents.java`                                                 |
| Payment tools                                     | `tool/PaymentTools.java`                                            |
| Payment situations and guidance (pure logic)      | `tool/PaymentGuidance.java`                                         |
| Support tools                                     | `tool/SupportTools.java`                                            |
| Plain words for status codes                      | `tool/StatusWords.java`                                             |
| India time for customers ("19 Nov, 2:32 pm IST")  | `support/IndiaTime.java`                                            |
| The live context line per agent                   | `chat/ContextBuilder.java`                                          |
| Page links (buttons and links in answers)         | `link/Pages.java`; frontend `src/features/ai/pageLinks.ts`          |
| Deterministic stand-in model (tests, local)       | `model/ScriptedChatModel.java`                                      |
| Configuration                                     | `config/AiProperties.java`, `application.yml`                       |
| Assistant switcher in the chat panel              | frontend `src/features/ai/AgentSwitcher.tsx`                        |
| Complaints API                                    | order-service (section 2)                                           |

Every tool is a `ToolSpec`. ToolGuard checks the allowlist, the role and the arguments for every call, and audits it.
The tools call the business APIs through `BusinessApi` with the customer's token. Agents **share** tools where their
jobs overlap, so each tool has one implementation:
- `cancelOrder` and `requestReturn` come from the shopping assistant;
- `getPaymentStatus` from the payment assistant.

### 3.2 Payment assistant (`payment`)

| Tool               | Risk       | Calls                                                                       | Gives the model                                                                                   |
|--------------------|------------|-----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `listMyPayments`   | READ       | `GET /api/orders` (`status=AWAITING_PAYMENT`, or the newest 50 filtered)    | up to 10 orders: number, store, total, order and payment status (code and words), pay-by time, minutes left |
| `getPaymentStatus` | READ       | `GET /api/orders/{id}` (found by number) + `GET /api/payments/orders/{id}`  | the order, the payment (method, paid at, pay by, minutes left, last 3 attempts with failure reasons, refunds) and `situation`, `whatHappened`, `nextStep`, `canPayNow` |
| `cancelOrder`      | SENSITIVE  | shared with shopping: an approval the customer confirms                     | the approval card                                                                                 |

`listMyPayments` filters: `WAITING_FOR_PAYMENT`, `PAID`, `REFUNDS`, `EXPIRED` and `ALL` (the default).

**Situations** (`PaymentGuidance`, checked in this order):

| Situation                  | When                                                                    | The customer is told                                                    |
|----------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `REFUND_FAILED_RETRYING`   | a refund failed and none is pending                                     | not completed yet; retried automatically; support can raise a complaint |
| `REFUND_IN_PROGRESS`       | a refund is pending, or a paid order was cancelled, or a late payment   | that they paid; the amount, the reason, when it started; it goes back to the method used; bank timing (configured) |
| `REFUNDED`                 | refunded                                                                | that they paid; the amount ("in full" if so), when and why              |
| `PARTIALLY_REFUNDED`       | part refunded (e.g. a return)                                           | how much of the total, when and why                                     |
| `PAID`                     | paid                                                                    | the amount, when, the method, and the order's status                     |
| `NOTHING_TO_PAY`           | cancelled or rejected before payment                                    | nothing was charged                                                     |
| `PAYMENT_TIME_OVER`        | the payment window closed without payment                               | nothing was charged; the order is (being) cancelled; order again         |
| `CHECKING_STOCK`           | the order was just placed                                               | payment opens after the stock check, then tap **Pay now**                |
| `CONFIRMING_PAYMENT`       | the provider took the money and the order has not caught up yet         | don't pay again; it updates within a minute or two                       |
| `PAYMENT_FAILED_CAN_RETRY` | the last attempt failed and the window is open                          | the failure reason; try again before the deadline (minutes left)         |
| `WAITING_FOR_PAYMENT`      | the window is open and nothing has been paid                            | pay before the deadline on the order page (**Pay now**)                  |

A failed refund's provider text ("The id provided does not exist") never reaches the model. The app shows it to admins
only, so the assistant does the same.

A refund always starts with the payment itself, and a cancelled order's refund says why the order was cancelled:
"You paid ₹239.00 on 19 Nov, 8:43 pm IST by UPI. ₹239.00 was refunded in full on 19 Nov, 8:44 pm IST because the
order was cancelled (the store could not accept it)." Told only "refunded" and "cancelled", the small local model said
the payment "was not completed".

The chat never takes payments. Paying always happens on the order page. The prompt also forbids asking for card
numbers, CVV, UPI PIN, OTP or passwords. **Context line:** the latest order's payment status and pay-by time, its
`whatHappened` and `nextStep` in the same words `getPaymentStatus` gives (two extra reads, the order and its payment),
and how many orders wait for payment. The model often answers "did my last payment go through?" from this line without
a tool call, so the line carries the whole story.

### 3.3 Customer support assistant (`support`)

| Tool               | Risk            | Calls                                                                 | Gives the model                                                                  |
|--------------------|-----------------|-----------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `listMyOrders`     | READ            | shared with shopping                                                  | recent orders                                                                    |
| `getOrderStatus`   | READ            | shared with shopping                                                  | one order with `cancellable` / `returnable`                                     |
| `trackOrder`       | READ            | `GET /api/orders/{id}` + (when trackable) `GET /api/delivery/orders/{id}/tracking` | status in words, the partner's first name and vehicle, ETA minutes, last update; never the partner's phone, number plate or coordinates |
| `listMyReturns`    | READ            | `GET /api/returns` (optionally one order)                             | up to 10 returns: status and reason in words, items, refund, decision due / decided, the store's comment, refunded at |
| `fileComplaint`    | LOW_RISK_WRITE  | `POST /api/complaints` (the order number becomes its id)              | the new complaint (number, category, status) and what happens next               |
| `listMyComplaints` | READ            | `GET /api/complaints`                                                 | up to 10 complaints with status and the team's answer                            |
| `cancelOrder`      | SENSITIVE       | shared with shopping                                                  | an approval the customer confirms                                               |
| `requestReturn`    | SENSITIVE       | shared with shopping                                                  | an approval the customer confirms (the store then decides)                      |
| `getPaymentStatus` | READ            | shared with payment                                                   | as in 3.2                                                                       |

`fileComplaint` is a LOW_RISK_WRITE because it only records the customer's words. Nothing about the order, money or
stock changes. The prompt tells the assistant to repeat the category, order and description and to file only after the
customer says yes. For damaged, wrong or missing items it suggests a return first, because a return is what refunds
items. **Context line:**
- the latest order, with why and when it was cancelled if it was (one extra read, only then);
- how many return requests there are, and the newest one's status;
- the open complaints (number, order, status);
- how many complaints are resolved, with the newest three answers, shortened to 120 characters.

It ends by naming what it does **not** hold: where an order is now, why another order was cancelled, refunds, return
details and older complaints. The prompt says to look those up with a tool every time.

This came from testing with the real local model (qwen3:4b-instruct). A 4B model often answers from the context line
instead of calling a tool. When the line only said "latest order …: CANCELLED" or "no open complaints", it made up a
cancellation reason ("the items were out of stock") and even complaints with replies. With the real facts in the line
it repeats them instead. That is the same approach the shopping, vendor and delivery assistants use (see the comment
in `ContextBuilder`).

### 3.4 Security and privacy

- Customers only: any other role gets `403 AGENT_NOT_ALLOWED` (and an audit row).
- Every call uses the customer's own JWT through the gateway, so the services return only the customer's data and
  check permissions again. Another customer's order is `ORDER_NOT_FOUND`.
- No provider ids (Razorpay order or payment ids), no partner phone numbers, number plates or coordinates, and no
  admin-only fields reach the model.
- Cancellations and returns stay approval requests. Complaints are rate-limited by the chat limits
  (`AI_MESSAGES_PER_HOUR`, `max-tool-calls`) and by order-service's own rules (one open complaint per order, at most
  `max-open` open complaints).
- Every tool call is audited (`ai_tool_invocation`) with the agent `payment` or `support`.

### 3.5 Configuration

| Property                                    | Env                         | Default            | Meaning                                                    |
|---------------------------------------------|-----------------------------|--------------------|------------------------------------------------------------|
| `grocery.ai.customer-care.refund-timeline`  | `AI_REFUND_TIMELINE`        | `5–7 working days` | what the payment assistant says about bank refund timing; empty = says nothing |
| `grocery.order.complaints.max-open`         | `ORDER_COMPLAINTS_MAX_OPEN` | `5`                | open complaints per customer                               |

The gateway routes `/api/complaints/**` and `/api/admin/complaints/**` to order-service (route `order-complaints`).
Everything else (model, timeouts, limits) is shared with the other assistants.

### 3.6 Tests

| Test                                                    | Covers                                                                    |
|---------------------------------------------------------|---------------------------------------------------------------------------|
| `PaymentGuidanceTest` (unit)                            | every payment situation, wording, deadlines and rounding                   |
| `PaymentAssistantIntegrationTest`                       | failed payment with reason and minutes left; paid; refund pending; orders to pay; refunds list; no payment yet; payment-service down; unknown order; tools of other agents refused; cancellation proposed; non-customers refused |
| `SupportAssistantIntegrationTest`                       | tracking without private details; "where is my order"; complaint raised, in context, answer read back; invalid and refused complaints; returns; shared order and payment tools; cart tools refused |
| `ScriptedChatModelTest`                                 | the stand-in model's rules for both agents                                |
| `InsightsAndMonitoringIntegrationTest`                  | a customer is offered `shopping`, `payment` and `support`                 |
| order-service `ComplaintsIntegrationTest`               | the complaints API and its rules (section 2)                              |
| frontend `AssistantPanel.test.tsx`                      | the switcher: three assistants, switching, waiting while an answer streams |
| frontend complaint page tests                           | Help & support and the admin Complaints page                              |

**Try it locally.** To start the real model, run `docker compose --profile ai up -d ollama ollama-init` in
`infrastructure/local`. The `ollama-init` step pulls qwen3:4b-instruct (2.5 GB) once. Name the two services: plain
`--profile ai` also starts the containerised ai-orchestrator-service, which clashes with the one running on the host.
Then restart ai-orchestrator-service: `run-service.cmd` switches to Ollama by itself when the model is there. A long
test session can hit the chat limit of 30 messages an hour (`AI_MESSAGES_PER_HOUR`).

With the real model, ask in your own words, for example:
- "did my payment for GA-… go through?"
- "why did my payment fail?"
- "where is my refund?"
- "where is my order?"
- "my milk was warm and the order came late"

With `AI_PROVIDER=scripted`, the stand-in model understands fixed phrases:
- payment: "payment of GA-…", "did my last payment go through", "which orders do I have to pay", "my refunds"
- support: "track GA-…", "where is my order", "my returns", "complaint delivery about GA-…: what happened",
  "my complaints"

### 3.7 Adding a tool

1. Add a `ToolSpec` to `PaymentTools` or `SupportTools`. Choose the right risk class: READ, LOW_RISK_WRITE, or a
   SENSITIVE proposal with an approval action.
2. Add it to the agent's allowlist in `Agents.java` and tell the prompt when to use it.
3. Use `StatusWords` for plain words. Never put private data or provider ids in the result.
4. Add a `ScriptedChatModel` rule and an integration test with the fake gateway.
