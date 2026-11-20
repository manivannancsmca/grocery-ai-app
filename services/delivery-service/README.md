# delivery-service

Delivery partner (rider) onboarding and approval lifecycle (Phase 2) and, since Phase 9, deliveries: offers and
assignment to the nearest available partner, partner availability and live location (Redis GEO), pickup / deliver /
fail, customer tracking and the admin delivery monitor; Phase 10 adds the cleanup of old trail samples.

| | |
|---|---|
| Port | 8112 |
| Database | `delivery_db` (user `delivery_user`) — `delivery_partner` (+ availability columns), `delivery_partner_status_change`, `delivery`, `delivery_status_history`, `delivery_excluded_partner`, `delivery_offer`, `location_sample`, `outbox_event`, `processed_event`, `platform_setting` + `platform_setting_change` (admin platform settings) |
| Redis | `REDIS_HOST` / `REDIS_PORT` (docker profile: host `redis`) — one GEO set, see below |
| Publishes | `delivery.events` (outbox): partner lifecycle (key partnerId) and delivery events (key orderId); `delivery.location` (direct producer): `DeliveryLocationUpdated` |
| Consumes | `identity.events` (group `delivery-service.identity-events`), `order.events` (group `delivery-service.order-events`) |
| API | `docs/api/phase-2-identity-and-stakeholders.md` §5 (partner profile), `docs/api/phase-9-delivery.md` §3–§5 and its addenda (partner statistics, the store's deliveries), `docs/api/phase-10-event-driven-hardening.md` §6 (housekeeping), `docs/api/platform-settings.md` (area `delivery`) |

**partnerId = userId**: a partner's id is the identity user id, so there is no separate owner column and
"my profile" is a lookup by the JWT subject.

## Partner lifecycle (Phase 2)
```
DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
  ▲                      │ reject
  └──── edit ◀── REJECTED ◀┘
```
All transition rules live in `partner/entity/DeliveryPartner.java`. Every change writes a
`delivery_partner_status_change` row and an outbox event in the same transaction
(`partner/service/PartnerLifecycleRecorder.java`). Any admin role can read; approve/reject/suspend/reinstate are
allowed for SUPER_ADMIN, OPS_ADMIN **and DELIVERY_MANAGER** (they run the fleet). Status changes lock the partner row
(location reports update the same row every few seconds).

Required to submit: fullName, phone, city, vehicleType (BICYCLE | SCOOTER | MOTORCYCLE | CAR); vehicleNumber and
drivingLicenseNumber unless the vehicle is a BICYCLE. Email comes from registration / the JWT and is not editable.

Vehicle and licence numbers are validated and stored **normalised** (spaces and hyphens removed, upper case), so
`ka-01-ab 1234` is stored as `KA01AB1234`:
- vehicle number `^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4}$` (KA01AB1234, DL3C1234, MH121234)
- driving licence `^[A-Z0-9]{10,20}$`

## Deliveries and dispatch (Phase 9, package `dispatch`)

### Statuses
```
PENDING (paid) ─OrderAccepted─▶ SEARCHING ─accept / admin─▶ ASSIGNED ─pickup─▶ PICKED_UP ─deliver─▶ DELIVERED
                                   ▲                           │ admin reassign            └─fail──▶ FAILED
                                   └───────────────────────────┘
PENDING / SEARCHING / ASSIGNED ─OrderCancelled─▶ CANCELLED          (after pickup: ignored and logged)
```
- `OrderConfirmed` → a PENDING delivery with a snapshot of the order: pickup = `storeLocation` (v3; older events
  have none → pickup null and the search uses the drop point), drop = `deliveryAddress`, recipient, phone, note,
  item count (units), total.
- `OrderAccepted` → SEARCHING and the first wave at once. `OrderReadyForPickup` → `readyForPickup` (pickup before it:
  409 `ORDER_NOT_READY`). `OrderCancelled` → CANCELLED, open offers WITHDRAWN, the assigned partner released,
  `DeliveryCancelled`.
- The partner: pickup (ASSIGNED → PICKED_UP), deliver / fail (PICKED_UP → DELIVERED / FAILED; the partner is free
  again). Repeating a step in the state it leads to → 200 unchanged.

### Dispatch model (contract §2)
- **Available partner:** APPROVED, online, no active delivery, no other open offer, last stored location newer than
  `location-stale-after`. Decided from the **database**; Redis only finds candidates.
- **Search rounds, waves, radii.** Round 1 starts when the store accepts. A **wave** = the `offer-batch-size` nearest
  available partners within the current radius of the search point (pickup, else drop) who were not offered this
  delivery in this round; each gets an offer valid for `offer-ttl`. When every offer of the wave was rejected or
  expired, the next wave goes out (on rejection immediately, on expiry with the poller); when nobody is left in the
  radius, the next one of `search-radii-km`. Nobody inside the largest radius → `assignmentFailed = true`,
  `DeliveryAssignmentFailed` (once per delivery) and a new round from the smallest radius every `retry-interval`,
  partners offered before included. `assignmentFailed` is cleared when someone is assigned.
- **First accept wins.** Accept, reject, expiry and withdrawal all run under the **delivery row lock**: of concurrent
  accepts exactly one wins; the others find their offer WITHDRAWN → 409 `OFFER_NOT_AVAILABLE`
  (`currentStatus` = the offer's status; an offer past its expiry reports EXPIRED and is closed on the spot).
- **One poller** (`DispatchPoller`, every `poll-interval`, batches of `poll-batch-size`, `SELECT … FOR UPDATE SKIP
  LOCKED`, safe with replicas): each SEARCHING delivery has `next_search_at` = the expiry of its open offers, the retry
  time, or now; claiming the due ones covers "expire offers", "start next waves" and "retry failed searches". A batch
  that fails is retried one delivery per transaction; a delivery failing on its own is postponed by a poll interval.
- **Admin reassign** (SEARCHING / ASSIGNED only): with `partnerId` the partner is assigned now (`manual: true`;
  APPROVED, online and free, else 409 `PARTNER_UNAVAILABLE`; their open offers elsewhere are withdrawn); without, the
  current partner is dropped and never offered this delivery again (`delivery_excluded_partner`) and a fresh round
  starts at once. A current partner gets `DeliveryPartnerUnassigned`.
- **Locking rule** (`dispatch/service/AssignmentEngine`): delivery rows first (the changed one, then any other in
  ascending id), partner rows after (ascending id, one statement); offers only under their delivery's lock; location
  reports and availability lock only the partner. A wave locks its candidates and re-checks them, so two deliveries
  never offer the same partner at the same moment. Transactions are READ COMMITTED.
- Distances are straight-line (haversine, R = 6371 km), shown with one decimal; ETA = km × 1.3 / 20 km/h, rounded up
  (partner → store → customer while ASSIGNED, partner → customer while PICKED_UP).

### The partner's history
`GET /api/delivery/history` (and its export `GET /api/delivery/history/export`, same filters) lists the caller's
DELIVERED and FAILED deliveries, newest first by `finished_at`. Optional filters: `status` = `DELIVERED` | `FAILED`
(absent = both) and `from` / `to` = ISO days, both included, on the day the delivery ended in `timezone`
(`Asia/Kolkata`) — the same days as the summary's "today" and "this week". An unknown status (also `ASSIGNED` and the
other non-final ones) or a malformed date → 400 `INVALID_PARAMETER`; `from` after `to` → 400 `VALIDATION_FAILED`.
The range is a range on `idx_delivery_partner_finished (partner_id, finished_at)` (V4), so no new index; the status is
checked on the rows it finds. `GET /api/delivery/history/summary` takes no filters.

### Customer tracking
`GET /api/delivery/orders/{orderId}/tracking` (`TrackingService`, `DeliveryMapper.toTracking`): only the order's
customer (and admins); anyone else → 404 `TRACKING_NOT_FOUND`. The partner is shown by **first name** and vehicle
type. Their **phone** and **number plate** (`partner.phone`, `partner.vehicleNumber`), position and ETA only while
they hold the delivery (ASSIGNED / PICKED_UP): the customer can call and spot them on the way, but no longer gets the
number once the delivery is DELIVERED, FAILED or CANCELLED (null then). The store's view
(`/api/delivery/store/deliveries`) has the full name and phone while the order is still at the store.

### Location reports
`PUT /api/delivery/partners/me/location` (online only, else 409 `PARTNER_OFFLINE`): a report less than 2 s after the
last **stored** one is accepted (204) but neither stored nor published. A stored report becomes the partner's last
location (`delivery_partner.last_*`), is GEOADDed, and while the partner has an ASSIGNED / PICKED_UP delivery it is
sampled into `location_sample` (at most one row per delivery per 15 s) and published as `DeliveryLocationUpdated` —
so at most one event per partner per 2 s. Redis and Kafka run after the database commit and never fail the call.

### Trail retention (Phase 10, `dispatch/location/LocationSampleCleanup.java`)
Every `grocery.delivery.location-sample-cleanup-interval` (1h, fixed delay) the `location_sample` rows recorded more
than `grocery.delivery.location-sample-retention` ago (env `DELIVERY_LOCATION_SAMPLE_RETENTION`, 30d) are deleted,
oldest first, in batches of 1000 — each batch one short `DELETE … ORDER BY recorded_at LIMIT 1000` statement of its own
(index `idx_location_sample_recorded`, Flyway `V5__location_sample_retention.sql`), so it never holds locks a location
report waits on for long — until a batch comes back short; the total is logged. It runs with the dispatch poller
(`pollers-enabled`) and is safe in every replica.

### Redis keys
| Key | Type | Content |
|---|---|---|
| `delivery:partners:geo` | GEO (sorted set) | member = partnerId, position = last stored location. `GEOADD` on every stored report and when going online with a known location, `ZREM` when going offline, `GEOSEARCH key FROMLONLAT lng lat BYRADIUS r km ASC COUNT 200` per radius. Only an index: stale or missing members never get offers (freshness from the database); a lost Redis delays assignment until the next reports, a search that cannot reach Redis is retried after a poll interval (never declared failed). |

### Settings (`grocery.delivery.*`, `config/DispatchProperties`)
| Property | Env | Default |
|---|---|---|
| `offer-ttl` | `DELIVERY_OFFER_TTL` | `30s` |
| `offer-batch-size` | `DELIVERY_OFFER_BATCH_SIZE` | `2` |
| `search-radii-km` | `DELIVERY_SEARCH_RADII_KM` | `3,6,10` (ascending) |
| `poll-interval` | `DELIVERY_POLL_INTERVAL` | `5s` |
| `retry-interval` | `DELIVERY_RETRY_INTERVAL` | `60s` |
| `location-stale-after` | `DELIVERY_LOCATION_STALE_AFTER` | `60s` |
| `pollers-enabled` | `DELIVERY_POLLERS_ENABLED` | `true` (tests: false) |
| `location-min-interval`, `trail-sample-interval`, `poll-batch-size`, `timezone` | — | `2s`, `15s`, `20`, `Asia/Kolkata` (summary days) |
| `location-sample-retention` (Phase 10) | `DELIVERY_LOCATION_SAMPLE_RETENTION` | `30d` |
| `location-sample-cleanup-interval` (Phase 10) | — | `1h` |

### Platform settings (admin, area `delivery`)
`offer-ttl`, `offer-batch-size`, `search-radii-km` and `location-stale-after` above are only the **defaults**: admins
change them on **Admin → Platform settings** (`GET` / `PUT /api/admin/settings/delivery`, read: any admin, save:
SUPER_ADMIN or OPS_ADMIN; contract `docs/api/platform-settings.md`). A saved value is stored in `platform_setting`
(Flyway `V10__platform_settings.sql`, audit rows in `platform_setting_change`) and overrides the default; until an
admin saves, dispatch behaves exactly as configured.
- **API fields and bounds:** `searchRadiiKm` (1–5 ascending steps, each 0.5–50 km), `offersPerWave` (1–10),
  `offerTimeLimitSeconds` (10–300), `locationFreshnessSeconds` (30–600: the partner app re-sends a still position every
  25 s).
- **When it applies:** `AssignmentEngine` reads the values in force (`settings/DeliverySettingsService`) at every search
  step, so the next wave of every search uses them; offers already sent keep their expiry. A shorter radius list
  continues a running search from its largest step.
- **Delay:** each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`); the instance that saved uses them at once.
- **History:** `GET /api/admin/settings/delivery/history?page=&size=` (any admin): the audit rows as a `PageResponse`,
  newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, values in the API's units
  (`searchRadiiKm` as a list), `null` = the default (contract §5). Read from the table on every call.
- **Tests:** `PlatformSettingsIntegrationTest` (defaults unchanged without an override, an override used by the next
  search, reset, 409 `SETTINGS_CHANGED`, validation, roles, audit rows, the history: order, units, paging, roles).

## Events
| Topic | Event | Key | Notes |
|---|---|---|---|
| `delivery.events` | `DeliveryPartnerRegistered/Submitted/Approved/Rejected/Suspended/Reinstated` | partnerId | aggregate `DeliveryPartner`, payload `{partnerId, fullName, reason, actorUserId}` |
| `delivery.events` | `DeliveryOfferCreated`, `DeliveryOfferClosed` (EXPIRED / REJECTED / WITHDRAWN), `DeliveryPartnerAssigned` (`manual`), `DeliveryPartnerUnassigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed`, `DeliveryAssignmentFailed` (once per delivery), `DeliveryCancelled` | orderId | aggregate `Delivery`, through the outbox |
| `delivery.location` | `DeliveryLocationUpdated` | orderId | direct producer, best effort (failures logged) |

Payloads exactly as `libs/grocery-event-schemas` **1.2.0** (`avro/delivery.events/*.avsc`,
`avro/delivery.location/DeliveryLocationUpdated.avsc`); payload records in `dispatch/event/DeliveryEvents.java`.

### Avro (ADR-0011)
- Outbox events: grocery-common 0.6.0 writes `payload` (JSON) and `payload_avro` (Confluent wire format, Flyway
  `V3__outbox_avro_payload.sql`) in the business transaction; the Debezium connector publishes `payload_avro`
  (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- `delivery.location`: `LocationEventPublisher` encodes the value with grocery-common's `AvroEventEncoder` (same
  schema lookup as the outbox) and sends `byte[]` through its own `KafkaTemplate<String, byte[]>`
  (`KafkaLocationRecordSender`: built from Boot's producer settings, acks=1, max.block.ms=1000), with the headers the
  outbox router sets: `eventId`, `eventType`, `eventVersion` (1), `aggregateType` (`Delivery`), `correlationId`,
  `occurredAt` (epoch microseconds). With `grocery.events.avro.enabled=false` the value is the JSON.
- Consumed `identity.events` / `order.events` are decoded from Avro (or legacy JSON) back to JSON; listeners read
  their own tolerant DTOs.
- Schema Registry: `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`, default
  `http://localhost:8081`); tests use the in-memory `mock://delivery-service`.

## Package layout
```
config/                Clock bean (UTC, microsecond ticks)
partner/…              Phase 2 onboarding (controller, dto, entity, event, mapper, repository, service)
dispatch/config/       DispatchProperties (grocery.delivery.*), scheduling switch
dispatch/controller/   PartnerDispatchController (/api/delivery/…), TrackingController, StoreDeliveryController
                       (/api/delivery/store/deliveries), AdminDeliveryController
dispatch/dto/          request / response records
dispatch/entity/       Delivery (state machine + search state), DeliveryOffer, history, enums, OrderSnapshot
dispatch/event/        DeliveryEvents (payloads), DeliveryEventPublisher (outbox), OrderEventsListener (consumer)
dispatch/geo/          Distances (haversine, ETA), GeoPoint, PartnerGeoIndex (Redis GEO)
dispatch/location/     LocationEventPublisher, LocationRecordSender, KafkaLocationRecordSender (delivery.location),
                       LocationSampleCleanup (Phase 10 trail retention)
dispatch/mapper/       DeliveryMapper
dispatch/repository/   Spring Data JPA + LocationTrail (JDBC)
dispatch/service/      AssignmentEngine (rules + locking), DispatchPoller, DeliveryProjection (order events),
                       OfferService, PartnerDeliveryService, AvailabilityService, TrackingService,
                       StoreDeliveryService, DeliveryAdminService
settings/              admin platform settings (area delivery): PlatformSettingStore (table + 15 s cache, history),
                       DeliverySettingsService, AdminSettingsController, SettingChange (history entry)
```

## Run / test
```bash
cd infrastructure/local && docker compose --profile core up -d --build delivery-service
# tests (from repo root, Git Bash) — Testcontainers MySQL + Redis; no Kafka broker (records are handed to the
# listeners, the delivery.location producer is replaced by a recorder)
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/delivery-service/pom.xml clean verify
```
`LocationSampleCleanupIntegrationTest` (Phase 10): samples older than 30 days deleted in batches (2,500 rows → three
batches), samples inside the retention and exactly 30 days old kept, a second run deletes nothing, the defaults.

Debezium connector config: `deploy/debezium-connector.json` (`delivery-outbox`).
