# 3. Stakeholder Interaction

## Who uses what

```mermaid
flowchart TB
  subgraph People
    C(("Customer"))
    V(("Vendor"))
    RO(("Restaurant Owner"))
    DP(("Delivery Partner"))
    A(("Admin<br/>Ops, Finance, Support,<br/>Delivery Manager"))
  end

  subgraph SPA["One React app - lazy-loaded role areas"]
    RC["Shop area<br/>search, cart, checkout,<br/>tracking, AI shopping assistant"]
    RV["Vendor area<br/>products, stock, prices,<br/>orders, AI vendor assistant"]
    RR["Restaurant area<br/>menu, live order board,<br/>sales, AI restaurant assistant"]
    RD["Delivery area<br/>online toggle, offers,<br/>pickup and drop, AI delivery assistant"]
    RA["Admin area<br/>approvals, monitoring, refunds,<br/>config, AI business insights"]
  end

  C --> RC
  V --> RV
  RO --> RR
  DP --> RD
  A --> RA

  RC & RV & RR & RD & RA --> GW["API Gateway"]
  GW --> BE["Backend services"]
  BE -.->|"live updates over SSE"| RC & RV & RR & RD & RA
```

## How one stakeholder's action reaches the others

```mermaid
sequenceDiagram
  actor C as Customer
  actor RO as Restaurant Owner
  actor DP as Delivery Partner
  actor A as Admin
  participant P as Platform

  C->>P: Place order and pay
  P-->>RO: New order on the live board
  P-->>A: Activity feed - new paid order
  RO->>P: Accept order
  P-->>C: Order accepted
  P-->>DP: Delivery offer with countdown
  DP->>P: Accept offer
  P-->>RO: Rider assigned
  P-->>C: Rider assigned
  RO->>P: Food ready
  DP->>P: Picked up
  P-->>C: Out for delivery with live location
  DP->>P: Delivered
  P-->>C: Delivered - rate your order
  A->>P: Suspend a vendor
  P-->>A: Vendor products hidden, sessions closed
```

## Role capabilities

| Stakeholder | Main capabilities | Can see |
|---|---|---|
| Customer | Register, search, cart, checkout, pay, track, cancel, review, AI shopping assistant | Own profile, cart, orders, notifications |
| Vendor | Register (KYC), products, stock, prices, vendor orders, sales, AI vendor assistant | Own products, stock, prices, orders (`vendor_id` claim) |
| Restaurant owner | Register, profile, menu, availability, accept/reject/prepare orders, sales, AI assistant | Own restaurant, menu, orders (`restaurant_id` claim) |
| Delivery partner | Register, go online/offline, accept offers, pickup/deliver/fail, location, history, AI assistant | Own offers and deliveries (`partner_id` claim) |
| Admin (sub-roles) | Approvals, user/vendor/restaurant/partner management, categories, monitoring, refunds, config, AI insights | Everything, limited by sub-role for finance, ops and delivery actions |

## In plain language

- Everyone uses the **same web app**, but each person sees only their own area and their own data.
- Actions by one person often matter to others. A customer's payment creates a new order on the restaurant's
  screen. A rider accepting a job tells both the restaurant and the customer.
- These updates arrive **live**, without refreshing the page.
- The admin team sees an activity feed and can step in. They approve new sellers, issue refunds, reassign
  deliveries and suspend accounts.
- Each role also has an **AI assistant**. It can look things up freely, but anything that costs money or changes
  operations must be approved by a human.

## Key rules

- Authorization is decided by the backend, not the UI. Hidden buttons are a convenience, not a security control.
- Data ownership comes from JWT claims (`vendor_id`, `restaurant_id`, `partner_id`) and is checked in the service layer.
- Live updates go only to people allowed to see them. Nothing is broadcast to everyone.
- Admin powers are split into sub-roles: SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER.
