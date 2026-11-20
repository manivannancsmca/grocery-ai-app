# identity-service

Registration, login, RS256 access tokens + JWKS, refresh-token rotation, admin user management and the
business claims (`vendor_id`, `restaurant_id`) every other service relies on.

| | |
|---|---|
| Port | 8101 |
| Database | `identity_db` (user `identity_user`): `user_account`, `user_role`, `refresh_token`, `password_reset_token`, `business_membership`, `jwt_signing_key`, `auth_audit`, `outbox_event`, `processed_event` |
| Redis | `auth:revoked-before:<userId>` markers read by the gateway |
| Publishes | `identity.events`: UserRegistered, UserSuspended, UserReactivated, PasswordResetRequested, PasswordChanged |
| Consumes | `vendor.events`, `restaurant.events` → business memberships → JWT claims (group `identity-service.business-events`); `customer.events` CustomerProfileUpdated → the account's name and phone (group `identity-service.customer-events`) |
| API | `docs/api/phase-2-identity-and-stakeholders.md` §2–3 |

## How the tokens work
| Token | Where it lives | Lifetime | Notes |
|---|---|---|---|
| Access token (JWT, RS256) | SPA memory only | 15 min | claims: `sub`, `roles`, `email`, `name`, `iat_ms`, `vendor_id`/`restaurant_id` (APPROVED businesses only); `iss` `https://identity.grocery.local`, `aud` `grocery-api` |
| Refresh token (opaque) | httpOnly, Secure, SameSite=Strict cookie `grocery_rt`, path `/api/auth` | 14 days | only its SHA-256 hash is stored; rotated on every refresh |

- **Rotation & reuse detection** — refresh returns a new token and revokes the old one. Presenting a rotated
  token again within 10 s → `409 REFRESH_RACE` (two tabs; client retries). Later → the whole session family is revoked.
- **Immediate effect of changes** — approval, suspension and reinstatement write a Redis "revoked-before" marker;
  the gateway answers older tokens with `401 TOKEN_REVOKED`; the client refreshes and gets current claims.
- **Lockout** — 5 wrong passwords (at sign-in or as the current password of a change) → 15 min lock. Unknown email, wrong password and lockout all return the same
  `401 INVALID_CREDENTIALS` (no account enumeration). Everything is written to `auth_audit`.
- **CSRF** — `/api/auth/refresh` and `/logout` use the cookie, so they also require `X-Requested-With: XMLHttpRequest`.
- **Signing key** — generated on first start and stored in `jwt_signing_key` (local/MVP). Production: KMS / Secrets Manager.
- **Bootstrap admin** — when no SUPER_ADMIN exists, one is created from `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`
  (local default `admin@grocery.local` / `Admin@12345`).
- **Development accounts** — with `DEV_SEED_ENABLED=true` (`grocery.identity.dev-accounts.enabled`; on in the local
  Compose file and the `local` profile, off everywhere else, never with profile `aws`) `DevAccountSeeder` creates
  `dev.customer`, `dev.vendor`, `dev.restaurant`, `dev.delivery`, `dev.ops`, `dev.finance`, `dev.support` and
  `dev.delivery-manager` `@grocery.local`, password `Dev@12345`. The first four go through the registration path
  (UserRegistered event, so the other services create their profiles); existing accounts are never changed.
  The dev sign-in screen lists them (`frontend/grocery-web/src/features/auth/devAccounts.ts` — keep both lists in step).

## Passwords (`service/PasswordService.java`, contract §3)
| Endpoint | What it does |
|---|---|
| `POST /api/auth/password/change` (signed in) | Checks the current password (a wrong one counts towards the lockout; while locked even the right one gets `400 CURRENT_PASSWORD_INCORRECT`), wants a new one that follows the registration rule and differs (`400 PASSWORD_UNCHANGED`). Then: new hash, every refresh-token family revoked **except the caller's** (from the `grocery_rt` cookie; reason `PASSWORD_CHANGED`), revoked-before marker (the caller's client just refreshes), unused reset links ended, `auth_audit` `PASSWORD_CHANGED`, event `PasswordChanged` (CHANGE). `204` |
| `POST /api/auth/password/forgot` (public) | Always `202`, and never faster than `min-response-time` (250 ms): no enumeration by answer or by timing. For an ACTIVE account with fewer than `max-requests-per-hour` (3) links in the last hour (the user row is locked while counting): the older unused links end, a new `password_reset_token` row (SHA-256 hash, `created_at`, `expires_at` = now + `ttl` 30 min, `requested_ip`), event `PasswordResetRequested` with the raw token. Every outcome is audited (`PASSWORD_RESET_REQUESTED`, or `PASSWORD_RESET_IGNORED` with the reason) |
| `POST /api/auth/password/reset` (public) | The token's row (locked), unused, unexpired, of an account still ACTIVE, else `400 RESET_TOKEN_INVALID` (audited). Then: new hash, lockout lifted, link used, other links ended, **every** refresh-token family revoked (`PASSWORD_RESET`), revoked-before marker, event `PasswordChanged` (RESET). Signs nobody in. `204` |

The raw reset token travels in `PasswordResetRequested` because notification-service builds the emailed link. It is a
deliberate trade-off: the link works once and for 30 minutes, identity_db keeps only the hash (like refresh tokens),
and the token is never logged. Settings: `grocery.identity.password-reset.ttl` (env `PASSWORD_RESET_TTL`, `30m`),
`.max-requests-per-hour` (`3`), `.min-response-time` (`250ms`). Flyway `V5__password_reset_token.sql`.

## A customer's name (`service/CustomerProfileSyncService.java`)
`customer.events` CustomerProfileUpdated (customerId = userId), group `identity-service.customer-events`: the account
takes the profile's `fullName` and `phone`, so the header, the JWT `name` claim, `/api/auth/me` and the admin user
list show what the customer saved. Idempotent (`processed_event`); `user_account.profile_updated_at` (Flyway
`V6__user_account_profile_updated_at.sql`) holds the `updatedAt` last applied, and an older event changes nothing. A
changed name writes the revoked-before marker: the client's next call refreshes into a token with the new name. An
unknown user or an event without customerId, fullName or updatedAt is logged and skipped.

## Events: Avro (ADR-0011)
- Payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/identity.events/*.avsc`, pinned dependency `grocery-event-schemas` 1.7.0).
  grocery-common 0.6.0 writes each outbox row twice in the business transaction: `payload` (JSON, kept for
  auditing/debugging/rollback) and `payload_avro` (Flyway `V3__outbox_avro_payload.sql`), which the Debezium
  connector publishes (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- Consumed events (`vendor.events`, `restaurant.events`, `customer.events`) are decoded from Avro (or legacy JSON) back to the same JSON,
  so the listeners are unchanged.
- Schema Registry: `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`, default
  `http://localhost:8081`); tests use the in-memory `mock://identity-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; re-point the connector to `payload` first).

## Run
```bash
cd infrastructure/local
docker compose --profile core up -d --build identity-service
curl localhost:8101/.well-known/jwks.json
```
From the IDE: stop the container, run `IdentityServiceApplication` with profile `local` (MySQL `localhost:3307`,
Redis `localhost:6379`, Kafka `localhost:9092`). Requires a local JDK 25.

## Tests (inside Docker — Testcontainers MySQL 8.4 + Redis)
From the repository root (Git Bash):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble \
  bash -c "mvn -q -f libs/grocery-event-schemas/pom.xml install -DskipTests && mvn -q -f libs/grocery-common/pom.xml install -DskipTests && mvn -f services/identity-service/pom.xml verify"
```
22 integration tests: registration/cookie/claims/outbox, duplicate email, validation, lockout, enumeration,
`/me`, rotation + race + reuse detection, CSRF header, logout, JWKS, admin search/suspend/reactivate,
role checks, admin creation, vendor approval → `vendor_id` claim → suspension removes it, duplicate events,
Avro outbox round trip (`AvroOutboxRoundTripIntegrationTest`: every event's `payload_avro` decodes to its `payload`).
`PasswordIntegrationTest`: change (this session kept, the others signed out, marker, event, audit; no cookie → all
signed out; wrong current password → lockout; rule and PASSWORD_UNCHANGED; 401), forgot (same 202 and ≥ 250 ms for known
and unknown addresses, hash only, suspended account, 3 per hour and only the newest link works), reset (lockout lifted,
every session signed out, single use, unknown / expired / replaced links). `CustomerProfileSyncIntegrationTest`: a new
name reaches the account, the JWT and the admin list; duplicates and older profiles; a phone-only change keeps the
tokens; unknown users.
`src/test/resources/avro/identity.events/TestEvent.avsc` is a test-only schema for the placeholder event
`PlatformFoundationIntegrationTest` publishes (every outbox event needs a schema).
