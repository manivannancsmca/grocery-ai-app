# Knowledge base (RAG) — API reference

All paths go through the gateway (`/api/...`). Errors are `application/problem+json` with a stable `errorCode`, like
every other API of the platform. Dates are ISO-8601 UTC.

## 1. Admin: documents (`/api/admin/ai/knowledge`)

The gateway lets only admins reach `/api/admin/**`. **Any admin** may read and test the search; **SUPER_ADMIN and
OPS_ADMIN** may upload, replace, reindex and delete. With the knowledge base switched off every endpoint except
`/status` answers **503 `KNOWLEDGE_BASE_DISABLED`**.

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/documents?status&category&page&size` | any admin | Documents, newest change first (deleted ones only with `status=DELETED`) |
| GET | `/documents/{id}` | any admin | One document |
| POST | `/documents` (multipart) | SUPER_ADMIN, OPS_ADMIN | Upload → **202** (indexing in the background) or **200** (the same file is already there) |
| PUT | `/documents/{id}` (multipart) | SUPER_ADMIN, OPS_ADMIN | A new version and/or new details → **202**, or **200** when nothing changed |
| POST | `/documents/{id}/reindex` | SUPER_ADMIN, OPS_ADMIN | Index again from the stored text → **202** |
| DELETE | `/documents/{id}` | SUPER_ADMIN, OPS_ADMIN | **204**; unsearchable at once |
| POST | `/search` | any admin | Test the retrieval as a reader would get it |
| GET | `/status` | any admin | On/off, Elasticsearch reachable, model, documents per status |

### The document

```json
{
  "id": "0197d0a2-6f4e-7c1b-9a3e-5b2d8f1c4e70",
  "title": "Cancellations and refunds",
  "category": "POLICIES",
  "audiences": ["CUSTOMER", "STAFF"],
  "fileName": "04-cancellations-and-refunds.md",
  "format": "MARKDOWN",
  "sizeBytes": 1789,
  "contentVersion": 1,
  "status": "COMPLETED",
  "searchable": true,
  "chunkCount": 5,
  "embeddingModel": "nomic-embed-text",
  "failureReason": null,
  "createdBy": { "id": "6f1c…", "name": "Dev Ops" },
  "updatedBy": { "id": "6f1c…", "name": "Dev Ops" },
  "createdAt": "2026-11-25T09:00:00Z",
  "updatedAt": "2026-11-25T09:00:41Z",
  "processedAt": "2026-11-25T09:00:41Z"
}
```

- `status`: `UPLOADED` (waiting), `PROCESSING` (indexing), `COMPLETED`, `FAILED`, `DELETED`.
- `searchable`: the assistants can use it now. While a new version is `UPLOADED`, `PROCESSING` or `FAILED`, the previous
  version is still used and `searchable` stays `true`.
- `failureReason`: why indexing failed (`FAILED`), or why it is being tried again (`UPLOADED`). Never a stack trace.
- `category`: `POLICIES`, `APP_GUIDE`, `PRODUCT_GUIDE`, `VENDOR`, `DELIVERY`, `SUPPORT`.
- `audiences`: `CUSTOMER`, `VENDOR`, `RESTAURANT_OWNER`, `DELIVERY_PARTNER`, `STAFF` (every admin role).

### Upload

```bash
curl -X POST http://localhost:8080/api/admin/ai/knowledge/documents \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F file=@refund-policy.pdf \
  -F title="Refund policy" \
  -F category=POLICIES \
  -F audiences=CUSTOMER -F audiences=STAFF
```

| Part | Required | Notes |
|---|---|---|
| `file` | yes | `.pdf`, `.docx`, `.txt`, `.md`, `.markdown`, `.html`, `.htm`; ≤ 5 MB |
| `title` | no | 1–200 characters; default: the file name ("refund-policy.pdf" → "refund policy") |
| `category` | yes | see above |
| `audiences` | yes | repeated, or comma-separated (`CUSTOMER,STAFF`) |

`PUT /documents/{id}` takes the same parts; `title`, `category` and `audiences` are kept when left out.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `errors: [{field: "category" \| "audiences" \| "title" \| "question" \| "topK" \| "minSimilarity", message}]` |
| 400 | `INVALID_PARAMETER` | an unknown category, audience or status |
| 400 | `INVALID_DOCUMENT` | the content is not what the extension says, damaged, password-protected, has macros |
| 400 | `EMPTY_DOCUMENT` | no text (e.g. a scanned PDF) |
| 400 | `DOCUMENT_TOO_LONG` | more than 300,000 characters of text |
| 404 | `DOCUMENT_NOT_FOUND` | unknown or deleted (for changes) |
| 409 | `DOCUMENT_CHANGED` | changed at the same moment by someone else; reload and try again |
| 413 | `DOCUMENT_TOO_LARGE` | above 5 MB (`maxBytes`) |
| 415 | `UNSUPPORTED_DOCUMENT_TYPE` | another extension |
| 403 | `FORBIDDEN` | not an admin, or a change by another admin role |
| 503 | `KNOWLEDGE_BASE_DISABLED` | `AI_KNOWLEDGE_ENABLED=false` |
| 503 | `KNOWLEDGE_BASE_UNAVAILABLE` | the test search could not reach Elasticsearch or the embedding model |

### Test search

```bash
curl -X POST http://localhost:8080/api/admin/ai/knowledge/search \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"Can I cancel a paid order?","audience":"CUSTOMER","topK":3}'
```

```json
{
  "status": "FOUND",
  "results": [
    {
      "documentId": "0197d0a2-…",
      "title": "Cancellations and refunds",
      "category": "POLICIES",
      "text": "## Can I cancel my order?\nYou can cancel an order yourself until the store accepts it…",
      "similarity": 0.74
    }
  ]
}
```

`audience` (default: every audience), `category`, `topK` (1–20) and `minSimilarity` (0–1) are optional and default to
the configuration. `status` is `FOUND` or `NO_MATCH` (what the assistant then says it could not find).

### Status

```json
{ "enabled": true, "elasticsearchReachable": true, "embeddingModel": "nomic-embed-text",
  "index": "grocery-knowledge", "documents": { "COMPLETED": 11, "FAILED": 0 } }
```

## 2. Chat (existing endpoint, one new event)

`POST /api/ai/agents/{agent}/chat` streams Server-Sent Events as before (`conversation`, `tool`, `approval`, `token`,
`retry`, `notice`, `done`, `error`). New, sent just before `done` when the answer used help-center articles:

```
event: sources
data: {"sources":[{"documentId":"0197d0a2-…","title":"Cancellations and refunds","category":"POLICIES"}]}
```

Clients that do not know the event ignore it. `GET /api/ai/conversations/{id}` returns the same list per assistant
message as `messages[].sources` (empty for none), so the sources show again when a conversation is reopened.

New tools the assistants may call (audited in `/api/admin/ai/tool-invocations` like every tool):

| Tool | Risk | Who | What |
|---|---|---|---|
| `searchHelpCenter` | READ | every role | The help-center passages for a question, only of documents for the caller's audiences |
| `getStoreRules` | READ | every role | The minimum order, delivery fees and free-delivery amount in force |

## 3. Delivery fees for the assistants (pricing-service)

`GET /api/pricing/delivery-fees` — any signed-in user; the same answer as cart-service's internal
`/internal/delivery-fees` (the platform settings in force, [platform-settings.md](../api/platform-settings.md)):

```json
{ "groceryBaseFee": 25.00, "groceryIncludedKm": 3, "groceryPerExtraKm": 8.00, "groceryFreeAbove": 499.00,
  "groceryMinimumOrder": 99.00, "restaurantBaseFee": 30.00, "restaurantIncludedKm": 3, "restaurantPerExtraKm": 10.00 }
```
