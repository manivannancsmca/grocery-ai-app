# 7. Real-Time Notification Architecture

## Multi-replica fan-out

```mermaid
flowchart LR
  K[["Kafka domain events"]]

  subgraph NA["notification replica A"]
    A1["1 resolve recipients<br/>from event IDs + projection"]
    A2[("2 store persistent notification<br/>notification_db inbox")]
    A3["3 PUBLISH to Redis"]
    SA["SSE hub A"]
  end

  subgraph NB["notification replica B"]
    SB["SSE hub B"]
  end

  RP[("Redis pub/sub")]

  K -->|"consumer group notification-service.dispatch<br/>each event handled by one replica"| A1
  A1 --> A2 --> A3 --> RP
  RP --> SA
  RP --> SB
  SA -->|"SSE - only locally<br/>connected recipients"| U1["Users connected to A"]
  SB -->|"SSE"| U2["Users connected to B"]
```

## Connect, receive, reconnect

```mermaid
sequenceDiagram
  participant B as Browser - RealtimeProvider
  participant G as API Gateway
  participant N as notification-service
  participant DB as notification_db
  participant R as Redis pub/sub

  B->>G: GET /api/notifications/stream with Bearer JWT
  G->>N: forward - JWT already validated
  N->>N: bind stream to userId and roles, compute channels
  N-->>B: SSE open, heartbeat every 20 s
  R->>N: message for user 42
  N-->>B: event id 1001 - OrderAccepted
  Note over B: network drop
  B->>G: reconnect with Last-Event-ID 1001 and fresh token
  G->>N: forward
  N->>DB: load notifications for user after id 1001
  N-->>B: replay 1002, 1003
  N-->>B: live events continue
  Note over B: also invalidate React Query caches so data is refetched
```

## Message kinds

| Kind | Examples | Stored? | On reconnect |
|---|---|---|---|
| Persistent notification | New order for restaurant, order accepted, approval requested, refund done | Yes, MySQL inbox (its ID is the SSE event ID) | Replayed using `Last-Event-ID` |
| Ephemeral live signal | Rider location, stock in/out, dashboard counters | No | Client refetches through React Query invalidation |

## Channels (always computed by the server)

| Channel | Who receives | Example |
|---|---|---|
| `user:{userId}` | One user, all their tabs | Customer's own order status |
| `role:ADMIN` | Connected admins (filtered by sub-role) | Activity feed, stuck orders, approvals |
| `public:catalog` | All connected shoppers, throttled | Product went out of stock |

## In plain language

- When something happens, the notification service decides **who** should know. It works this out from the IDs
  inside the event (customer, vendor, restaurant, rider) and a small local lookup table.
- Important messages are **saved first**, then sent. If your connection drops, you get the missed ones back when you reconnect.
- There can be several copies of the notification service. Your browser is connected to just one of them.
  **Redis pub/sub** passes every message to every copy, and each copy delivers it only to the users connected to it.
- Fast-changing data like a rider's position is **not saved**. If you miss some, the next update or a quick refetch
  fixes the screen.
- You can only receive what you are allowed to see. A vendor never receives another vendor's orders.

## Key rules

- **Transport is SSE.** Browser actions use normal REST.
- **The stream is authenticated with the JWT.** A fetch-based SSE client sends the `Authorization` header and `Last-Event-ID`.
- **Token expiry:** the server closes the stream when the token expires. The client reconnects with a fresh token
  and exponential backoff.
- **Server-side subscriptions only.** Clients never subscribe to arbitrary topics.
- **No broadcast of everything.** Stock is pushed only on in-stock ↔ out-of-stock transitions, and throttled.
- **Account suspension:** a suspended user's open stream gets a final "suspended" event and is closed.
