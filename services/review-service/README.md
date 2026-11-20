# review-service

Phase 12 §1: **reviews and ratings**. A customer rates what a delivered order brought them — each product or dish,
the store (vendor or restaurant) and the delivery partner — for 30 days after delivery. Reviews are moderated
automatically (and by admins when flagged), stores reply publicly, and every change of a target's published ratings is
summarised (average, count, 1–5 distribution) with an increasing `summaryVersion` that catalog-query puts into search.

| | |
|---|---|
| Port | 8114 |
| Database | `review_db` (user `review_user`) — tables `review_order`, `review_order_item` (eligibility projection), `review`, `rating_summary`, `outbox_event`, `processed_event`, `platform_setting`, `platform_setting_change` (admin platform settings) |
| Publishes | `review.events` (Avro schemas 1.7.0): `ReviewSubmitted`, `ReviewModerated`, `ReviewReplied` (aggregate `Review`, key reviewId), `RatingSummaryUpdated` (aggregate `RatingSummary`, key targetId) |
| Consumes | `order.events` → `OrderConfirmed`, `OrderDelivered` (group `review-service.order-events`, idempotent with `processed_event`); everything else ignored |
| API | `docs/api/phase-12-post-mvp-features.md` §1 (gateway routes in §8) |
| Debezium | `deploy/debezium-connector.json` (connector `review-outbox`, server id 5114, topic prefix `review`) |

## Endpoints

| Method & path | Who | |
|---|---|---|
| `GET /api/reviews/eligible?orderId=` | CUSTOMER | `[ { orderId, orderNumber, deliveredAt, targetType, targetId, targetName, review: ReviewView \| null } ]` — the caller's delivered orders still in the window (newest delivery first; at most 50 orders), or just `orderId` (empty when it is not the caller's, not delivered or too old). Per order: products/dishes in line order, then the store, then the partner |
| `POST /api/reviews` | CUSTOMER | `{ orderId, targetType, targetId, rating 1–5, title? ≤ 80, comment? ≤ 1000 }` → 201 `ReviewView` · 422 `REVIEW_NOT_ELIGIBLE` with `reason` `NOT_DELIVERED` (also unknown / another customer's order) \| `NOT_IN_ORDER` \| `WINDOW_OVER` · 409 `ALREADY_REVIEWED` (`reviewId`) · 400 `VALIDATION_FAILED` |
| `PUT /api/reviews/{id}` | the author | `{ rating, title?, comment? }` → `ReviewView`, moderated again · 422 `REVIEW_EDIT_WINDOW_OVER` (7 days after writing) · 404 `REVIEW_NOT_FOUND` for anyone else |
| `DELETE /api/reviews/{id}` | the author | → 204 (any time, any status; the target can then be reviewed again while the window is open) |
| `GET /api/reviews/mine?page=&size=` | CUSTOMER | `PageResponse<ReviewView>`, every status, newest first |
| `GET /api/reviews?targetType=&targetId=&sort=newest\|highest\|lowest&page=&size=` | **public** | `PageResponse<PublicReview>`, published only |
| `GET /api/reviews/summary?targetType=&targetId=` | **public** | `{ targetType, targetId, averageRating, ratingCount, distribution }` (zeros for an unreviewed target) |
| `GET /api/store/reviews?targetType=&rating=&page=&size=` | VENDOR, RESTAURANT_OWNER | published reviews of the caller's store (`vendor_id` / `restaurant_id` claim) and of its products / dishes, newest first; delivery-partner reviews are not the store's |
| `PUT /api/store/reviews/{id}/reply` | VENDOR, RESTAURANT_OWNER | `{ text ≤ 500 }` → `ReviewView`; one reply, editable · 404 unless a published review of the caller's store |
| `GET /api/admin/reviews?status=&targetType=&q=&page=&size=` | any admin | `PageResponse<ReviewView>`, `status` default `FLAGGED`; `q` in title, comment, author, target name, order number; most recently submitted first |
| `POST /api/admin/reviews/{id}/publish` · `/reject` · `/remove` | SUPER_ADMIN, OPS_ADMIN, SUPPORT | reject / remove need `{ reason ≤ 300 }` → `ReviewView` · 409 `REVIEW_NOT_MODERATABLE` (`currentStatus`) · 404 |
| `POST /api/admin/reviews/summaries/republish` | SUPER_ADMIN, OPS_ADMIN | *addition to the contract:* re-sends `RatingSummaryUpdated` for every published summary with its **current** version → `{ republished }` (rebuilding catalog-query's indexes) |

`targetType`: `PRODUCT`, `MENU_ITEM`, `VENDOR`, `RESTAURANT`, `DELIVERY_PARTNER` (case-insensitive in query parameters).
A wrong parameter type or unknown enum value is 400 `INVALID_PARAMETER`; no token on a non-public endpoint 401.

**`ReviewView`** (author, store and admin lists): `id, orderId, orderNumber, targetType, targetId, targetName, storeId,
storeName (from the order, null when unknown), customerId, authorName, rating,
title, comment, status (PUBLISHED|FLAGGED|REJECTED|REMOVED), flagReason, moderationReason, moderatedAt, edited,
reply: { text, repliedAt, updatedAt } | null, createdAt, submittedAt, updatedAt, editableUntil`.
**`PublicReview`**: `id, targetType, targetId, authorName, rating, title, comment, edited, reply, createdAt, updatedAt`
(no customer, order or moderation data). `distribution` (and the event's `ratingDistribution`): index 0 = 1 star …
index 4 = 5 stars.

## Rules

- **Eligibility** comes only from the projection: `OrderConfirmed` writes the order (number, type, customer, store,
  store name from `storeLocation.name`) and its lines (one row per distinct product/dish); `OrderDelivered` writes the
  partner and `deliveredAt`. Each event upserts only its own columns, so either order and duplicates give the same
  result. Without `OrderConfirmed` (e.g. history before the service existed) the store and partner can still be
  reviewed. A submission locks the order row (`SELECT … FOR UPDATE`), so "already reviewed" cannot race (plus the
  unique key `(customer_id, order_id, target_type, target_id)`).
- **Names**: `targetName` = the item name at ordering time, the store name (fallback `Store` / `Restaurant`), or
  `Delivery partner`. `authorName` = first name + last initial from the JWT `name` claim when the review is written or
  edited ("Asha Sharma" → "Asha S."; one word stays as it is; no name → "Customer").
- **Automatic moderation** (`AutoModerator`, on every submission and edit), the first match is `flagReason`:
  `EMAIL`, `LINK` (scheme, `www.`, or a bare domain with a common TLD), `PHONE` (10+ digits, separators allowed, not a
  date-time), `PROFANITY` (whole words of `moderation/words.txt`, case-insensitive), `UPPER_CASE` (more than 70 % of the
  letters, judged only from 10 letters on so "OK" or "GOOD" pass). Else `PUBLISHED`.
- **Admin decisions**: `FLAGGED → PUBLISHED | REJECTED`, `PUBLISHED → REMOVED`; anything else 409 with `currentStatus`.
  An edit re-moderates whatever the status was (a rejected or removed review that is fixed can be published again;
  the admin fields are cleared).
- **Rating summary** (`RatingSummaryService`): every change that can touch a target's published reviews (publish,
  edit, admin decision, delete) locks the target's `rating_summary` row (`INSERT … ON DUPLICATE KEY UPDATE`, an
  exclusive lock), recomputes count / distribution / average (2 decimals, half up) from `review` with SQL (the JPA
  change is flushed first; READ COMMITTED sees every change committed before the lock), and — only when the result
  differs — increments `summaryVersion` and publishes `RatingSummaryUpdated`. So concurrent reviews of one target get
  distinct versions 1, 2, 3 … each counting all earlier ones.
- **Events** (exact 1.5.0 fields): `ReviewSubmitted` (submit and edit: `edited`, `status`, `flagReason`),
  `ReviewModerated` (`decision`, `reason`, `moderatorId`), `ReviewReplied` (`repliedBy`, `repliedAt` = this reply or
  edit), `RatingSummaryUpdated`. `storeId` is always the order's store (also for partner reviews). A deleted review has
  no event of its own; its summary change has. Notifications are notification-service's job.

## Configuration

| Property | Env | Default |
|---|---|---|
| `grocery.review.review-window` | `REVIEW_WINDOW` | `30d` |
| `grocery.review.edit-window` | `REVIEW_EDIT_WINDOW` | `7d` |
| `grocery.review.moderation.words` | `REVIEW_MODERATION_WORDS` | `classpath:moderation/words.txt` (any Spring resource, e.g. `file:/config/words.txt`) |
| `grocery.review.moderation.upper-case-ratio` / `upper-case-min-letters` | — | `0.70` / `10` |
| `grocery.review.eligible-orders-limit`, `republish-batch-size` | — | `50`, `200` |
| datasource | `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD` | `localhost:3307`, `review_user` / `review_pass` (docker: `mysql:3306`) |
| Kafka / registry / JWT | `KAFKA_BOOTSTRAP_SERVERS`, `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL`, `IDENTITY_JWKS_URI`, `JWT_ISSUER` | as every service |

The security chain is grocery-common's (stateless JWT, `@PreAuthorize`, ProblemDetail 401/403) declared in
`ReviewSecurityConfig` so that only **GET** `/api/reviews` and `/api/reviews/summary` are public (`POST /api/reviews`
without a token is 401). The gateway must also let those two GETs through without a token.

### Platform settings (admin, area `reviews`)
`grocery.review.review-window` is only the **default**: admins change it on **Admin → Platform settings** (`GET` /
`PUT /api/admin/settings/reviews`, field `reviewWindowDays`, 1–365; read: any admin, save: SUPER_ADMIN or OPS_ADMIN;
contract `docs/api/platform-settings.md`). A saved value is stored in `platform_setting` (Flyway
`V4__platform_settings.sql`, audit rows in `platform_setting_change`) and overrides the default.
- **When it applies:** `ReviewService` reads the value in force (`settings/ReviewSettingsService`) for the eligible
  list and each new review. The window counts from delivery, so a change applies to every delivered order at once.
  The edit window (`edit-window`) stays config.
- **Delay:** each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`); the instance that saved uses them at once.
- **History:** `GET /api/admin/settings/reviews/history?page=&size=` (any admin): the audit rows as a `PageResponse`,
  newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, days as in `GET`, `null` = the
  default (contract §5).
- **Tests:** `PlatformSettingsIntegrationTest` (30 days without an override, an override used by the next check,
  reset, 409 `SETTINGS_CHANGED`, validation, roles, audit rows, the history: order, values, paging, roles).

## Package layout
```
config/      Clock, ReviewProperties (grocery.review.*), ReviewSecurityConfig
controller/  ReviewController (/api/reviews/**), StoreReviewController (/api/store/reviews/**),
             AdminReviewController (/api/admin/reviews/**), InvalidParameterAdvice
dto/         requests, ReviewView, PublicReview, RatingSummaryView, EligibleTarget; ReviewMapper
entity/      Review (JPA), ReviewStatus, TargetType
event/       OrderEventsListener (order.events), ReviewEvents (published payloads = the Avro schemas)
repository/  ReviewRepository (JPA), OrderProjection and RatingSummaries (JDBC)
service/     ReviewService, StoreReviewService, ModerationService, RatingSummaryService, AutoModerator,
             AuthorNames, OrderEventsService, ReviewEventPublisher, ReviewException
settings/    admin platform settings (area reviews): PlatformSettingStore (table + 15 s cache, history),
             ReviewSettingsService, AdminSettingsController, SettingChange (history entry)
```

## Run / test
```bash
cd infrastructure/local && docker compose --profile reviews up -d --build review-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4.13, in-memory Schema Registry
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -f services/review-service/pom.xml verify
```
Integration tests hand `order.events` records to the listener directly (Kafka is not started):
`EligibilityIntegrationTest` (projection, eligible list, 422 reasons, window, 409, concurrent duplicates, event order
and duplicates), `ModerationIntegrationTest` (automatic rules, admin list and transitions, 409 `currentStatus`),
`ReviewLifecycleIntegrationTest` (author view, edit window and re-moderation, delete, my reviews),
`StoreReviewsIntegrationTest` (store listing and replies, own store only), `PublicReviewsAndSummaryIntegrationTest`
(public listing with "Asha S.", summary, distribution, increasing and concurrent versions, republish),
`AvroOutboxRoundTripIntegrationTest` (every event encodes with the 1.7.0 schemas and decodes to its JSON payload).
Unit tests: `AutoModeratorTest`, `AuthorNamesAndSummaryTest`.
