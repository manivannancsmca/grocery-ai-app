# api-gateway

Single entry point for the React application (Spring Cloud Gateway, reactive).

| | |
|---|---|
| Port | 8080 |
| Database | none (Redis from Phase 2 for rate limits and the suspended-user denylist) |
| Status | **Phase 1**: routing, CORS (frontend origin only), `X-Correlation-Id` creation/propagation |
| Phase 2 | JWT validation via identity-service JWKS, denylist, rate limiting, strip client identity headers, business routes |
| Phase 10 | operations routes: each service's dead letters under one admin prefix, Kafka Connect status and restart (below) |
| Phase 11 | AI routes to ai-orchestrator-service: streamed chat `/api/ai/agents/*/chat` (no response timeout), insights (150 s), `/api/ai/**`, `/api/approvals/**`, `/api/admin/ai/**`, `GET /api/health/ai`. `X-Approval-Token` now passes through to the services (they verify it); `X-User-*` and `X-Internal-Token` are still stripped |
| Phase 12 | reviews (review-service, 8114), coupons, returns, purchasing, email preferences and log, admin reports (reporting-service, 8116); `GET /api/health/review`, `/api/health/reporting` (below) |

## Routes (Phase 1)
| Path | Target | Notes |
|---|---|---|
| `GET /api/identity/actuator/health` | identity-service `/actuator/health` | used by the React connectivity widget |

Configuration lives in `src/main/resources/application.yml` under `spring.cloud.gateway.server.webflux`.
Service URLs come from environment variables (`IDENTITY_SERVICE_URL`, …) set in `infrastructure/local/docker-compose.yml`.

## Operations routes (Phase 10)
| Path | Target | Roles |
|---|---|---|
| `/api/admin/ops/<service>/dead-letters/**` | that service's `/api/admin/dead-letters/**` (13 routes: identity, customer, vendor, restaurant, delivery, catalog-command, catalog-query, inventory, pricing, cart, order, payment, notification) | any admin; the service itself allows replay and discard only to SUPER_ADMIN and OPS_ADMIN |
| `GET /api/admin/system/connectors` | Kafka Connect `/connectors?expand=status` (`KAFKA_CONNECT_URL`) | any admin |
| `POST /api/admin/system/connectors/{name}/restart` | Kafka Connect `/connectors/{name}/restart?includeTasks=true&onlyFailed=true` | SUPER_ADMIN, OPS_ADMIN (checked here) |

`/internal/**` paths of the services are never routed. Contract: `docs/api/phase-10-event-driven-hardening.md`.

## Post-MVP routes (Phase 12)
| Path | Target | Edge rule |
|---|---|---|
| `/api/reviews/**`, `/api/store/reviews/**`, `/api/admin/reviews/**` | review-service (`REVIEW_SERVICE_URL`, default `http://localhost:8114`) | `GET /api/reviews` and `GET /api/reviews/summary` are public (product and restaurant pages); the rest needs a token |
| `/api/coupons/**`, `/api/vendor/coupons/**`, `/api/restaurant/coupons/**`, `/api/admin/coupons/**` | pricing-service | token |
| `/api/returns/**`, `/api/store/returns/**`, `/api/admin/returns/**` (requests: `/api/orders/{id}/returns`, order route) | order-service | token |
| `/api/vendor/suppliers/**`, `/api/vendor/purchase-orders/**`, `/api/admin/purchase-orders/**` | vendor-service | token |
| `/api/notifications/preferences`, `/api/admin/notifications/**` | notification-service | token |
| `/api/admin/reports/**` | reporting-service (`REPORTING_SERVICE_URL`, default `http://localhost:8116`) | token |
| `/api/admin/ops/review/dead-letters/**`, `/api/admin/ops/reporting/dead-letters/**` | the service's `/api/admin/dead-letters/**` | token |
| `GET /api/health/review`, `GET /api/health/reporting` | the service's `/actuator/health` | public |

These routes are declared **before** the stakeholder routes (first match wins): `/api/restaurant/**` (restaurant-service)
must not swallow `/api/restaurant/coupons`. Every `/api/admin/**` path still needs an admin role at the edge; the
finer role checks (for example who may moderate or create coupons) stay in the services.
Contract: `docs/api/phase-12-post-mvp-features.md` §8.

## Store directory
`/api/stores/**` (the grocery stores delivering to a point, one store's public card) is part of the `vendor` route
(vendor-service) and a guest read: GET without a token (`SecurityConfig.PUBLIC_READ_PATHS`), every other method needs
one. Contract: `docs/api/phase-3-catalog-and-search.md` §9.

## Run
```bash
cd infrastructure/local
docker compose --profile identity up -d --build api-gateway
curl -i localhost:8080/api/identity/actuator/health
```

## Tests (inside Docker)
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 -w /workspace \
  maven:3.9.16-eclipse-temurin-25-noble mvn -f services/api-gateway/pom.xml verify
```
`GatewayRoutingIntegrationTest` starts the real gateway against one stub server per backend service and checks
routing (which service received each path, so a general route swallowing a specific one fails the test), path
rewriting, roles at the edge, correlation-ID propagation (exactly one header value) and CORS.
