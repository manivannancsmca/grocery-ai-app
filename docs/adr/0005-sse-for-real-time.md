# ADR-0005: Server-Sent Events for real-time updates

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

All stakeholders need live updates without refreshing: order status, incoming orders, delivery offers, rider location,
stock changes and approvals. The blueprint allows WebSocket or SSE. Backend services run multiple replicas,
and clients reconnect.

## Decision

- **SSE** from notification-service (`GET /api/notifications/stream`), passed through Spring Cloud Gateway.
- Browser-to-server actions, including delivery location pings every 5–10 s, use plain **REST**.
- **Fan-out across replicas with Redis pub/sub:**
  - notification-service consumes each Kafka event once (one consumer group) and resolves recipients on the server.
  - It stores persistent notifications in MySQL and publishes to Redis.
  - Every replica delivers the message to its locally connected users.
- **Reconnect:**
  - Persistent notifications are replayed by `Last-Event-ID`, using the inbox ID.
  - Ephemeral signals are not replayed. The client refetches through React Query invalidation.
- **Authentication:** a fetch-based SSE client sends `Authorization: Bearer` and `Last-Event-ID`. The server closes the
  stream when the token expires. Heartbeat every 20 s.
- AI chat streaming uses SSE too.

## Consequences

- ✅ Plain HTTP. It works through the gateway, load balancers and proxies with no special configuration.
- ✅ Built-in event IDs and resume semantics.
- ✅ No connection registry needed. Redis pub/sub plus local delivery is enough.
- ⚠️ One-way only. This is fine because all client actions are REST calls.
- ⚠️ The native `EventSource` cannot send headers, so a fetch-based client is required.
- ⚠️ Long-lived connections need tuned idle timeouts on gateway and load balancer, and heartbeats.

## Alternatives considered

- **WebSocket (STOMP):** bidirectional, which we don't need. It requires more infrastructure (broker relay or sticky
  sessions) and has no built-in resume. Rejected.
- **Polling:** simple, but wasteful and slow to update. Rejected, except as a temporary approach in Phase 6.
- **Every replica consuming Kafka with a unique group ID:** each replica would process every event, which is wasteful and
  hard to make idempotent. Rejected in favor of Redis pub/sub.

## Refinements (Phase 7, as built)

- **Three SSE event kinds:**
  - `notification` carries an `id:`, which is the inbox id (UUIDv7, time-ordered), so it can be replayed.
  - `signal` is an ephemeral "this changed, refetch" hint with no id, so it never disturbs `Last-Event-ID`.
  - `session` (`TOKEN_EXPIRED`, `REVOKED`, `REPLACED`) is sent just before the server closes the stream.
- **Audiences** are computed from the token on the server: `user:<sub>`, `role:<ROLE>` for each role, and `public`.
  A notification for several admin roles is stored once, so an admin with two of those roles sees it once. Read state
  is per user.
- **Gateway:** the stream route has no response timeout; every other route keeps the 15 s default. Spring Cloud
  Gateway flushes `text/event-stream` event by event, which a test proves.
- **Events older than 15 minutes** only update the projections, so replays and new replicas never re-notify people.
- **Payment events that arrive before their order is known** are parked and applied once the order arrives. They are
  not retried, because the order and payment topics are co-partitioned onto the same consumer thread, and a blocking
  retry would have starved the event it waits for.
- **The stream limit (5 per user) is per replica.** A global limit would need connection-targeted messages through
  Redis.
