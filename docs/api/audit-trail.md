# Audit trail: one search across the platform's audit logs

*Added 2026-11-21. Service: reporting-service (port 8116, `reporting_db`). Through the gateway:
`/api/admin/audit` (route `reporting`).*

Every service already recorded what people did (identity's sign-in audit, each service's settings history, the
events that name an admin's decision), but each in its own place. The audit trail brings them into **one list**:
who did what, to what, and when, searchable and exportable.

## How it is built

- Only from **events**, like the reports: consumer group `reporting-service.audit` reads ten topics
  (`identity`, `vendor`, `restaurant`, `delivery`, `pricing`, `inventory`, `order`, `payment`, `review`, `ai`
  `.events`). One row per event (`audit_entry`; the id is the event id, so a redelivery adds nothing).
- Two events were added for it (grocery-event-schemas 1.8.0):
  - `SecurityEventRecorded` (identity-service, `identity.events`, key `recordId`): one per row of `auth_audit`, with the
    client's IP and User-Agent and, where an admin acted on the account, `actorUserId`.
  - `PlatformSettingChanged` (delivery-, order-, pricing-, restaurant-, review- and vendor-service, on their own topic,
    key `settingKey`): one per changed field of an admin's save, old and new stored value (null = the default).
- The other entries come from events that already named the actor: store, restaurant and partner decisions, price
  change requests and decisions, stock adjustments, order cancellations, store rejections, return decisions,
  delivery reassignments, refunds, review moderation and the AI assistants' approvals. Full list:
  [reporting-service README, "Audit trail"](../../services/reporting-service/README.md#audit-trail).
- **Retention:** sign-in events 180 days (`AUDIT_SIGN_IN_RETENTION`), everything else 730 days (`AUDIT_RETENTION`),
  purged daily at 03:40 India time. The trail starts with what Kafka still held when it was deployed (7 days locally).

## `GET /api/admin/audit`

**Roles:** SUPER_ADMIN, OPS_ADMIN (the gateway lets every admin role through `/api/admin/**`; reporting-service
refuses the others with 403). The trail holds sign-in addresses and e-mails.

| Parameter | Meaning |
|---|---|
| `q` | Words found anywhere in the summary, details, target (order number, business name, e-mail, setting), action, the actor's name or e-mail, or the IP address (case-insensitive; at most 100 characters) |
| `category` | `SECURITY` (sign-in), `ACCOUNTS`, `STORES`, `PARTNERS`, `PRICES`, `STOCK`, `ORDERS`, `DELIVERIES`, `PAYMENTS`, `REVIEWS`, `AI`, `SETTINGS`; repeat the parameter or separate with commas; none = all |
| `action` | One action code, e.g. `LOGIN_FAILURE`, `VENDOR_APPROVED`, `SETTING_CHANGED` |
| `actorId` | Only what this user did |
| `targetType`, `targetId` | Only what was done to this target (`USER`, `VENDOR`, `RESTAURANT`, `PARTNER`, `ORDER`, `PRODUCT`, `REVIEW`, `APPROVAL`, `SETTING`) |
| `from`, `to` | India dates, both inclusive; default the last 30 days; at most 366 days |
| `page`, `size` | Page (from 0) and size (1–100, default 50) |
| `format` | none or `json`: one page; `csv`, `xlsx`, `pdf`: the whole search as a file (grocery-common's exporter: `X-Export-Rows`, a PDF holds at most 5,000 rows) |

Newest first (ties: the newest event first). A bad parameter is 400 `INVALID_PARAMETER`.

```jsonc
// PageResponse<AuditEntry>
{ "content": [ {
    "id": "…",                                   // the event id
    "occurredAt": "2026-11-21T04:30:12.123456Z",
    "category": "ORDERS", "action": "ORDER_CANCELLED", "source": "order-service",
    "actor": { "id": "…", "role": "ADMIN",       // ADMIN, STORE, PARTNER, CUSTOMER, USER, SYSTEM, ANONYMOUS
               "name": "Meera Ops", "email": "meera.ops@grocery.local" },   // as far as the events told
    "target": { "type": "ORDER", "id": "…", "label": "GA-7K3F9Q2M" },       // null when there is none
    "summary": "Order cancelled by admin",
    "detail": "admin cancelled · customer called support",
    "ipAddress": null, "userAgent": null          // sign-in and account events only
  } ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1 }
```

**Who someone is:** events carry user ids. The trail learns an account's e-mail from its sign-in events and an
admin's name from their settings changes, and shows them with every action of that person.

## Admin page

`/admin/audit` ("Audit log", Operations section; permission `audit.view` = SUPER_ADMIN, OPS_ADMIN): search,
category, person and dates in the URL; a row's person or target narrows the list to them; failed sign-ins and
reused session tokens are highlighted; the export button downloads the whole search.

## Not in the trail (yet)

Coupon management, complaint handling and catalog edits publish no event that names the actor. The assistants'
individual tool calls stay on the AI audit page (`/admin/ai/audit`); their approvals are in the trail.
