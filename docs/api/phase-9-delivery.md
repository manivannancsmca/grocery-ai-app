# Phase 9 API contract — Delivery

Single source of truth for delivery (delivery-service) and the changes it causes in cart-, order-, inventory-, vendor-,
restaurant- and notification-service and the React app. Public paths go through the API gateway
(`http://localhost:8080`). Conventions unchanged ([Phase 2](phase-2-identity-and-stakeholders.md#1-conventions));
conflict errors carry the current status as `currentStatus` (never a second `status`). Events are Avro (ADR-0011),
schemas **already written and registered**: `libs/grocery-event-schemas` **1.2.0**.

## 1. Who owns what

| Service | Owns in Phase 9 |
|---|---|
| **delivery-service** (8112) | deliveries, offers and assignment (nearest available partner), partner availability and live location (Redis GEO), pickup / deliver / fail, customer tracking, admin delivery monitor and reassignment |
| order-service | OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED; snapshots the store's pickup location |
| cart-service | the checkout preview's `store` gains `city`, `latitude`, `longitude` (additive) |
| inventory-service | commits the held stock at pickup (`StockCommitted`) |
| vendor- / restaurant-service | the store order becomes `PICKED_UP` (leaves the board) |
| notification-service | offers and delivery updates for partners, customers, stores and delivery managers; live location to the customer |

### The flow
```
OrderConfirmed ─► delivery: delivery PENDING (pickup = storeLocation, drop = deliveryAddress)
OrderAccepted  ─► delivery: SEARCHING → offers to the nearest available partners (DeliveryOfferCreated)
partner accepts (first one wins) ─► ASSIGNED + DeliveryPartnerAssigned ─► order records the partner; customer told
OrderReadyForPickup ─► delivery: readyForPickup = true (partner told)
partner "picked up" ─► PICKED_UP + DeliveryPickedUp ─► order OUT_FOR_DELIVERY + OrderOutForDelivery
                                                         ├► inventory commits the stock (StockCommitted)
                                                         └► store order PICKED_UP
partner location pings ─► Redis GEO + delivery.location (DeliveryLocationUpdated) ─► notification ─► customer's map
partner "delivered" ─► DELIVERED + DeliveryCompleted ─► order DELIVERED + OrderDelivered
partner "couldn't deliver" ─► FAILED + DeliveryFailed ─► order DELIVERY_FAILED + OrderDeliveryFailed (admins decide a refund)
OrderCancelled (before pickup) ─► delivery CANCELLED, offers withdrawn, partner released (DeliveryCancelled)
```

## 2. Assignment rules (delivery-service)

- **Delivery statuses:** `PENDING` (paid, store has not accepted) → `SEARCHING` → `ASSIGNED` → `PICKED_UP` → `DELIVERED`;
  terminal `FAILED`, `CANCELLED`. A SEARCHING delivery that found nobody gets `assignmentFailed = true` (still SEARCHING).
- **Available partner:** APPROVED, online, no active delivery, no other pending offer, and a location report newer
  than `grocery.delivery.location-stale-after` (60 s).
- **A wave:** the `grocery.delivery.offer-batch-size` (**2**) nearest available partners within the current radius of
  the pickup point (Redis `GEOSEARCH … ASC`), excluding partners already offered this delivery, each get an offer valid
  for `grocery.delivery.offer-ttl` (**30 s**). The first to accept wins; the others' offers are WITHDRAWN.
- **Next wave** when every offer of the wave was rejected or expired: the next nearest partners; when nobody is left
  inside the radius, the next radius of `grocery.delivery.search-radii-km` (**3, 6, 10**). Nobody inside the largest
  radius → `assignmentFailed = true` + `DeliveryAssignmentFailed` (once) and a fresh search from the smallest radius
  every `grocery.delivery.retry-interval` (**60 s**), including partners offered before.
- **Admin platform settings:** the radii, the batch size, the offer time and the location freshness above are
  defaults that admins can override in the app (area `delivery`, [platform-settings.md](platform-settings.md)); the
  next wave uses the values in force.
- **Pollers** (every `grocery.delivery.poll-interval` = 5 s, `SELECT … FOR UPDATE SKIP LOCKED`, safe with replicas):
  expire offers, start next waves, retry failed searches.
- **Accept** locks the delivery row: exactly one accept wins; every other accept of that delivery gets 409
  `OFFER_NOT_AVAILABLE` (`currentStatus` = the offer's status).
- Settings by env: `DELIVERY_OFFER_TTL`, `DELIVERY_OFFER_BATCH_SIZE`, `DELIVERY_SEARCH_RADII_KM`,
  `DELIVERY_POLL_INTERVAL`, `DELIVERY_RETRY_INTERVAL`, `DELIVERY_LOCATION_STALE_AFTER`.
- Distances are straight-line (haversine) in km; ETA = distance × 1.3 / 20 km/h, rounded up to whole minutes.

## 3. Partner API (role `DELIVERY_PARTNER`, the partner's id = their user id)

A partner who is not APPROVED → 403 `PARTNER_NOT_ACTIVE` on everything below.

| Method & path | Body → Response |
|---|---|
| `GET /api/delivery/partners/me/status` | → `PartnerStatusView` |
| `PUT /api/delivery/partners/me/availability` | `{ "online": true }` → `PartnerStatusView`. Going offline with an active delivery → 409 `ACTIVE_DELIVERY` |
| `PUT /api/delivery/partners/me/location` | `{ "latitude", "longitude", "accuracyMeters"?, "heading"?, "speedKmh"? }` → 204. Offline → 409 `PARTNER_OFFLINE`. Reports closer than 2 s apart are accepted but not stored or published |
| `GET /api/delivery/offers` | → `OfferView[]` (my PENDING offers) |
| `POST /api/delivery/offers/{offerId}/accept` | → `DeliveryView` (ASSIGNED). Not mine → 404 `OFFER_NOT_FOUND`; not PENDING / expired / delivery taken → 409 `OFFER_NOT_AVAILABLE` |
| `POST /api/delivery/offers/{offerId}/reject` | → 204 (idempotent) |
| `GET /api/delivery/deliveries/active` | → `DeliveryView` or 204 |
| `GET /api/delivery/deliveries/{id}` | → `DeliveryView` (only the assigned partner; others 404 `DELIVERY_NOT_FOUND`) |
| `POST /api/delivery/deliveries/{id}/pickup` | → PICKED_UP. The store has not marked it ready → 409 `ORDER_NOT_READY`; wrong status → 409 `INVALID_DELIVERY_TRANSITION` |
| `POST /api/delivery/deliveries/{id}/deliver` | → DELIVERED (from PICKED_UP) |
| `POST /api/delivery/deliveries/{id}/fail` | `{ "reason": "CUSTOMER_UNREACHABLE" \| "ADDRESS_NOT_FOUND" \| "CUSTOMER_REFUSED" \| "OTHER", "note" }` (note ≤ 300, required for OTHER) → FAILED (from PICKED_UP) |
| `GET /api/delivery/history?status=&from=&to=&page=&size=` | → `PageResponse<DeliverySummary>` (`deliveryId`, `orderNumber`, `status` DELIVERED/FAILED, `store` name, `dropArea`, `tripKm`, `pickedUpAt`, `finishedAt`), newest first. Optional filters: `status` = `DELIVERED` \| `FAILED` (absent = both); `from` / `to` = `YYYY-MM-DD`, India-time days of `finishedAt`, both included, either may be left out. Unknown status (also a non-final one such as `ASSIGNED`) or malformed date → 400 `INVALID_PARAMETER`; `from` after `to` → 400 `VALIDATION_FAILED` (`errors[0].field` = `from`) |
| `GET /api/delivery/history/export?format=&status=&from=&to=` | the history as a file (csv \| xlsx \| pdf), every page, with the same filters and errors ([data exports](data-exports.md)) |
| `GET /api/delivery/history/summary` | → `{ "deliveredToday", "deliveredThisWeek", "failedThisWeek" }` (India-time days; week = last 7 days; the history's filters do not apply) |

Repeating pickup / deliver / fail in the state it leads to → 200 unchanged (idempotent).

```jsonc
// PartnerStatusView
{ "partnerId": "…", "online": true, "location": { "latitude": 12.97, "longitude": 77.59, "at": "…" } | null,
  "activeDeliveryId": "…" | null, "pendingOffers": 1 }

// OfferView — before acceptance the partner sees no customer details
{ "offerId": "…", "deliveryId": "…", "orderNumber": "GA-…", "orderType": "RESTAURANT",
  "store": { "name": "Green Basket", "city": "Bengaluru", "latitude": 12.97, "longitude": 77.59 },
  "dropArea": "Indiranagar, Bengaluru",                // landmark/line2 + city, like the store board
  "distanceKm": 1.2,                                    // partner → store when offered
  "tripKm": 3.4,                                        // store → customer
  "itemCount": 3, "expiresAt": "…" }

// DeliveryView — the assigned partner (and admins) see the drop details
{ "deliveryId": "…", "orderId": "…", "orderNumber": "GA-…", "orderType": "GROCERY", "status": "ASSIGNED",
  "readyForPickup": false, "readyBy": "…" | null,
  "pickup": { "name", "city", "latitude", "longitude" },
  "drop": { "recipientName", "phone", "line1", "line2", "landmark", "city", "postalCode", "latitude", "longitude" },
  "note": "Ring twice" | null, "itemCount": 3, "total": 245.00, "currency": "INR",
  "assignedAt", "pickedUpAt", "deliveredAt", "failedAt", "failure": { "reason", "note" } | null,
  "timeline": [ { "status", "at" } ] }
```

## 4. Customer tracking (role `CUSTOMER`, own orders; any admin)

`GET /api/delivery/orders/{orderId}/tracking` → `TrackingView`; no delivery yet or another customer's order → 404
`TRACKING_NOT_FOUND`.
```jsonc
{ "orderId": "…", "status": "PICKED_UP",                 // delivery status (PENDING … DELIVERED / FAILED / CANCELLED)
  "partner": { "name": "Ravi", "vehicleType": "SCOOTER",           // first name only
               "phone": "+919840066004" | null,                     // added 2026-11-18, see below
               "vehicleNumber": "TN09AB4521" | null } | null,
  "pickup": { "name", "latitude", "longitude" }, "drop": { "latitude", "longitude" },
  "partnerLocation": { "latitude", "longitude", "heading", "at" } | null,   // only while ASSIGNED or PICKED_UP
  "etaMinutes": 12 | null,                               // partner → (store →) customer
  "timeline": [ { "status", "at" } ] }
```
Live: the `DELIVERY_LOCATION` signal (§7) carries the new position; the app moves the marker without refetching.

`partner.phone` and `partner.vehicleNumber` (added 2026-11-18): the partner's phone, so the customer can call them,
and their number plate, so the customer can spot them. Set only while the partner holds the delivery (`ASSIGNED`:
heading to the store, `PICKED_UP`: on the way to the customer); null before and after (`DELIVERED`, `FAILED`,
`CANCELLED`), so the customer does not keep the partner's number. Only this endpoint shows them to customers, and it
answers 404 to anyone but the order's customer (and admins). Older clients ignore the two fields.

## 5. Admin (delivery-service)

| Method & path | Body → Response |
|---|---|
| `GET /api/admin/deliveries?status=&assignmentFailed=&partnerId=&q=&page=&size=` | any admin → `PageResponse<AdminDeliveryView>` (DeliveryView + partner id/name, `assignmentFailed`, offers sent), newest first; `q` = order number |
| `GET /api/admin/deliveries/{id}` | any admin → AdminDeliveryView + `offers: [ { offerId, partnerId, partnerName, distanceKm, status, sentAt, closedAt } ]` |
| `POST /api/admin/deliveries/{id}/reassign` | SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER · `{ "partnerId": "…" (optional), "reason": "…" (optional ≤ 300) }`. With `partnerId`: assign that partner now (must be APPROVED, online, free; else 409 `PARTNER_UNAVAILABLE`) → `DeliveryPartnerAssigned` (`manual: true`). Without: drop the current partner and search again (excluding them). Only while SEARCHING or ASSIGNED (409 `INVALID_DELIVERY_TRANSITION`). A current partner gets `DeliveryPartnerUnassigned` |
| `GET /api/admin/delivery-partners/online` | any admin → `[ { partnerId, name, vehicleType, latitude, longitude, locationAt, busy, activeDeliveryId } ]` |

## 6. Events

**delivery.events** (outbox, aggregate `Delivery`, key orderId — next to the partner lifecycle events, which stay keyed
by partnerId): `DeliveryOfferCreated`, `DeliveryOfferClosed` (EXPIRED / REJECTED / WITHDRAWN), `DeliveryPartnerAssigned`,
`DeliveryPartnerUnassigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed`, `DeliveryAssignmentFailed`,
`DeliveryCancelled`. **delivery.location** (direct Kafka producer with grocery-common's Avro encoder, no outbox, key
orderId, best effort, at most one per partner per 2 s): `DeliveryLocationUpdated`. Payloads: the `.avsc` files.

**order.events** new: `OrderOutForDelivery`, `OrderDelivered`, `OrderDeliveryFailed` (with `partnerId`);
`OrderConfirmed` v3 adds optional `storeLocation { name, city, latitude, longitude }`.
**inventory.events** new: `StockCommitted { orderId, reservationId, items[productId, quantity], committedAt }`.

delivery-service consumes `order.events` (group `delivery-service.order-events`): OrderConfirmed → PENDING delivery
(no storeLocation in an older event → pickup = null and the search uses the drop point); OrderAccepted → SEARCHING;
OrderReadyForPickup → readyForPickup; OrderCancelled → CANCELLED unless already PICKED_UP or later (then ignored and
logged: admin cancel is not allowed after pickup).

## 7. Changes in the other services

- **cart-service:** `CheckoutPreview.store` and `CartView.store` gain `city`, `latitude`, `longitude` (from the vendor
  store / restaurant menu quote it already reads).
- **order-service:** snapshot `store_city`, `store_latitude`, `store_longitude` at creation; OrderConfirmed carries
  `storeLocation`. Statuses `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_FAILED`. Consumer `order-service.delivery-events`:

  | Event | Applied when the order is | Result |
  |---|---|---|
  | DeliveryPartnerAssigned | ACCEPTED, PREPARING, READY_FOR_PICKUP | record `delivery.partnerId/partnerName/assignedAt` (no status change, no event) |
  | DeliveryPartnerUnassigned | same | clear the partner |
  | DeliveryPickedUp | READY_FOR_PICKUP | `OUT_FOR_DELIVERY` + OrderOutForDelivery |
  | DeliveryCompleted | OUT_FOR_DELIVERY | `DELIVERED` + OrderDelivered |
  | DeliveryFailed | OUT_FOR_DELIVERY | `DELIVERY_FAILED` + OrderDeliveryFailed |

  OrderView gains `delivery: { partnerName, assignedAt, pickedUpAt, deliveredAt } | null` and `trackable` (true from
  ACCEPTED to OUT_FOR_DELIVERY). Neither customers nor admins can cancel after pickup. Stats list the new statuses.
- **inventory-service:** OrderOutForDelivery (GROCERY) → `commit` the reservation (Phase 4 logic) + `StockCommitted`
  in the same transaction. A failed delivery does not return stock automatically (the vendor adjusts it).
- **vendor- / restaurant-service:** OrderOutForDelivery → store order `PICKED_UP` (terminal success; not in `ACTIVE`;
  reports count it as accepted and sold).
- **notification-service:**

  | Event | Recipient | `type` · severity / signal |
  |---|---|---|
  | DeliveryOfferCreated | partner | `DELIVERY_OFFER` · INFO "New delivery from {store}" · "{distanceKm} km away · respond within {s} s" · `/delivery` + signal `DELIVERY_OFFERS_CHANGED` |
  | DeliveryOfferClosed | partner | signal `DELIVERY_OFFERS_CHANGED` |
  | DeliveryPartnerAssigned | customer | `PARTNER_ASSIGNED` · INFO "{partnerFirstName} will deliver order {no}" · `/orders/{id}`; partner signal `DELIVERY_UPDATED`; store owner signal `STORE_ORDERS_CHANGED` |
  | DeliveryPartnerUnassigned | partner | `DELIVERY_REASSIGNED` · WARNING "Order {no} was reassigned" |
  | OrderReadyForPickup | assigned partner (projection from DeliveryPartnerAssigned) | `PICKUP_READY` · INFO "Order {no} is ready at {store}" · `/delivery` |
  | OrderOutForDelivery | customer | `OUT_FOR_DELIVERY` · INFO "Order {no} is on the way" · `/orders/{id}`; store owner signal |
  | OrderDelivered | customer | `ORDER_DELIVERED` · SUCCESS "Order {no} delivered" · "Enjoy!" |
  | OrderDeliveryFailed | customer | `DELIVERY_FAILED` · ERROR "We could not deliver order {no}" · "Our support team will contact you about a refund." |
  | OrderDeliveryFailed | SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER | `DELIVERY_FAILED_ADMIN` · ERROR "Delivery failed for order {no}" · "{reason}. Decide on a refund in the payment monitor." · `/admin/orders` |
  | DeliveryAssignmentFailed | SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER | `NO_DELIVERY_PARTNER` · WARNING "No delivery partner for order {no}" · "Assign one by hand." · `/admin/deliveries` |
  | DeliveryCancelled (with partner) | partner | `DELIVERY_CANCELLED` · WARNING "Order {no} was cancelled" · "Do not pick it up." |
  | every delivery event | SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER | signal `ADMIN_DELIVERIES_CHANGED { deliveryId, orderId }` |
  | DeliveryLocationUpdated (group `notification-service.location`; older than 30 s → dropped; at most 1 per order per 2 s) | the order's customer | signal `DELIVERY_LOCATION { orderId, latitude, longitude, heading, at }` |

  `data` carries `orderId`, `orderNumber`, and `deliveryId` / `offerId` where relevant. The order projection keeps
  the partner id (from DeliveryPartnerAssigned) and the new statuses; ORDER_UPDATED and ADMIN_ORDERS_CHANGED are sent
  for the new order events as for the others.

## 8. Gateway and compose

- Gateway: `/api/delivery/**` already reaches delivery-service; add `/api/admin/deliveries`, `/api/admin/deliveries/**`.
- delivery-service now needs Redis (GEO, `REDIS_HOST`) and produces to Kafka directly (`delivery.location`).
- Compose passes the §2 settings; the smoke test uses `DELIVERY_OFFER_TTL=10s DELIVERY_POLL_INTERVAL=2s`.

## Addendum: the partner's performance statistics (dashboard charts)

`GET /api/delivery/stats?from=YYYY-MM-DD&to=YYYY-MM-DD` — role `DELIVERY_PARTNER` (approved; otherwise 403
`PARTNER_NOT_ACTIVE`), the caller's own data only. India-time days, both included, at most 92; without dates the
last 7 days. A malformed date is 400 `INVALID_PARAMETER`; `from` after `to` or more than 92 days is 400
`VALIDATION_FAILED`.

- `totals` — `assigned` (by assignment time), `delivered` / `failed` (by when the delivery ended), `cancelled` (after it
  was assigned to the partner, by cancellation time), `offersReceived` / `offersAccepted` / `offersRejected` /
  `offersExpired` (by when the offer was sent), `successRate` = delivered / (delivered + failed),
  `acceptanceRate` = accepted / (accepted + rejected + expired) (withdrawn offers left out), `distanceKm` (trips
  delivered, straight line), `earnings` (estimated, see below), `avgPickupMinutes` (assigned → picked up),
  `avgRideMinutes` (picked up → delivered), `avgTotalMinutes`, `onlineMinutes`; `inProgress` and `offersPending` are
  right now. Rates and averages are `null` when there is nothing to compute them from.
- `days` — every day of the range, oldest first: `delivered`, `failed`, `cancelled`, `earnings`, `distanceKm`,
  `onlineMinutes`.
- `payRate` — `basePerDelivery`, `perKm`, `currency`.

**Estimated earnings** = for each delivered order, `grocery.delivery.pay.base-per-delivery` + `per-km` × trip km
(`DELIVERY_PAY_BASE`, default 25.00; `DELIVERY_PAY_PER_KM`, default 6.00; INR). The defaults are placeholders, not a
pay policy. Earnings are computed when read, so a new rate also changes past estimates.

**Online time** comes from `partner_online_session` (V8): a session opens when the partner goes online and closes when
they go offline; location reports keep `last_seen_at` current (every 30 s at most), and a report after more than
5 minutes of silence ends the session at its last report and opens a new one, so a phone left "online" without
reporting is not counted. History starts with V8.

## Addendum: the store's deliveries (who is collecting each order)

`GET /api/delivery/store/deliveries` (no parameters) — role `VENDOR` (needs the `vendor_id` claim, otherwise 403
`VENDOR_NOT_ACTIVE`) or `RESTAURANT_OWNER` (`restaurant_id`, otherwise 403 `RESTAURANT_NOT_ACTIVE`); any other role
403. → a JSON **array** (not a page) of the caller's store's deliveries still at the store: status `PENDING`,
`SEARCHING` or `ASSIGNED` and `storeId` = the caller's vendorId / restaurantId, oldest `createdAt` first, at most 100;
`[]` when there are none. The store order board shows who will collect each order instead of "waiting for a delivery
partner".
```jsonc
[ { "deliveryId": "…", "orderId": "…", "orderNumber": "GA-…",
    "status": "ASSIGNED",                                // PENDING | SEARCHING | ASSIGNED
    "assignmentFailed": false,                           // nobody inside the largest radius yet (still retried)
    "assignedAt": "2026-11-17T10:00:00Z" | null,
    "partner": { "name": "Senthil Kumar", "phone": "+919840012345", "vehicleType": "MOTORCYCLE",
                 "vehicleNumber": "TN09AB4521" | null } | null,   // full name: the store hands the order over
    "partnerLocation": { "latitude", "longitude", "heading", "at" } | null,
    "distanceToStoreKm": 1.2 | null,                     // straight line partner → pickup point, 0.1 km
    "etaToStoreMinutes": 4 | null } ]                    // that distance × 1.3 at 20 km/h, rounded up
```
`partner` is null until a partner is assigned. `partnerLocation`, `distanceToStoreKm` and `etaToStoreMinutes` only
while ASSIGNED, once the partner has reported a location and the delivery has a pickup point (older OrderConfirmed
events carry no store location). Once picked up (or cancelled) the delivery leaves the list. The store owner gets
`STORE_ORDERS_CHANGED` on DeliveryPartnerAssigned (§7); the partner's position is not pushed to the store
(`DELIVERY_LOCATION` goes to the customer only): refetch to update it. Served by
`idx_delivery_store_status (store_id, status)` (V9).
