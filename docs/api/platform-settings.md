# Platform settings — API contract

Admins change a few business rules in the app (**Admin → Platform settings**, `/admin/settings`) instead of in server
config: how far and how long the partner search runs, how long stores have to accept, the return and review windows,
and the delivery fees. Conventions as in [Phase 2](phase-2-identity-and-stakeholders.md#1-conventions); paths go
through the API gateway.

**Nothing changes until an admin saves.** The values in `application.yml` / env stay the defaults; a saved value only
overrides them.

## 1. How it works

- **Each service owns its settings** ([ADR-0001](../adr/0001-database-per-service.md)): the service that uses a rule
  stores it in its own database. There is no central settings service and no event: the admin page asks each owner.
- **Storage** (one Flyway migration per owning service):
  - `platform_setting`: `setting_key` (`<area>.<field>`, e.g. `delivery.offersPerWave`), `setting_value` (text),
    `updated_by`, `updated_by_name`, `updated_at`, `version`.
  - A row with a value is an **override**. No row, or a `NULL` value (the admin reset it), means the default.
  - `platform_setting_change`: the audit trail, one row per changed key (`old_value`, `new_value`, `changed_by`,
    `changed_by_name`, `changed_at`; `NULL` = the default).
- **Reads.** Business code asks for the value in force each time it uses one. Each instance keeps the table in memory
  and reads it again every **15 seconds** (`grocery.settings.cache-ttl`, env `PLATFORM_SETTINGS_CACHE_TTL`). The
  instance that saved uses the new value at once; **other replicas within 15 s**; delivery fees within **30 s** (pricing's
  15 s plus cart-service's own 15 s, see §4). If the database cannot be read, an instance keeps the values it read last.
- **New work uses the new value; existing records keep what they got.** Details per setting in §3.

## 2. The API

Every area has the same three endpoints in its owning service.

```
GET /api/admin/settings/{area}          any admin role
PUT /api/admin/settings/{area}          SUPER_ADMIN or OPS_ADMIN
GET /api/admin/settings/{area}/history  any admin role (§5)
```

**`GET`** →

```json
{
  "area": "returns",
  "values":   { "returnWindowHours": 72, "storeDecisionHours": 24 },
  "defaults": { "returnWindowHours": 48, "storeDecisionHours": 24 },
  "overridden": ["returnWindowHours"],
  "updatedAt": "2026-11-17T05:12:40.123456Z",
  "updatedBy": "Dev Ops",
  "version": 3
}
```

- `values`: the values in force (the override, else the default). `defaults`: the values from config.
- `overridden`: the fields with an admin override.
- `updatedAt` / `updatedBy`: the last change of any field of the area (name, or email when the token has no name);
  both `null` when never changed.
- `version`: `0` until the first change; it goes up by one for every changed field.
- Numbers, never duration strings. A default that is not a whole number of the unit is shown with two decimals (e.g.
  `acceptanceTimeMinutes: 0.5` when a smoke test sets `ACCEPTANCE_TIMEOUT=30s`); it stays exact as long as nobody
  saves the field.

**`PUT`** with the whole area:

```json
{ "values": { "returnWindowHours": 72, "storeDecisionHours": null }, "expectedVersion": 3 }
```

- Every field of the area. **`null` (or a missing field) means "use the default"**: it resets an override. The page
  sends `null` for fields that are not overridden, so they keep following the config.
- **All or nothing**: the area is saved in one transaction, with one audit row per field whose value changed. Saving the
  same values again changes nothing (no new version, no audit row). Money is compared with two decimals (`40` = `40.00`).
- `200` with the new view (as `GET`).

**Errors** (problem+json, as everywhere):

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | a value out of bounds, too many decimals, not ascending (radii), `values` or `expectedVersion` missing. `errors: [{ "field": "values.offersPerWave", "message": "must be between 1 and 10" }]` (list items: `values.searchRadiiKm[0]`) |
| 400 | `REQUEST_ERROR` | a body that is not JSON or a text where a number belongs |
| 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` | no token; not an admin (`GET`), not SUPER_ADMIN or OPS_ADMIN (`PUT`). The gateway already refuses non-admins for `/api/admin/**` |
| 409 | `SETTINGS_CHANGED` | `expectedVersion` is not the area's version any more: someone else saved first. Reload and try again |

Concurrency: the area's version is checked, and each changed field is written with a version check of its own, in one
transaction. A save made from a page loaded before another admin's save gets 409; two saves at exactly the same moment
can never both change the same field (the second gets 409 and nothing of it is saved).

## 3. Areas

| Area (owner) | Field | Unit | Default (config, env) | Bounds | When a change applies |
|---|---|---|---|---|---|
| `delivery` (delivery-service) | `searchRadiiKm` | km, list | `3, 6, 10` (`grocery.delivery.search-radii-km`, `DELIVERY_SEARCH_RADII_KM`) | 1–5 steps, each 0.5–50 (one decimal), strictly ascending | the next wave of every search; a search already past the last step of a shorter list continues with the largest radius |
| | `offersPerWave` | partners | `2` (`offer-batch-size`, `DELIVERY_OFFER_BATCH_SIZE`) | 1–10 | the next wave |
| | `offerTimeLimitSeconds` | s | `30` (`offer-ttl`, `DELIVERY_OFFER_TTL`) | 10–300 | offers sent from then on; open offers keep their expiry |
| | `locationFreshnessSeconds` | s | `60` (`location-stale-after`, `DELIVERY_LOCATION_STALE_AFTER`) | 30–600 | the next wave. At least 30 s: the partner app re-sends a still position every 25 s |
| `grocery-stores` (vendor-service) | `acceptanceTimeMinutes` | min | `5` (`grocery.fulfillment.acceptance-timeout`, `ACCEPTANCE_TIMEOUT`) | 1–60 | orders reaching the board from then on; waiting orders keep their `acceptBy` |
| `restaurants` (restaurant-service) | `acceptanceTimeMinutes` | min | `5` (same property and env in restaurant-service) | 1–60 | as for grocery stores |
| `returns` (order-service) | `returnWindowHours` | h | `48` (`grocery.order.returns.window`, `ORDER_RETURN_WINDOW`) | 1–720 | checked when the customer asks (and for the order's `returnable`), counted from delivery: **every delivered order at once**, earlier ones included |
| | `storeDecisionHours` | h | `24` (`returns.decision-timeout`, `RETURN_DECISION_TIMEOUT`) | 1–168 | returns requested from then on; open returns keep their `decideBy` |
| `reviews` (review-service) | `reviewWindowDays` | days | `30` (`grocery.review.review-window`, `REVIEW_WINDOW`) | 1–365 | checked when the customer asks, counted from delivery: **every delivered order at once** |
| `delivery-fees` (pricing-service) | `groceryBaseFee` | ₹ | `25.00` (`grocery.pricing.delivery-fees.grocery-base-fee`) | 0–500 | the next checkout preview, so new orders; placed orders keep the fee they paid |
| | `groceryIncludedKm` | km | `3` | 0–20 (one decimal) | as above |
| | `groceryPerExtraKm` | ₹ per started km | `8.00` | 0–100 | as above |
| | `groceryFreeAbove` | ₹ subtotal | `499.00` | 0–10,000 | as above |
| | `groceryMinimumOrder` | ₹ subtotal | `99.00` | 0–5,000 | as above (`BELOW_MINIMUM_ORDER`) |
| | `restaurantBaseFee` | ₹ | `30.00` | 0–500 | as above |
| | `restaurantIncludedKm` | km | `3` | 0–20 (one decimal) | as above |
| | `restaurantPerExtraKm` | ₹ per started km | `10.00` | 0–100 | as above |

Money has at most two decimals. Restaurants have no free delivery and no minimum order (as before); those two are not
settings. Everything else in config stays config: poll intervals, retry interval, road factor, maximum delivery
distance, edit window of reviews, payment window, and so on.

## 4. Delivery fees: pricing-service → cart-service

cart-service has no database (its carts live in Redis) and order-service takes the totals from the cart's checkout
preview, so the fee **settings live in pricing-service**, which owns the money rules.

- `GET /internal/delivery-fees` (pricing-service, **internal**: `/internal/**` is never routed by the gateway) answers
  the eight fees in force: `{ "groceryBaseFee": 25.00, "groceryIncludedKm": 3, …, "restaurantPerExtraKm": 10.00 }`. Any
  authenticated caller; cart-service forwards the customer's token, like its other calls. Covered by the Pact
  `cart-service-pricing-service.json` ("the delivery fees are the configured ones").
- cart-service (`DeliveryFeeRules`) keeps the answer for 15 s (`grocery.cart.delivery.settings-cache-ttl`, env
  `DELIVERY_FEES_CACHE_TTL`) and asks again on the next preview after that. It has its own circuit breaker
  (`pricing-fees`), so a failing fee lookup never opens the price quote's breaker.
- **Checkout never breaks because of this.** When pricing-service cannot be reached (or is an older version without the
  endpoint), cart-service logs a warning and keeps the last fees it got; before its first answer it uses its own
  `grocery.cart.delivery.*`. It tries again after another 15 s. Keep `grocery.cart.delivery.*` equal to
  `grocery.pricing.delivery-fees.*`.
- **Road factor and maximum distance stay in config** (`grocery.cart.delivery.road-factor`,
  `grocery.cart.delivery.<type>.max-distance-km`): they also decide which stores are "near you" (vendor-service's
  `grocery.stores.*` uses the same numbers), so changing them needs both services' config.

## 5. Audit trail and history

Every saved change is a row of the owning service's `platform_setting_change` (§1), and the admin page reads it:

```
GET /api/admin/settings/{area}/history?page=0&size=20     any admin role (same rule as GET of the area)
```

→ the usual `PageResponse` (`page` from 0, `size` 1–100, default 20), **newest first**:

```json
{
  "content": [
    { "field": "returnWindowHours", "oldValue": null, "newValue": 72, "changedAt": "2026-11-17T05:12:40.123456Z",
      "changedBy": { "id": "6f1c…", "name": "Dev Ops" } },
    { "field": "storeDecisionHours", "oldValue": 12, "newValue": null, "changedAt": "2026-11-16T09:30:00Z",
      "changedBy": { "id": "a2b9…", "name": "Super Admin" } }
  ],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1
}
```

- One entry per changed field: a save that changed two fields gives two entries with the same `changedAt` and
  `changedBy`. Within one save the entries come in the reverse order of writing; the order is stable across pages.
- `field` is the field's name in the API (`returnWindowHours`, not the stored key `returns.returnWindowHours`).
- `oldValue` / `newValue` are in the units and types of the area's `values` (hours, km lists, rupees with two
  decimals: `"oldValue": 40.00`); **`null` means the default** (no override before, or reset to the default).
- `changedBy.name` is the admin's name when they saved (or email when the token had none).
- A value edited by hand in the database that the service cannot read is shown as the stored text.
- 401 / 403 as for `GET` of the area. The history is read from the database on every call (never cached).

In the database, newest first:

```sql
SELECT setting_key, old_value, new_value, changed_by_name, BIN_TO_UUID(changed_by) AS changed_by, changed_at
FROM platform_setting_change ORDER BY changed_at DESC, id DESC;
```

## 6. The admin page

`/admin/settings` (admin navigation, **Platform settings**): one card per area. Each field shows its unit, the default
("Default: 48 h"), a "Changed" marker for an override, a reset-to-default button and validation messages; each card
shows its last change and saves on its own. Permissions `settings.view` (every admin role) and `settings.manage`
(SUPER_ADMIN, OPS_ADMIN); without `settings.manage` the page is read-only. A 409 shows "Someone else changed these
settings — reload". A card whose service is down shows an error; the other cards still work.

Each card's **History** button (every admin) opens a drawer (full screen on a phone) with the area's §5 history, 20
entries a page: one block per save with who and when, then each changed field with its label and old → new value
with the unit ("2 partners → 3 partners", "₹25 → ₹40.50"), **Default** for `null`. It has its own loading, empty
("No changes yet") and error states; a save refreshes it.

Gateway routes (`services/api-gateway`, with the usual API rate limit): `/api/admin/settings/delivery` →
delivery-service, `grocery-stores` → vendor-service, `restaurants` → restaurant-service, `returns` → order-service,
`reviews` → review-service, `delivery-fees` → pricing-service; each route matches the area path and its `/history`
(nothing else below it).
