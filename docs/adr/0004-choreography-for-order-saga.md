# ADR-0004: Choreography for the order saga

- **Status:** Accepted (chosen by the product owner)
- **Date:** 2026-11-10

## Context

Placing an order spans inventory reservation, Razorpay payment, vendor or restaurant acceptance, delivery assignment
and notifications. Each step can fail or time out. Distributed transactions are not an option.
A saga can be coordinated by **orchestration** (a central coordinator sends commands) or **choreography**
(services react to each other's events).

## Decision

Use **choreography**:

- **order-service owns the order status.** It reacts to participant events (`StockReserved`, `PaymentCaptured`,
  `RestaurantOrderAccepted`, `DeliveryCompleted`, …) and emits its own state events (`OrderAwaitingPayment`,
  `OrderConfirmed`, `OrderAccepted`, …) that participants react to.
- **Each participant owns its own timeouts:**
  - inventory: reservation expiry
  - payment: payment window
  - vendor and restaurant: acceptance deadline
  - delivery: offer expiry
- **`OrderCancelled` (with a reason) is the single compensation trigger.** Every participant undoes its own part:
  release stock, refund, drop the fulfillment task, cancel delivery.
- State-machine guards handle duplicates and out-of-order events. A late `PaymentCaptured` for a cancelled order is
  refunded automatically.

## When orchestration would be preferable

- Many conditional branches, or compensations that must run in a strict order.
- Strict end-to-end SLAs that need one component to watch the whole process.
- The business wants one place to see and change the workflow definition.
- Teams struggle to answer "where is this order stuck?" even with the mitigations below.

Because events are designed as **facts**, order-service can later become an orchestrator without large changes to the participants.

## Mitigations for choreography's weaknesses

- Event flow is documented in [06-order-saga-workflow](../architecture/06-order-saga-workflow.md) and the
  [event catalog](../events/event-catalog.md).
- `order_status_history` records every status change with the causing event ID.
- Correlation IDs flow through HTTP headers, MDC logs and Kafka headers.
- Phase 10 adds a **stuck-order watchdog** (orders exceeding an SLA per status surface on the admin dashboard)
  and an **order timeline** API and UI.

## Consequences

- ✅ Loose coupling. Participants don't know about each other, only about events.
- ✅ No single coordinator to scale or to become a bottleneck.
- ⚠️ The end-to-end flow is spread across services. It is harder to read and debug without the mitigations above.
- ⚠️ Timeout logic is spread across four services.

## Refinements (Phase 8, as built)

- **Stores keep their own view of the order.** vendor-service and restaurant-service build their store order from
  `OrderConfirmed` and publish their decisions as store-order events: `…OrderReceived`, `…Accepted`, `…Rejected`,
  `…Preparing`, `…ReadyForPickup` on `vendor.events` / `restaurant.events`.
- **order-service stays the only owner of the order status.** It applies those events through state guards, so a
  late or duplicate event changes nothing.
- **Rejection, by the store or automatically at the acceptance deadline, still uses the single compensation
  trigger.** order-service publishes `OrderCancelled` (`STORE_REJECTED` / `STORE_NO_RESPONSE`), and the existing
  payment and inventory consumers refund and release with no change on their side.
- **Each participant owns its own timeout,** as planned: the acceptance deadline and its auto-reject poller live in
  the store services, next to the data they lock.

## Refinements (Phase 10, as built)

- **Stuck-order watchdog** (order-service): a job flags an order that stays in one status longer than that status's
  threshold, for example CREATED with no stock answer after 2 minutes, or CONFIRMED with no store answer after
  15 minutes. Each flag publishes `OrderStuckDetected` once. Admins get a `STUCK_ORDER` notification and see the order
  on the Operations page and the dashboard. The flag clears itself when the order moves on.
  - The watchdog **observes, it does not decide**: it never changes the order. Each participant's own timeouts (payment
    window, acceptance deadline, offer expiry) still drive the saga. A flag means one of those did not fire, usually
    because a participant or its connector is down.
- **Order event timeline:** order-service records every event it publishes or consumes for an order, in the same
  transaction as the processing. Consumed events are marked APPLIED, IGNORED (with the state guard's reason) or
  DUPLICATE. Admins see it in the order drawer, so "why is this order still CONFIRMED?" can be answered without
  reading logs.
- Together with dead-letter replay and the connector watchdog, these are the safeguards listed above. They answer
  this ADR's main risk, a saga step that silently never happens.
