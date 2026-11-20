# 6. Order Saga Workflow (Choreography)

There is no central coordinator. **order-service owns the order status.** Every other service reacts to events and
owns its own timeouts. **`OrderCancelled` is the single trigger for compensation.**

## Happy path

```mermaid
sequenceDiagram
  participant C as Customer - React
  participant O as order-service
  participant I as inventory-service
  participant P as payment-service
  participant R as vendor or restaurant
  participant D as delivery-service
  participant N as notification-service

  C->>O: POST /api/orders with Idempotency-Key
  Note over O: sync reads - cart, price quote, address
  O-->>C: 201 CREATED
  O->>I: OrderCreated - grocery only
  I->>O: StockReserved - expires in 15 min
  O->>P: OrderAwaitingPayment
  P->>P: create Razorpay order
  P->>O: PaymentInitiated
  C->>P: GET checkout session, pay in Razorpay Checkout, verify signature
  P->>O: PaymentCaptured - webhook is the source of truth
  O->>R: OrderConfirmed
  R->>O: VendorOrderAccepted or RestaurantOrderAccepted
  O->>D: OrderAccepted - start partner offers
  D->>O: DeliveryPartnerAssigned
  R->>O: ReadyForPickup
  O->>D: OrderReadyForPickup
  D->>O: DeliveryPickedUp
  O->>I: OrderOutForDelivery - commit stock
  D->>O: DeliveryCompleted
  O->>N: OrderDelivered
  Note over N: notification-service consumes every step and pushes to the right users
```

**Restaurant orders** skip inventory. order-service checks menu availability and open status synchronously when
the order is created, then emits `OrderAwaitingPayment` right away.

## Order status

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> AWAITING_PAYMENT: StockReserved or restaurant order
  CREATED --> REJECTED: StockReservationFailed
  AWAITING_PAYMENT --> CONFIRMED: PaymentCaptured
  AWAITING_PAYMENT --> CANCELLED: payment expired or reservation expired
  CONFIRMED --> ACCEPTED: vendor or restaurant accepts
  CONFIRMED --> CANCELLED: rejected, acceptance timeout, customer cancels
  ACCEPTED --> PREPARING
  PREPARING --> READY_FOR_PICKUP
  ACCEPTED --> READY_FOR_PICKUP: grocery packed
  READY_FOR_PICKUP --> OUT_FOR_DELIVERY: DeliveryPickedUp
  OUT_FOR_DELIVERY --> DELIVERED: DeliveryCompleted
  OUT_FOR_DELIVERY --> DELIVERY_FAILED: DeliveryFailed
  ACCEPTED --> CANCELLED: approved cancellation
  DELIVERED --> [*]
  REJECTED --> [*]
  CANCELLED --> [*]
  DELIVERY_FAILED --> [*]
```

## Failures and compensation

```mermaid
flowchart TD
  F1["Out of stock<br/>StockReservationFailed"] --> REJ["Order REJECTED<br/>no payment created"]
  F2["Payment declined<br/>PaymentFailed"] --> RETRY["Stay AWAITING_PAYMENT<br/>customer may retry until reservation expires"]
  F3["No payment in 15 min<br/>PaymentExpired"] --> CAN
  F4["Reservation expired<br/>StockReservationExpired"] --> CAN
  F5["Vendor or restaurant rejects<br/>or acceptance deadline passes"] --> CAN
  F6["Customer cancels before acceptance"] --> CAN
  CAN{{"order-service emits<br/>OrderCancelled with reason"}}
  CAN --> C1["inventory: release reservation"]
  CAN --> C2["payment: refund if captured,<br/>otherwise mark session dead"]
  CAN --> C3["vendor or restaurant: drop the fulfillment task"]
  CAN --> C4["delivery: cancel offers or assignment"]
  CAN --> C5["notification: tell customer, store, rider, admin"]
  F7["Late capture after cancellation"] --> C6["payment: automatic refund"]
  F8["No partner after N offers"] --> M1["DeliveryAssignmentFailed<br/>delivery manager assigns manually<br/>or admin cancels with refund - approval"]
  F9["Customer unreachable<br/>DeliveryFailed"] --> M2["DELIVERY_FAILED<br/>admin decides refund - approval"]
  F10["Refund API error"] --> M3["retry with backoff<br/>then RefundFailed to admin queue"]
```

## In plain language

- An order is a **chain of small steps**, each done by a different service. Each service finishes its step and
  announces it with an event. The next service picks it up from there.
- **order-service** keeps the official status of the order. It listens to every step and updates the status.
- Each service is responsible for its **own deadlines**:
  - inventory: reservation expiry
  - payment: payment window
  - restaurant and vendor: acceptance window
  - delivery: offer expiry
- When something goes wrong, order-service announces **`OrderCancelled`** with a reason. Every service then undoes
  its own part: stock is released, money is refunded, the rider is freed, and people are told.
- Events can arrive late or twice. Services check whether a change still makes sense before applying it. Example:
  if a payment arrives for an already cancelled order, it is refunded automatically.

## Key rules

- `POST /api/orders` requires an `Idempotency-Key`. Retries never create a second order.
- Webhooks, not the browser callback, are the final source of truth for payment status.
- Every status change is stored in `order_status_history` together with the causing event ID.
- Deadline pollers use `SELECT ... FOR UPDATE SKIP LOCKED`.
- Phase 10 adds a **stuck-order watchdog** and an **order timeline** to make choreography easy to debug.
  See [ADR-0004](../adr/0004-choreography-for-order-saga.md).
