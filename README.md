# Grocery AI Platform

Enterprise grocery + restaurant commerce platform for five stakeholders (Admin, Customer, Vendor,
Restaurant Owner, Delivery Partner): Java 25 / Spring Boot 4 microservices, MySQL (source of truth),
Kafka + Debezium (transactional outbox), Elasticsearch, Redis, Spring AI agents with human approval,
and a React + TypeScript + Tailwind frontend.

- **Plan & architecture proposal:** [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)
- **Architecture docs (10 Mermaid diagrams), ADRs, event catalog, API contracts:** [docs/](docs/README.md)
- **Status:** Phase 1 foundation ✅ · Phase 2 identity & stakeholder management ✅ · Phase 3 catalog, menus & search ✅ · Phase 4 inventory & pricing ✅ · Phase 5 cart & checkout preview ✅ · Phase 6 orders & payments ✅ · Avro + Schema Registry ✅ · Phase 7 real-time updates ✅ · Phase 8 vendor & restaurant fulfillment ✅ · Phase 9 delivery ✅ · Phase 10 event-driven hardening ✅ · Phase 11 AI assistants & human approval ✅ · Phase 12 reviews, coupons, returns, purchasing, email, store assistants & reporting ✅ · Phase 13 observability & extended testing ✅ · Phase 14 CI/CD (Jenkins) & AWS (Terraform, validated offline) ✅

## Prerequisites
Only **Docker Desktop (WSL2)**. Everything — databases, Kafka, Debezium, Ollama, every service, builds,
tests and the frontend dev server — runs in containers; nothing is installed as a Windows service.
A local JDK 25 / Node 24 is optional (IDE support only).

## Quick start
```bash
cd infrastructure/local
cp .env.example .env            # optional — all values have defaults
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime --profile frontend up -d --build
```
Open http://localhost:6420 and sign in as the bootstrap admin **admin@grocery.local / Admin@12345**
(local only), or register as a customer, vendor, restaurant owner or delivery partner.

**Development accounts.** The local stack also creates one account per stakeholder, and the sign-in screen lists
the accounts of the selected stakeholder in a "Dev only" section (click one to fill in the form). Password
**Dev@12345** for all of them: `dev.customer`, `dev.vendor`, `dev.restaurant`, `dev.delivery`, and the admin roles
`dev.ops`, `dev.finance`, `dev.support`, `dev.delivery-manager` (all `@grocery.local`). The vendor, restaurant and
delivery-partner accounts start at onboarding, like a fresh registration; `seed-chennai.mjs` completes the four
stakeholder accounts with Chennai data and gets them approved (see
[Development accounts](#development-accounts-one-per-stakeholder-start-here)). Switch them off with
`DEV_SEED_ENABLED=false` (accounts) and `VITE_DEV_ACCOUNTS=false` (the section); neither exists outside local
development: the seeder never runs with the `aws` profile and a production frontend build leaves the section out.

**Payments.** Without Razorpay keys, payment-service runs a built-in **payment simulator** (local and Docker profiles
only): the app shows its own "Test payment" dialog and the whole flow — pay, decline, timeout, refunds — runs through
the same code as Razorpay. To use **Razorpay test mode** instead, put your own test keys from the Razorpay dashboard
in `infrastructure/local/.env` (`RAZORPAY_KEY_ID=rzp_test_…`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`) and
recreate payment-service. Webhooks from Razorpay need a public URL to the gateway (e.g. a tunnel to
`http://localhost:8080/api/payments/razorpay/webhook`); without one, verify and the payment-window check still
confirm payments by asking Razorpay directly.

**Passwords.** Every signed-in user changes their password from the account menu (**Change password**): their other
devices are signed out, this one stays signed in. **Forgot password?** on the sign-in screen emails a reset link that
works once, for 30 minutes (locally it lands in Mailpit, http://localhost:8025; the answer on screen is the same
whether or not the address has an account). Reset links and "your password was changed" notices are security emails:
always sent, never in the in-app inbox. Details: [Phase 2 contract §3](docs/api/phase-2-identity-and-stakeholders.md#3-identity-service).
A customer's new profile name also becomes their account name (header, admin user list, email greetings) through the
`CustomerProfileUpdated` event.

| URL | What |
|---|---|
| http://localhost:6420 | React app (Vite dev server, hot reload) |
| http://localhost:8080 | API gateway — every API call goes through it |
| http://localhost:8070 | Kafka UI — topics, messages, DLTs, consumer lag, Debezium connectors |
| http://localhost:8083 | Kafka Connect / Debezium REST API |
| http://localhost:8081 | Schema Registry — Avro event schemas (`/subjects`, `/config`); source files in [libs/grocery-event-schemas](libs/grocery-event-schemas/) |
| localhost:3307 | MySQL (one container, 13 service databases; 3307 because a Windows MySQL often owns 3306) |
| localhost:9092 / 6379 | Kafka (host listener) / Redis |
| localhost:8101-8113 | identity, customer, vendor, restaurant, catalog-command, catalog-query, inventory, pricing, cart, order, payment, delivery, notification services (direct, for debugging) |
| localhost:8123 | second notification-service replica (profile `realtime-ha`, multi-replica check) |
| localhost:8115 | ai-orchestrator-service (profiles `assistant` / `ai`; direct, for debugging) |
| localhost:8000 | speaches, the voice server (profile `voice`; `SPEACHES_HOST_PORT`; `GET /v1/models` lists the downloaded models) |
| localhost:8114 / 8116 | review-service (profile `reviews`) / reporting-service (profile `reporting`) |
| http://localhost:8025 | Mailpit — every email the platform sends locally (profiles `realtime`, `realtime-ha`, `observability`), alert emails included |
| http://localhost:9200 | Elasticsearch (profile `catalog`; aliases `products`, `restaurants`, `categories`) |
| http://localhost:3000 | Grafana — 6 dashboards (folder *Grocery*), Explore for logs (Loki), traces (Tempo), metrics (profile `observability`) |
| http://localhost:9090 / 9093 | Prometheus (targets, alert rules) / Alertmanager (profile `observability`) |

Only the gateway and the frontend listen on the network (`PUBLIC_BIND_ADDRESS`, default `0.0.0.0`). Every other
published port, including MySQL with its local root/root, listens on this machine only (`BIND_ADDRESS`, default
`127.0.0.1`).

### Compose profiles (start only what you need)
| Profile | Adds |
|---|---|
| *(none)* | mysql, kafka (+ topic & DLT init), kafka-connect (+ connector registration), schema-registry (+ schema registration), redis, kafka-ui |
| `core` | api-gateway, identity, customer, vendor, restaurant, delivery services (Phase 2) |
| `frontend` | React dev server |
| `catalog` | elasticsearch + catalog-command + catalog-query (Phase 3) |
| `inventory` | inventory + pricing (Phase 4; pricing uses Redis for its quote cache) |
| `cart` | cart-service (Phase 5; carts live in Redis) |
| `orders` | order + payment services (Phase 6; needs `core`, `catalog`, `inventory` and `cart` for a full checkout) |
| `realtime` | notification-service: inbox + live updates over Server-Sent Events (Phase 7) |
| `realtime-ha` | `realtime` plus a second notification-service replica on port 8123 |
| `search` | elasticsearch only |
| `reviews` | review-service (Phase 12: reviews, moderation, ratings in search) |
| `reporting` | reporting-service (Phase 12: admin reports, CSV) |
| `assistant` | ai-orchestrator-service only (Phase 11; set `AI_PROVIDER=scripted` when no Ollama runs) |
| `ai` | ai-orchestrator-service + ollama + one-shot model pull and warm-up (`OLLAMA_MODEL`, default `qwen3:4b-instruct`) |
| `voice` | speaches (OpenAI-compatible speech to text and text to speech on CPU) + one-shot download of the faster-whisper and Kokoro models; set `AI_VOICE_MODE=server` to use it (without it the assistant uses the browser's own speech features) |
| `observability` | Prometheus, Alertmanager, Grafana, Loki, Tempo, Alloy, OpenTelemetry Collector and the Kafka / Connect / MySQL / Redis / Elasticsearch exporters (Phase 13; about 1.5 GB). It never recreates an application container |
| `all` | everything defined so far |

## Verify Phase 2 end-to-end
```bash
bash infrastructure/local/scripts/smoke-phase2.sh
```
Registers users of every role through the gateway, waits for the event-driven profile creation
(outbox → Debezium → Kafka), runs vendor onboarding → admin approval → `vendor_id` claim → suspension,
customer addresses, and refresh-token reuse detection. Exit code 0 = all checks passed.

## Verify Phase 3 end-to-end
```bash
bash infrastructure/local/scripts/smoke-phase3.sh
```
Admin builds categories → an approved vendor lists and publishes products → anonymous search finds them within a
second or two (typo `tomatos`, synonym `yogurt` → Curd, autocomplete, parent-category filter) → unpublish, vendor
suspension and category deactivation hide them → a restaurant prepares its menu before approval and becomes
searchable by dish near a location after approval → the products index is deleted and rebuilt with "republish".

## Verify Phase 4 end-to-end
```bash
bash infrastructure/local/scripts/smoke-phase4.sh
```
New products reach inventory and pricing through `catalog.events` → the first price applies at once, a −10 % change
applies, a −44 % change waits for an admin (approve / reject with reason) → stock rules (no going below zero, stock
count, low stock) → **25 parallel increases lose nothing and 35 parallel decreases on 25 units succeed exactly 25
times** → all-or-nothing bulk update → search shows the live price, discount and stock, with in-stock and price
filters → the products index is deleted and rebuilt from the catalog, price and stock republish calls.

## Verify Phase 5 end-to-end
```bash
bash infrastructure/local/scripts/smoke-phase5.sh
```
A customer fills a grocery cart (stock and quantity limits, one store per cart, the cart survives a new login) →
checkout preview with the default and other addresses (₹25 base fee, ₹73 with extra kilometres, free above ₹499,
minimum order ₹99, out of area, address without location) → the vendor changes the price, stock falls, the product is
unpublished: the cart shows the live price and blocks checkout with the right issue → 10 parallel adds lose nothing →
a restaurant cart with "not accepting orders" and a sold-out dish → `/internal/**` is not reachable from outside.

## Verify Phase 6 end-to-end
```bash
cd infrastructure/local
ORDER_PAYMENT_WINDOW=45s PAYMENT_EXPIRY_POLL_INTERVAL=5s \
  docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders up -d --build
bash scripts/smoke-phase6.sh
docker compose --profile orders up -d order-service payment-service   # back to the normal 15-minute window
```
(The short payment window is only for the timeout check; without it that one check is skipped.) Missing
Idempotency-Key, empty cart and stale total are refused → **5 parallel requests with one key create one order** → stock
reserved, cart emptied → a declined payment, then pay + verify → CONFIRMED with the stock still held → a forged
signature and a bad webhook signature are refused, **the same webhook three times is applied once** → cancelling an
unpaid order, a **late payment on a cancelled order is refunded automatically**, cancelling a paid order refunds it and
releases the stock → **two customers race for the last units: one order gets them, the other is REJECTED** → a
restaurant order goes straight to payment → admin monitor, stats, an idempotent partial refund → **an unpaid order is
cancelled when the payment window ends and its stock is released**.

## Verify Phase 7 end-to-end
```bash
cd infrastructure/local
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime-ha up -d --build
bash scripts/smoke-phase7.sh
```
Live streams (curl over Server-Sent Events) for an admin, two vendors and a customer:
- a vendor submits and admins are told live, then the approval reaches only that vendor;
- **an out-of-stock change reaches the customer's stream without a refresh**;
- **vendor B's low stock never reaches vendor A**;
- an order moves live: payment declined, then confirmed for the customer, "new order" for the store, and a signal
  for admins;
- the inbox: unread count, mark read, read-all, and another user's notification answering 404;
- **a reconnect with `Last-Event-ID` replays exactly the missed notifications, oldest first**;
- **with two replicas, every notification reaches streams on both** (the consumer group has 2 members);
- a suspension closes the vendor's stream.

## Verify Phase 8 end-to-end
```bash
cd infrastructure/local
ACCEPTANCE_TIMEOUT=30s ACCEPTANCE_POLL_INTERVAL=3s   docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
bash scripts/smoke-phase8.sh
docker compose --profile core up -d vendor-service restaurant-service   # back to the normal 5-minute acceptance window
```
(The short acceptance window is only for the auto-reject check; without it that one check is skipped.)
- **A paid order appears on the store's board instantly**, with a NEW_ORDER alert. Another vendor gets 404 and a
  customer 403.
- The vendor accepts with a ready time, then packing → ready. The customer is told at each step and can no longer
  cancel.
- **A restaurant rejection cancels the order, refunds it in full and tells the customer.** A grocery rejection
  releases the stock.
- A customer cancels before acceptance and the order leaves the board. An admin cancels an accepted order, which is
  refunded.
- **Nobody answers before the deadline → auto-reject, refund, and both sides are told.**
- **The vendor's and the restaurant's sales reports match exactly the orders the test created.**
- A sold-out dish cannot be ordered.

## Verify Phase 9 end-to-end
```bash
cd infrastructure/local
DELIVERY_OFFER_TTL=10s DELIVERY_POLL_INTERVAL=2s   docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
bash scripts/smoke-phase9.sh
docker compose --profile core up -d delivery-service      # back to the normal 30 s offers
```
Four partners stand about 0.5, 1.2, 2.5 and 4.5 km from the store.
- **The two nearest get the offer, and when both accept at once exactly one wins.**
- **An unanswered offer moves on to the next partner** in the next radius.
- **Pickup** (only once the store marked the order ready) commits the stock and the order goes out for delivery.
- **The customer's stream receives the partner's moving position**; tracking shows an ETA and first name only.
- **Delivery sets the order to DELIVERED.** A failed delivery is DELIVERY_FAILED and admins are asked to decide.
- With nobody online, delivery managers are alerted, assign a partner by hand, and a cancellation releases them.

In the app (Vite dev server), open `/delivery?simulate=1` as a partner to drive a simulated position; production
builds have no simulator (only test builds with `VITE_LOCATION_SIMULATOR=true`), so partners share their phone's GPS.
The customer's order page shows the live map (OpenStreetMap tiles; production needs a tile provider).

## Verify Phase 10 end-to-end
```bash
cd infrastructure/local
PAYMENT_RECONCILE_INTERVAL=5s PAYMENT_RECONCILE_AFTER=10s ORDER_WATCHDOG_INTERVAL=5s ORDER_STUCK_CONFIRMED_AFTER=20s \
OUTBOX_RETENTION=2m OUTBOX_CLEANUP_INTERVAL=30s HOUSEKEEPING_INITIAL_DELAY=30s CONNECT_WATCHDOG_INTERVAL=10 \
  docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
bash scripts/smoke-phase10.sh
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d   # back to normal timings
```
The script briefly breaks, then restores, the identity connector.
- **A poison message goes to the DLT without blocking its partition.** A malformed `VendorSubmitted` is followed by
  a real one with the same key, and the real one is still processed. Every consumer lists the poison message. Replaying
  it fails again, and an operator discards it with a reason.
- **Replay is idempotent.** A valid dead-lettered event replays once (`REPLAYED`). Replaying it again gives 409, and a
  second copy of the same event changes nothing.
- **A missed webhook is recovered.** The customer pays, but the verify call and the webhook never arrive.
  Reconciliation finds the capture and the order is CONFIRMED.
- **The order's event timeline** shows every published and consumed event, oldest first.
- **Stuck orders show up and resolve themselves.** A paid order whose store does not answer is flagged
  `STORE_RESPONSE_OVERDUE` once, and admins are notified. When the store accepts, the flag clears.
- **Old outbox rows are deleted** and every connector keeps running.
- **A failed connector task is visible and restarted.** Its error trace shows in the admin API and the watchdog
  restarts it. Once the configuration is restored, the event written during the outage arrives.

In the app, admins open **Operations** (`/admin/operations`) for dead letters, connectors and stuck orders. The
dashboard shows "Stuck orders" and "Pipeline" cards, and the order drawer has an **Events** tab.

## Verify Phase 11 end-to-end
```bash
cd infrastructure/local
AI_PROVIDER=scripted AI_APPROVAL_TTL_CUSTOMER=30s AI_APPROVAL_EXPIRY_INTERVAL=5s \
  docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime \
  --profile assistant up -d --build
bash scripts/smoke-phase11.sh
```
The smoke test uses the deterministic **scripted** model (no LLM). At the end it restarts the assistant service with
`AI_PROVIDER=ollama` and no Ollama running, to show graceful degradation, then restores the scripted model.
- **The shopping assistant searches and adds items to the cart** through its tools, with the customer's own rights.
- **"Checkout" produces an approval** with the exact items and the checkout total. The customer confirms, and the
  order is placed once. A second confirm gives 409.
- **Rejected or expired proposals run nothing.**
- **A tampered approval is refused by the target service.** The stored refund amount is changed after the proposal,
  and payment-service answers `APPROVAL_BODY_MISMATCH`.
- **Cross-role attempts are blocked and audited.** A customer using the admin assistant, a customer asking for a
  refund tool, and a SUPPORT agent proposing a suspension are all refused. A suspension needs a second admin
  (four-eyes).
- **With the model down, the chat says so and everything else keeps working.** Insights fall back to rules.

**With a real model** (Ollama on CPU: expect 15–70 s per answer with `qwen3:4b-instruct` once loaded; an NVIDIA GPU makes it much faster,
see `docker-compose.gpu.yml`):
```bash
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime \
  --profile ai --profile frontend up -d --build     # pulls OLLAMA_MODEL (about 2.5 GB) the first time, then warms it up
```
Open http://localhost:6420 and click **Ask AI**. As a customer, try "find basmati rice under 150" or "add 2 of the
cheapest to my cart". As an admin, try "how are sales today?" or generate **AI insights** on the dashboard. Small local
models sometimes pick the wrong tool or skip one. ToolGuard and the approvals keep that safe, but expect to rephrase.

**Stakeholder-aware answers, history and voice.** The assistant knows who is signed in from the token (role, own store
or profile) and answers from that user's own data, with only the tools that role may use:
- as an admin: "How many vendors have been approved?", "Show pending vendor approvals", "how many restaurants joined";
- as a vendor: "How many orders did I get today?", "Which products are running low?";
- as a customer: "Where is my latest order?", "Find organic apples in the fruits category";
- as a delivery partner: "How many deliveries are assigned to me?".

Conversations are stored per user: after a refresh or a new sign-in the panel reopens the latest one, and
**Conversations** lists, renames and deletes them. The microphone next to **Send** turns speech into text in the input
(check it, then send); the answer to a spoken question is read aloud, and **Listen** reads any answer. By default the
browser's own speech features do this (`AI_VOICE_MODE=browser`); for speech handled by the platform itself:
```bash
docker compose --profile voice up -d          # speaches + model download (about 1 GB the first time)
AI_VOICE_MODE=server docker compose --profile ai up -d ai-orchestrator-service
```
(With host-run services, `run-service.cmd` picks `server` by itself once speaches lists its model.)

## Verify Phase 12 end-to-end
```bash
cd infrastructure/local
AI_PROVIDER=scripted RETURN_DECISION_TIMEOUT=30s RETURN_ESCALATION_INTERVAL=5s \
  docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime \
  --profile reviews --profile reporting --profile assistant up -d --build
bash scripts/smoke-phase12.sh
```
The store, partner and customers stand in Mysuru, away from the other smoke tests' stores. The script checks:
- **Coupons:**
  - a store coupon and a platform coupon; the minimum order and the case-insensitive code;
  - the discount in the preview and in the order;
  - a single-use coupon that exactly one of two simultaneous orders gets;
  - the use freed again by a cancellation.
- **A real delivery:** paid, accepted, picked up and delivered by a partner. A delivered order is what reviews and returns
  need.
- **Reviews:**
  - only what was received can be reviewed;
  - a review with a link is flagged and hidden until an admin publishes it;
  - the store can reply, and the rating shows in search within seconds.
- **Returns:**
  - returning 1 of 2 kg refunds ₹90.00 (its share of a ₹20 coupon is kept) and puts the kilo back in stock;
  - returning the last kilo also refunds the delivery fee;
  - an undecided return escalates to admins.
- **Purchasing:** supplier, purchase order, partial receipt. Stock rises once even when a receipt is retried, and
  over-receiving is refused.
- **Email:** the order confirmation and the refund email arrive in Mailpit (http://localhost:8025). A customer who
  turned order emails off gets none.
- **Assistants (scripted model):** the vendor, restaurant and delivery-manager assistants change a stock level, sell
  out a dish and restart a delivery search only after approval.
- **Reports:** the store's daily sales, the coupon's use and the purchase spend match exactly what the run did. The
  CSV export works.

## Data exports (CSV, Excel, PDF)
Every list page (admin, vendor, restaurant, customer and delivery partner) has an **Export** button: CSV, Excel or PDF of
every row that matches the page's filters, in its order, not just the page on screen. The services stream the file
from the list's own query in keyset batches (up to a million rows), with the list's access rules; people's contact
details reach only support and operations admins. Every section of **Admin → Reports** has the same button (the
section's period and filters). Excel files open with the title and the filters on top and **highlighted column
headers** (white on the brand green, frozen, with filter buttons); PDFs use the same header colours. Contract:
[docs/api/data-exports.md](docs/api/data-exports.md); the shared code: `libs/grocery-common` 0.10.1
(`com.grocery.common.export`). To try it: sign in as any Chennai account below, open a list, **Export** → a format; the
file name and row count appear in a toast.

## Platform settings
Admins change a few business rules in the app instead of in server config: **Admin → Platform settings**
(`/admin/settings`). Every admin role can look; SUPER_ADMIN and OPS_ADMIN can save. Each card shows the default
("Default: 48 h"), marks changed values, and resets a field to its default.

| Area (service that owns it) | Settings (default) |
|---|---|
| Delivery partner search (delivery-service) | search radii (3, 6, 10 km), partners per wave (2), time to accept an offer (30 s), location freshness (60 s) |
| Grocery stores (vendor-service) / Restaurants (restaurant-service) | time to accept an order (5 min) |
| Returns (order-service) | return window (48 h), store decision time (24 h) |
| Reviews (review-service) | review window (30 days) |
| Delivery fees (pricing-service; cart-service charges them) | grocery ₹25 base, 3 km included, ₹8 per extra km, free from ₹499, minimum order ₹99; restaurant ₹30, 3 km, ₹10 |

- **Nothing changes until an admin saves:** the `application.yml` / env values stay the defaults. A saved value is
  stored in the owning service's own database (`platform_setting`, with an audit trail in `platform_setting_change`).
- **New work uses the new value; existing records keep theirs** (an order waiting for acceptance, an open offer, an
  open return, a placed order's fee). The return and review windows count from delivery, so a change there applies to
  every delivered order at once.
- **How soon:** other servers pick a change up within 15 seconds; delivery fees within 30 seconds. If pricing-service
  cannot be reached, checkout keeps the last fees it got (before its first answer, cart-service's own config).
- Road factor and maximum delivery distance stay in config (they also decide which stores are "near you").

Contract, bounds and details: [docs/api/platform-settings.md](docs/api/platform-settings.md). To try it (with the six
owning services, cart-service and the gateway built from this version): sign in as `dev.ops@grocery.local` /
`Dev@12345`, open **Platform settings**, change the review window and save.

## Grocery catalog for local data
A real catalog to shop from: 30 categories (Fruits, Vegetables, Dairy Products, … Millets, Pooja Needs, Pet Care) and
224 products with photos, prices and stock. The data is in `infrastructure/local/seed/catalog/` (see the README there):
```bash
node infrastructure/local/scripts/seed-catalog.mjs --dry-run   # checks the CSV files and the photos only
node infrastructure/local/scripts/seed-catalog.mjs
```
- **How it is loaded:** through the API gateway, like an admin and a store would do it:
  - the admin creates the categories;
  - the store **FreshCart Store** (`dev.store@grocery.local` / `Dev@12345`) is registered and approved;
  - the store creates, prices and stocks the products.

  Search picks everything up from the events.
- **The photos:** they are in `frontend/grocery-web/public/images/products/`, with licenses and authors in
  `CREDITS.md` there. Each category shows one of its products' photos.
- **Re-running:** safe. Products are found again by their code (saved as the SKU) and updated to match the files.
  Stock is only set for products with nothing on hand.

## Chennai test accounts (every stakeholder)
Realistic Chennai data for testing the whole workflow by hand: customers, grocery stores (vendors), restaurants and
delivery partners, every business **approved**, with orders in different delivery states. The areas run from Anna
Nagar, T. Nagar, Mylapore, Adyar and Velachery to Guindy, Porur, Tambaram, Medavakkam and the OMR corridor
(Perungudi, Thoraipakkam, Sholinganallur). The south-Chennai and OMR stores sell a **master list of 30 branded
products** (Aavin, Aachi, Idhayam, Britannia…) with real photos. The data, the product table and the category mapping
are described in [infrastructure/local/seed/chennai/README.md](infrastructure/local/seed/chennai/README.md). The script
loads it through the API gateway, like people do in the app (the admin approves each business; nothing is written to a
database directly):
```bash
node infrastructure/local/scripts/seed-chennai.mjs --check   # offline: the files, the photos, which stores deliver where
node infrastructure/local/scripts/seed-chennai.mjs
```
The script ends by signing in to every account below and checking it: the role; businesses and partners approved;
every store product priced and in stock; every menu item there; every address saved with its map location; every
partner's phone and vehicle. It fails if one does not pass. Last run: 19 Nov 2026, all 54 accounts passed (and
`verify-workflow.mjs`, 37 steps). Re-running
is safe (nothing is created twice). It does not need `seed-catalog.mjs`, which adds the Bengaluru FreshCart Store with
the whole 224-product catalog: leave that out to keep the data Chennai-only. The Chennai stores sell products from all
30 catalog categories themselves (Vijaya Nagar Fresh Mart, next to the development customer, from every one).

- **Password of every account below: `Chennai@123`** (the four development accounts: `Dev@12345`). Sign in at http://localhost:6420/login; each account opens its own
  workspace (customers the storefront, stores `/vendor`, restaurants `/restaurant`, partners `/delivery`).
- **Admin** (approvals and every admin page): `admin@grocery.local` / `Admin@12345`.
- **Test data only:** people and business names are made up, and GSTIN and FSSAI numbers only have the right format.
  The streets and coordinates are real Chennai places (checked against OpenStreetMap), so distances, delivery fees,
  dispatch and tracking behave realistically. E-mails use `example.com`.

### Development accounts (one per stakeholder: start here)
The sign-in screen's development accounts (its "Dev only" section; identity-service creates them, see Quick start).
`seed-chennai.mjs` turns them into four accounts that work together, all in Velachery within 2 km of each other, so
you can follow one order from the customer to the doorstep by switching accounts. Their profiles are filled in,
submitted and approved by the admin through the app's own APIs, like any registration (the `development` block of
`chennai.json`). Password: **`Dev@12345`**. The header shows each account's sign-in name ("Dev Vendor",
"Dev Restaurant"…), which tells the four windows apart; customers, stores and partners see the profile names below.
The customer's header follows their profile name ("Kavya Sriram") once the profile is saved with a customer-service that
publishes `CustomerProfileUpdated`; data seeded before that keeps "Dev Customer" until the profile is saved again.

| Stakeholder | Sign in with | Who | Details | Status |
|---|---|---|---|---|
| Customer | `dev.customer@grocery.local` | Kavya Sriram, +91 98400 66001 | **Home** (default): Flat 3A, Sri Sai Residency, AGS Colony 4th Main Road, AGS Colony, Velachery, Chennai 600042 (12.9782, 80.212)<br>**Office**: International Tech Park, Taramani Road, Taramani, Chennai 600113 (12.9839, 80.2461)<br>**Parents**: 18 Kasturba Nagar 5th Main Road, Kasturba Nagar, Adyar, Chennai 600020 (13.0039, 80.2485) | Active (customers need no approval) |
| Vendor (grocery store) | `dev.vendor@grocery.local` | **Vijaya Nagar Fresh Mart**, owner Ramesh Babu, +91 98400 66002 | 46 Velachery Main Road, Vijaya Nagar, Velachery, Chennai 600042 (12.9772, 80.2213). GSTIN 33AVNFR6602K1Z8, FSSAI 12419006000731. Sells all 30 branded products, priced and in stock | Approved |
| Restaurant owner | `dev.restaurant@grocery.local` | **Kaveri Meals & Tiffin**, owner Shanthi Raghunathan, +91 98400 66003 | 12 Gandhi Road, Sony Nagar, Velachery, Chennai 600042 (12.986, 80.2277). South Indian, breakfast and Chettinad: 12 dishes. Open all day, taking orders | Approved |
| Delivery partner | `dev.delivery@grocery.local` | Suresh Kannan, +91 98400 66004 | Motorcycle TN22EM7302, licence TN2220200093417. Based at the Vijaya Nagar bus terminus, Velachery (12.976, 80.2211) | Approved |

By road, as checkout charges: Kavya's Home is 1.3 km from the store, her Office 2.6 km from the restaurant, Parents
5.4 km from the store. Both businesses deliver to all three addresses.

**By hand.** Use a separate browser window per account (a browser keeps one sign-in, so use private windows or
browser profiles). On the sign-in screen, pick the stakeholder and click its account under "Dev only":
1. **Customer** (Kavya): the header's **Deliver to** shows Home and Vijaya Nagar Fresh Mart is first under "Grocery
   stores near you". Add products (₹99 minimum), check out, pick one of the three saved addresses and **Pay now** →
   **Pay successfully** (the local payment simulator). With Razorpay TEST keys in `infrastructure/local/.env`, the
   Razorpay test window opens instead: card 4111 1111 1111 1111 (any future expiry, any CVV) or UPI `success@razorpay`.
2. **Store** (Ramesh, `/vendor/orders`): within 5 minutes **Accept** (choose "Ready in"), then **Start packing**. Once
   the partner accepts, the card shows who collects it: name, vehicle, distance from the store and a **Call** button.
   Then **Ready for pickup**, or **Reject** with a reason to test a refund.
3. **Partner** (Suresh, `/delivery`): go **Online**. On a computer, switch on **Use simulated location** in the Location
   simulator and click the map near the store (offers go only to partners whose position is under 60 seconds old).
   **Accept** the offer, follow **Navigate to Vijaya Nagar Fresh Mart**, press **Picked up**, then **Drive to the
   customer** and press **Delivered**.
4. **Customer**: the order page follows Suresh on the map until the order is delivered; then rate it.

The restaurant works the same way: Kavya orders from Kaveri Meals & Tiffin, and Shanthi accepts it, starts preparing
and marks it ready on `/restaurant/orders`.

**Automatically.** One command runs the whole workflow through the four accounts and checks every step from each side:
```bash
node infrastructure/local/scripts/verify-workflow.mjs
```
It checks the accounts (role, approval, addresses, products, prices, stock, menu, vehicle). Then it runs three orders:
- **Grocery order to Home:** Kavya finds the store nearby, fills the cart, checks the bill, orders and pays. The store
  accepts and packs it. Suresh gets the offer and accepts it, the store sees who is coming, and the store marks it
  ready. Suresh picks it up and rides to Kavya while her map follows him, then delivers it.
- **Restaurant order to Office:** the same steps with Kaveri Meals & Tiffin.
- **Rejected order to Parents:** the store rejects it, and the order is cancelled and refunded in full.

Last run: 17 Nov 2026, 37 of 37 steps passed. A step that needs a service newer than the one running is shown as
"skipped", with which service to restart. Every run adds three orders to the four accounts' history and empties
Kavya's cart.

**The account and admin features**, end to end, with the emails read from Mailpit:
```bash
node infrastructure/local/scripts/verify-new-features.mjs
```
It checks:
- **Passwords:** change password; forgot and reset, including that a reset link works only once.
- **Profile name:** the customer's new name reaches their account.
- **Platform settings:** a change and its history, a stale save getting 409, reset to default, and who may read or
  change the settings.
- **Delivery history filters.**
- **The coupon fields of the reports.**

It puts back everything it changes: Kavya's password, her name and the review window. Run it at most 3 times an hour,
because identity sends at most 3 reset links per account per hour.

### Customers (role `CUSTOMER`)
Customers need no approval: the accounts are active. Every delivery address has its map location saved.

| Name | Email | Status | Delivery address (latitude, longitude) | To test |
|---|---|---|---|---|
| Kavya Sriram (development) | `dev.customer@grocery.local` | Active | Home: AGS Colony 4th Main Road, Velachery, Chennai 600042 (12.9782, 80.212)<br>Office: International Tech Park, Taramani, Chennai 600113 (12.9839, 80.2461)<br>Parents: Kasturba Nagar 5th Main Road, Adyar, Chennai 600020 (13.0039, 80.2485) | The development customer (see above): orders from Vijaya Nagar Fresh Mart and Kaveri Meals & Tiffin. |
| Lakshmi Narayanan | `lakshmi.chennai@example.com` | Active | Home: 15 Duraisamy Road, West Mambalam, Chennai 600033 (13.0389, 80.2212) | A delivered restaurant order (Pondy Bazaar Tiffin House, by Senthil). |
| Karthik Subramanian | `karthik.chennai@example.com` | Active | Home: 7 C.P. Ramaswamy Road, Alwarpet, Chennai 600018 (13.0339, 80.2547)<br>Office: Tidel Park, Rajiv Gandhi Salai, Taramani, Chennai 600113 (12.9894, 80.2486) | An order out for delivery (Mylai Mess, by Irfan): open it to watch live tracking. |
| Divya Krishnan | `divya.chennai@example.com` | Active | Home: 22 Kamaraj Nagar 3rd Street, Thiruvanmiyur, Chennai 600041 (12.983, 80.2594) | An order assigned to Ravi while Adyar Chettinad Kitchen prepares it. |
| Priya Raman | `priya.chennai@example.com` | Active | Home: 3 Arcot Road, Kodambakkam, Chennai 600024 (13.051, 80.2215)<br>Office: SIDCO Industrial Estate, Guindy, Chennai 600032 (13.0108, 80.2143) | No orders yet. Two saved addresses: switch to Office at checkout and the delivery fee changes. |
| Arun Kumar | `arun.chennai@example.com` | Active | Home: 9 Poonamallee High Road, Kilpauk, Chennai 600010 (13.0789, 80.2421) | No orders yet. |
| Sanjay Venkatesh | `sanjay.chennai@example.com` | Active | Home: 12 Vijaya Nagar 2nd Main Road, Velachery, Chennai 600042 (12.976, 80.219) | A delivered grocery order (Velachery Green Grocers, by Meena). |
| Fathima Begum | `fathima.chennai@example.com` | Active | Home: 34 Peters Road, Royapettah, Chennai 600014 (13.0545, 80.264) | A delivered grocery order (Mylapore Fresh Mart, by Vijay). |
| Deepak Raj | `deepak.chennai@example.com` | Active | Home: W-112, 3rd Street, Anna Nagar West, Chennai 600040 (13.0905, 80.205) | No orders yet: a fresh customer for placing new orders. |
| Meenakshi Sundaram | `meenakshi.chennai@example.com` | Active | Home: 14 Camp Road, Selaiyur, East Tambaram, Chennai 600073 (12.9202, 80.1445) | A delivered grocery order (Tambaram Super Bazaar, by Prabhu). Try a store far away, e.g. Anna Nagar Supermart: checkout refuses it (out of delivery area). |
| Harish Babu | `harish.chennai@example.com` | Active | Home: Flat 2A, Lake View Residency, Mugalivakkam Main Road, Mugalivakkam, Porur, Chennai 600125 (13.0206, 80.1685) | A grocery order assigned to Kathiravan while Porur Family Mart packs it. |
| Swathi Ramesh | `swathi.chennai@example.com` | Active | Home: Flat 3C, Sai Krupa Apartments, Inner Ring Road, Ekkattuthangal, Guindy, Chennai 600032 (13.0225, 80.2062) | No orders yet: a fresh customer near Guindy Daily Fresh. |
| Rahul Menon | `rahul.chennai@example.com` | Active | Home: Flat 11B, Sea Breeze Towers, Rajiv Gandhi Salai (OMR), Kumaran Nagar, Sholinganallur, Chennai 600119 (12.8782, 80.2272)<br>Office: ELCOT SEZ, Elcot SEZ Main Road, Sholinganallur, Chennai 600119 (12.9112, 80.22) | A grocery order out for delivery (OMR Fresh Basket, by Saravanan): open it to watch live tracking. |
| Nandhini Prakash | `nandhini.chennai@example.com` | Active | Home: Flat 5A, Lotus Apartments, Kandanchavadi, Perungudi, Chennai 600096 (12.9625, 80.2455) | A delivered restaurant order (Perungudi Kerala Kitchen, by Yasmin). |
| Balamurugan Selvam | `balamurugan.chennai@example.com` | Active | Home: 9 Mambakkam Main Road, Medavakkam, Chennai 600100 (12.914, 80.1905) | A delivered grocery order (Medavakkam Fresh & Daily, by Arjun). |
| Aishwarya Iyer | `aishwarya.chennai@example.com` | Active | Home: Flat 4D, Ocean Pearl Apartments, Rajiv Gandhi Salai (OMR), Thoraipakkam, Chennai 600097 (12.948, 80.2395) | No orders yet: a fresh customer on OMR, 0.9 km from Thoraipakkam Super Store. |

### Grocery stores (role `VENDOR`)
The first five stores sell 12 products each from the grocery catalog. The six south-Chennai and OMR stores sell the
branded master list: all 30 products, or the part that suits the store (the stock of each is in the
[Chennai data README](infrastructure/local/seed/chennai/README.md)). Everything has a photo, a price and stock, except
Alphonso Mango at Porur Family Mart, which is listed out of stock (it is out of season).

| Store | Owner | Email | Status | Store address (latitude, longitude) | GSTIN | Sells |
|---|---|---|---|---|---|---|
| Vijaya Nagar Fresh Mart (development) | Ramesh Babu | `dev.vendor@grocery.local` | Approved | 46 Velachery Main Road, Vijaya Nagar, Velachery, Chennai 600042 (12.9772, 80.2213) | 33AVNFR6602K1Z8 | All 30 branded products |
| Mylapore Fresh Mart | Ganesh Iyer | `mylaporefresh.chennai@example.com` | Approved | 27 Luz Church Road, Mylapore, Chennai 600004 (13.0335, 80.265) | 33AMFPM4521K1Z3 | Apple, Banana, Mango, Pomegranate… |
| T. Nagar Daily Needs | Revathi Sundaram | `tnagardaily.chennai@example.com` | Approved | 64 Usman Road, T. Nagar, Chennai 600017 (13.0388, 80.2337) | 33ATNDN7788L1Z9 | Ponni Rice, Idli Rice, Wheat Atta, Sooji / Rava… |
| Adyar Organic Basket | Prakash Natarajan | `adyarorganic.chennai@example.com` | Approved | 15 Gandhi Nagar 1st Main Road, Adyar, Chennai 600020 (13.006, 80.253) | 33AADOB3301M1Z2 | Brown Rice, Red Rice, Ragi Flour, Green Gram… |
| Anna Nagar Supermart | Farooq Ahmed | `annanagarsupermart.chennai@example.com` | Approved | Q-18, 3rd Avenue, Anna Nagar, Chennai 600040 (13.085, 80.215) | 33AANSM5642N1Z7 | Murukku, Banana Chips, Marie Biscuits, Milk Chocolate… |
| Velachery Green Grocers | Anitha Mohan | `velacherygreens.chennai@example.com` | Approved | 8 Taramani Link Road, Velachery, Chennai 600042 (12.981, 80.224) | 33AVGGP9087P1Z4 | Orange, Grapes, Watermelon, Brinjal… |
| Tambaram Super Bazaar | Murugan Pandian | `tambaramsuper.chennai@example.com` | Approved | 112 Shanmugam Road, West Tambaram, Chennai 600045 (12.9267, 80.1146) | 33AATFP6612Q1Z5 | All 30 branded products |
| Porur Family Mart | Kavitha Rajendran | `porurfamilymart.chennai@example.com` | Approved | 45 Arcot Road, Porur, Chennai 600116 (13.0352, 80.1585) | 33AAKFP3398R1Z8 | All 30 (Alphonso Mango out of stock) |
| Guindy Daily Fresh | Joseph Antony | `guindyfresh.chennai@example.com` | Approved | 21 Alandur Road, Guindy, Chennai 600032 (13.0095, 80.211) | 33ABHPA7204S1Z1 | 24: fruit, vegetables, rice, dairy, oils, masalas, snacks, beverages |
| OMR Fresh Basket | Vasanth Kumar | `omrfreshbasket.chennai@example.com` | Approved | 201 Rajiv Gandhi Salai (OMR), Sholinganallur, Chennai 600119 (12.901, 80.2279) | 33AABCO5127T1Z6 | All 30 branded products |
| Thoraipakkam Super Store | Shahul Hameed | `thoraipakkamsuper.chennai@example.com` | Approved | 88 Rajiv Gandhi Salai (OMR), Thoraipakkam, Chennai 600097 (12.942, 80.2386) | 33AAJFH8841U1Z2 | 24: packaged goods, no fresh fruit or vegetables |
| Medavakkam Fresh & Daily | Lalitha Gopinath | `medavakkamfresh.chennai@example.com` | Approved | 16 Velachery Main Road, Medavakkam, Chennai 600100 (12.9206, 80.1845) | 33ALGPG2286V1Z9 | 12: fruit, vegetables, rice and grains, dairy and eggs |

### Restaurants (role `RESTAURANT_OWNER`)
Each owner has one approved restaurant with a menu, taking orders.

| Restaurant | Owner | Email | Status | Address (latitude, longitude) | Cuisines | Open |
|---|---|---|---|---|---|---|
| Kaveri Meals & Tiffin (development) | Shanthi Raghunathan | `dev.restaurant@grocery.local` | Approved | 12 Gandhi Road, Sony Nagar, Velachery, Chennai 600042 (12.986, 80.2277) | South Indian, Breakfast, Chettinad | All day |
| Pondy Bazaar Tiffin House | Raghavan Srinivasan | `tiffinhouse.chennai@example.com` | Approved | 48 Thyagaraya Road, Pondy Bazaar, T. Nagar, Chennai 600017 (13.0418, 80.2341) | South Indian, Breakfast | All day |
| Mylai Mess | Kamala Venkatesan | `mylaimess.chennai@example.com` | Approved | 12 North Mada Street, Mylapore, Chennai 600004 (13.0336, 80.2697) | South Indian | All day |
| Adyar Chettinad Kitchen | Muthiah Palaniappan | `chettinadkitchen.chennai@example.com` | Approved | 21 Lattice Bridge Road, Adyar, Chennai 600020 (13.0063, 80.2574) | Chettinad, South Indian, Biryani | All day |
| Anna Nagar Biryani Point | Abdul Rahman | `biryanipoint.chennai@example.com` | Approved | AC-45, 2nd Avenue, Anna Nagar, Chennai 600040 (13.087, 80.2105) | Biryani, North Indian | All day |
| Velachery Dragon Wok | Chen Li Wei | `dragonwok.chennai@example.com` | Approved | 100 Feet Bypass Road, Velachery, Chennai 600042 (12.9786, 80.2186) | Chinese | All day |
| Besant Nagar Beach Cafe | Nisha Fernandes | `beachcafe.chennai@example.com` | Approved | 4th Main Road, Besant Nagar, Chennai 600090 (13.0003, 80.2668) | Cafe, Desserts | 08:00–23:00 |
| Nungambakkam Grill House | Harpreet Singh | `grillhouse.chennai@example.com` | Approved | 18 Khader Nawaz Khan Road, Nungambakkam, Chennai 600006 (13.0608, 80.2497) | North Indian, Tandoor | All day |
| Tambaram Udupi Bhavan | Venkatesh Rao | `udupibhavan.chennai@example.com` | Approved | 8 Velachery Main Road, East Tambaram, Chennai 600073 (12.9226, 80.1333) | South Indian, Breakfast | All day |
| Porur Ambur Biryani Centre | Syed Basha | `amburbiryani.chennai@example.com` | Approved | 62 Mount Poonamallee Road, Porur, Chennai 600116 (13.0335, 80.163) | Biryani, South Indian | All day |
| OMR Spice Route | Aditya Sharma | `spiceroute.chennai@example.com` | Approved | 14 Elcot SEZ Main Road, Sholinganallur, Chennai 600119 (12.9105, 80.2205) | North Indian, Chinese | All day |
| Perungudi Kerala Kitchen | Thomas Varghese | `keralakitchen.chennai@example.com` | Approved | 41 Rajiv Gandhi Salai (OMR), Perungudi, Chennai 600096 (12.957, 80.2437) | Kerala, Seafood | All day |
| Medavakkam Parotta Point | Ganesan Perumal | `parottapoint.chennai@example.com` | Approved | 52 Velachery Main Road, Medavakkam, Chennai 600100 (12.918, 80.189) | South Indian, Chettinad, Street Food | All day |

### Delivery partners (role `DELIVERY_PARTNER`)
All approved, city Chennai. The partner profile keeps the phone, vehicle and licence (it has no home address); the
area is where the seed puts them online.

| Name | Email | Status | Vehicle | Area (latitude, longitude) | Right now |
|---|---|---|---|---|---|
| Suresh Kannan (development) | `dev.delivery@grocery.local` | Approved | Motorcycle TN22EM7302 | Vijaya Nagar, Velachery (12.976, 80.2211) | Free. Delivers the development orders. |
| Senthil Kumar | `senthil.rider.chennai@example.com` | Approved | Motorcycle TN09AB4521 | Pondy Bazaar, T. Nagar (13.0428, 80.2335) | Free. Delivered Lakshmi’s order. |
| Ravi Shankar | `ravi.rider.chennai@example.com` | Approved | Scooter TN07CK8812 | Adyar (13.011, 80.255) | Carrying Divya’s order (assigned, heading to Adyar Chettinad Kitchen). |
| Mohammed Irfan | `irfan.rider.chennai@example.com` | Approved | Motorcycle TN06BZ3307 | Mylapore (13.0352, 80.268) | Carrying Karthik’s order (picked up, on the way). |
| Gopal Iyer | `gopal.rider.chennai@example.com` | Approved | Bicycle | Anna Nagar (13.086, 80.212) | Offline (goes online from his dashboard). |
| Meena Devi | `meena.rider.chennai@example.com` | Approved | Scooter TN22DM1290 | Velachery (12.98, 80.22) | Free. Delivered Sanjay’s grocery order. |
| Vijay Anand | `vijay.rider.chennai@example.com` | Approved | Motorcycle TN01AX7744 | Nungambakkam (13.06, 80.248) | Free. Delivered Fathima’s grocery order. |
| Prabhu Ramasamy | `prabhu.rider.chennai@example.com` | Approved | Motorcycle TN11BQ5528 | Tambaram (12.9255, 80.1185) | Free. Delivered Meenakshi’s grocery order. |
| Kathiravan Murugesan | `kathir.rider.chennai@example.com` | Approved | Scooter TN10AZ3391 | Porur (13.0362, 80.1595) | Carrying Harish’s order (assigned, heading to Porur Family Mart). |
| Dinesh Kumar | `dinesh.rider.chennai@example.com` | Approved | Motorcycle TN22CF7710 | Guindy (13.0082, 80.2128) | Free, no deliveries yet. |
| Saravanan Muthu | `saravanan.rider.chennai@example.com` | Approved | Motorcycle TN14AH8823 | Sholinganallur, OMR (12.9035, 80.2272) | Carrying Rahul’s order (picked up, on the way). |
| Yasmin Banu | `yasmin.rider.chennai@example.com` | Approved | Scooter TN14BK2047 | Perungudi and Thoraipakkam, OMR (12.953, 80.242) | Free. Delivered Nandhini’s restaurant order. |
| Arjun Prakash | `arjun.rider.chennai@example.com` | Approved | Scooter TN11CA6604 | Medavakkam (12.92, 80.187) | Offline. Delivered Balamurugan’s grocery order. |

### Testing the workflow
- **Order placement and nearby search:** sign in as a fresh customer (Deepak in Anna Nagar, Swathi in Guindy, Aishwarya
  on OMR). The header's **Deliver to** shows the default address; change it there (another saved address, the
  device's location or a point on the map) and every page follows. The storefront lists the **grocery stores that
  deliver there** (nearest first, with the road distance checkout uses) and restaurants by distance. "All products",
  search and each store's page (`/stores/<id>`) show only products of stores that deliver to that location, with the
  store's distance on every card; **Show all stores** lifts that. From Meenakshi's Selaiyur home, for example, five
  stores deliver (Tambaram Super Bazaar 4.3 km … Velachery Green Grocers 14.2 km). A store farther than its delivery
  limit is still refused at checkout (`OUT_OF_DELIVERY_AREA`), e.g. Anna Nagar Supermart for Meenakshi.
- **Store confirmation:** accept the new order on the store’s **Orders** board within 5 minutes (the default; admins
  change it under [Platform settings](#platform-settings); it is refused automatically after that), then mark it ready. Once a partner accepts the delivery, the order card shows who
  collects it: name, vehicle, phone (**Call**) and distance from the store (it needs the current delivery-service).
- **Partner assignment:** offers go only to online partners whose position is under 60 seconds old (the default
  location freshness, a platform setting). Sign in as a
  partner near the store in another browser and go online (the phone’s GPS, or the location simulator on the Vite
  dev server), then accept the offer.
- **Live tracking and completion:** pick the order up as the partner and move (or drive the simulator); the customer’s
  order page follows without a refresh. Mark it delivered to finish.
- **Approvals:** every account here is already approved. To test approving one, register a new store, restaurant or
  partner on the sign-up page and approve it as the admin under **Approvals**.
## Product photos for local data
Products created by the smoke tests have no photos. To give each of them a real one, chosen from the product name
(basmati, toor dal, atta, poha, curd, tomato, …):
```bash
bash infrastructure/local/scripts/seed-product-images.sh --dry-run   # shows which photo each product gets
bash infrastructure/local/scripts/seed-product-images.sh
```
- **The photos:** they live in `frontend/grocery-web/public/images/products/` (licenses and authors in `CREDITS.md`
  there) and are served by the frontend at `/images/products/`.
- **What gets changed:** only products without a photo. The script raises each product's snapshot version, then
  republishes the catalog so search picks the photos up.
- **Re-running:** safe; run it again after new smoke runs. Set `PRODUCT_IMAGE_BASE_URL` when the frontend is not on
  `http://localhost:6420`.

## Database backups (daily export and import)
The `db-backup` container exports every service database once a day into `infrastructure/local/backups/`. It starts with
MySQL: `start-all.cmd` starts it, and so does any `docker compose up`.

- **One file per export:** `grocery-<date>_<time>-<kind>.sql.gz`, about 1 MB. Each file holds all databases from one
  consistent moment, so an order, its payment and its delivery always match after an import.
- **When:** daily at `BACKUP_TIME` (default `00:00`, India time). If the machine was off then, the export runs as soon
  as Docker runs again that day. Daily exports older than `BACKUP_KEEP_DAYS` (14) are deleted.
- **Settings:** `BACKUP_TIME`, `BACKUP_TIMEZONE`, `BACKUP_KEEP_DAYS` and `BACKUP_DIR` in `infrastructure/local/.env`.
  The exports hold personal data (names, phones, addresses), so the folder is git-ignored. Keep it out of shared drives.

```bat
rem Export now (kept until you delete it):
infrastructure\local\host-run\db-backup.cmd
rem See the exports:
infrastructure\local\host-run\db-restore.cmd
```

**Import an export:**
1. Stop the Spring Boot services: Ctrl+C in their tabs, or `host-run\stop-all.cmd`. The import refuses to run while a
   service is still connected to a database it replaces.
2. Run the import:
   ```bat
   infrastructure\local\host-run\db-restore.cmd latest
   rem or one export by name:
   infrastructure\local\host-run\db-restore.cmd grocery-2026-11-17_000512-daily.sql.gz
   rem or only some databases (the others keep their current data):
   infrastructure\local\host-run\db-restore.cmd latest --only order_db,payment_db
   ```
   It asks for confirmation (`--yes` skips the question). It saves the current data first as
   `grocery-<now>-before-restore.sql.gz`, so you undo an import by importing that file. Each chosen database is
   replaced completely.
3. Start the services: `host-run\start-all.cmd`.
4. Rebuild search from the imported data: `node infrastructure/local/scripts/rebuild-search.mjs`. This empties the
   search indexes and has each owning service re-send its data. Without this step, search keeps showing the newer data.

**Notes:**
- **Debezium:** the import runs with MySQL's binary log off, so it does not publish old outbox events again.
- **Not in the export:** carts (Redis) and Kafka. Search is rebuilt by step 4.
- **Single databases:** import one database only to undo damage in that service. Other services keep their own view
  of its data (see the AWS runbook's notes in `docs/operations/disaster-recovery.md`).
- **On another machine:** copy the `.sql.gz` file into `infrastructure/local/backups/`, then import it as above. After a
  MySQL volume reset, import before you start the services.

## Observability (Phase 13)
```bash
cd infrastructure/local
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime \
  --profile observability up -d --build
```
- **Metrics:** every service exposes `/actuator/prometheus` on its own port (internal only). The gateway serves its
  actuator only on the internal management port 9080; its public port answers 404 for `/actuator/**`. Business
  metrics include:
  - events published and consumed (`grocery_events_*`) and pending dead letters;
  - stuck orders, open returns and order placement time;
  - payment reconciliation and coupon redemptions;
  - emails, SSE connections, and AI tool calls, approvals and model latency.

  Exporters cover Kafka lag, Kafka Connect / Debezium, MySQL, Redis and Elasticsearch.
- **Traces:** OpenTelemetry, from the gateway through every service and **across Kafka**. The outbox stores the W3C
  `traceparent`, Debezium copies it into a Kafka header, and the consumer continues the trace. One business transaction
  (placing an order, paying, a store accepting …) is one trace in Tempo.
- **Logs:** ECS JSON with `traceId`, `spanId` and `correlationId`, shipped by Alloy to Loki. Every response carries
  `X-Correlation-Id`.
- **Dashboards** (Grafana, folder *Grocery*): Platform overview, Service detail, Business, Kafka & Debezium, Databases &
  caches, Order trace explorer.
- **Alerts:** 17 Prometheus rules → Alertmanager → email to `ops@grocery.local` in Mailpit (e.g. ServiceDown,
  DeadLettersPending, ConnectorTaskFailed, KafkaConsumerLagHigh, HighErrorRate, StuckOrders).
- **Troubleshooting a business transaction**, from an order number to its trace, logs, lag, dead letters and
  connectors: [docs/operations/troubleshooting.md](docs/operations/troubleshooting.md). Stack details:
  [infrastructure/local/observability/README.md](infrastructure/local/observability/README.md).

## Test suites (Phase 13)
All run in Docker from the repository root (Git Bash). The stack-based suites expect the gateway on 8090 here
(`GATEWAY_HOST_PORT=8090`) and the assistant in scripted mode (`AI_PROVIDER=scripted AI_APPROVAL_TTL_CUSTOMER=30s
AI_APPROVAL_EXPIRY_INTERVAL=5s`).

| Suite | Where | Command (details in each README) |
|---|---|---|
| API, security (authorization matrix, IDOR, JWT tampering, header spoofing, rate limits), AI tools and approvals, Kafka event flow | [tests/api-tests](tests/api-tests/README.md) | `mvn -f tests/api-tests/pom.xml verify -DGATEWAY_URL=…` (tags `smoke`, `api`, `security`, `ai`, `kafka`) |
| HTTP contracts (Pact, file-based) | consumers cart + order, 7 providers; files in [contracts/pacts](contracts/pacts) | part of each service's `mvn clean verify` |
| Event consumer contracts | `EventConsumerContractTest` in every consumer service, samples from the `grocery-event-schemas` test-jar | part of each service's `mvn clean verify` |
| Database migrations (from scratch, on top of the last release, expand/contract lint, schema lint) | [tests/db-tests](tests/db-tests/README.md) | `mvn -f tests/db-tests/pom.xml clean verify` |
| End to end (Playwright, 4 journeys) | [tests/e2e](tests/e2e/README.md) | `bash tests/e2e/run-e2e.sh` |
| Performance (k6: browse, shop, checkout) | [tests/performance](tests/performance/README.md) | `bash tests/performance/run-k6.sh browse.js` |
| Security scans (ZAP baseline + API, Trivy fs / images / config) | [tests/security](tests/security/README.md) | `bash tests/security/zap-baseline.sh` … `trivy-config.sh` |

## CI/CD and AWS (Phase 14)
Everything is built and validated **offline**: nothing is applied to an AWS account.
- **AWS target** ([architecture](docs/architecture/10-deployment-architecture.md), [ADR-0012](docs/adr/0012-aws-ecs-fargate-deployment.md)):
  - one ECS Fargate service per microservice, using ECS Service Connect;
  - the SPA on S3 + CloudFront, and an internal ALB for `/api`;
  - RDS for MySQL, MSK with MSK Connect (Debezium), ElastiCache, Elastic Cloud;
  - Secrets Manager;
  - ADOT → Amazon Managed Prometheus, X-Ray, CloudWatch Logs, Amazon Managed Grafana and SNS.
- **Terraform** in [infrastructure/aws](infrastructure/aws/README.md):
  - environments `dev`, `staging`, `prod`, with one service catalog (`services.auto.tfvars.json`);
  - native ECS **blue/green** (canary for the gateway in prod), with automatic rollback on alarms and the circuit
    breaker;
  - offline validation: `bash infrastructure/aws/validate.sh` (fmt, validate, mock-provider plan tests, tflint, Trivy
    config, promtool, ADOT config).
- **Jenkins** in [infrastructure/ci](infrastructure/ci/README.md): a shared library, plus one short Jenkinsfile per
  service, the frontend and each library, so **each builds and deploys alone**.
  - Stages: build and tests → contracts → Trivy dependency, secret and config gate → image (`Dockerfile.service`) +
    SBOM → Trivy image gate → push to ECR → register schemas → deploy dev → smoke tests → approval → staging (API
    tests, E2E, ZAP) → approval → prod (bake time, alarms) → automatic rollback on failure.
  - A local Jenkins and Docker registry validate it, with the AWS calls in `DRY_RUN`:
    `cd infrastructure/ci/jenkins/local && docker compose up -d`, then `bash lint.sh` and
    `bash run-pipeline.sh services/order-service`.
- **Images:** `infrastructure/ci/docker/Dockerfile.service` (JRE, non-root, layered jar, OCI labels) and
  `frontend/grocery-web/Dockerfile.prod` (nginx-unprivileged, SPA fallback, CSP and security headers). Trivy fails a
  build on any fixable HIGH or CRITICAL finding; accepted findings are listed with reasons in `.trivyignore`.
- **Configuration:** each service has an `application-aws.yml` that reads only environment variables. Secrets come
  from Secrets Manager (`grocery/<env>/<service>/<name>`). Flyway migrations stay expand/contract, so blue and green
  can run side by side; `tests/db-tests` enforces this.
- **Runbooks:** [deploy and rollback](docs/operations/deploy-and-rollback.md),
  [disaster recovery](docs/operations/disaster-recovery.md). Manual rollback:
  `infrastructure/ci/scripts/rollback.sh <env> <service>`. The path to Kubernetes:
  [ADR-0014](docs/adr/0014-kubernetes-path.md).

## Event schemas (Avro + Schema Registry)
Every Kafka event is Avro, defined once in [libs/grocery-event-schemas](libs/grocery-event-schemas/) and registered in
the Schema Registry with **BACKWARD_TRANSITIVE** compatibility. See [ADR-0011](docs/adr/0011-avro-and-schema-registry.md).
- `schema-init` registers the schemas on every `docker compose up`, after checking them for compatibility. To register
  them again after changing a schema: `docker compose up schema-init`.
- Browse them at `curl localhost:8081/subjects`. Kafka UI (http://localhost:8070) shows the messages decoded.
- To change a schema, edit the `.avsc` and run the module's build (first line of the test loop in
  [Tests](#tests-run-inside-docker)); its compatibility gate fails on a breaking change. Then rebuild the producing service and run
  `schema-init`.
- Listeners and business code are unchanged: grocery-common turns Avro back into the JSON they always received.

## Tests (run inside Docker)
From the repository root in Git Bash (`MSYS_NO_PATHCONV=1` stops Git Bash rewriting container paths):
```bash
# A function, not a string: the repository path may contain spaces ("Drive E").
m() { MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" \
   -v grocery-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
   -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9.16-eclipse-temurin-25-noble "$@"; }
m mvn -f libs/grocery-event-schemas/pom.xml clean install   # central Avro schemas + compatibility gate + contract test-jar
m mvn -f libs/grocery-common/pom.xml clean install
for s in api-gateway identity-service customer-service vendor-service restaurant-service delivery-service catalog-command-service catalog-query-service inventory-service pricing-service cart-service order-service payment-service notification-service ai-orchestrator-service review-service reporting-service; do
  m mvn -f services/$s/pom.xml clean verify   # unit + integration + Pact + event consumer contract tests
done
m mvn -f tests/db-tests/pom.xml clean verify                 # migrations, expand/contract and schema lint
cd infrastructure/local && MSYS_NO_PATHCONV=1 docker compose exec frontend npm test -- --run
```
Always build with `clean`: an IDE's compiler can leave stub classes in `target/test-classes` that break a non-clean
build. The stack-based suites (API, E2E, performance, security scans) are listed under
[Test suites](#test-suites-phase-13).

## Repository layout
```
docs/                    architecture diagrams, ADRs, event catalog, API contracts (docs/api), runbooks (docs/operations)
infrastructure/local/    docker-compose.yml (+ gpu override), MySQL init, topic/connector/schema/model init, smoke tests,
                         observability/ (Prometheus, alerts, Alertmanager, Grafana dashboards, Loki, Tempo, Alloy, OTel)
infrastructure/aws/      Terraform (bootstrap, shared, dev/staging/prod, 16 modules) + offline validate.sh
infrastructure/ci/       Jenkins shared library, local Jenkins, CI Dockerfiles, deploy / rollback scripts
libs/grocery-common/     separately versioned technical library (outbox, idempotency, security, Kafka, Avro, errors,
                         observability)
libs/grocery-event-schemas/  central Avro schema per event + backward-compatibility gate (+ contract-test test-jar)
services/<service>/      independent Spring Boot apps: own pom, Dockerfile, Jenkinsfile, Flyway, Debezium connector,
                         application-aws.yml, README
frontend/grocery-web/    single React app with lazy-loaded role areas (Dockerfile = dev server, Dockerfile.prod = nginx)
contracts/pacts/         Pact files written by consumers, verified by providers
tests/                   api-tests, db-tests, e2e (Playwright), performance (k6), security (ZAP, Trivy)
```

## Troubleshooting
- **Port already in use** — change the host port in `infrastructure/local/.env` (e.g. `MYSQL_HOST_PORT`). If another
  app holds 8080, set `GATEWAY_HOST_PORT=8090`, then run the smoke scripts with `GATEWAY_URL=http://localhost:8090`.
  The frontend is unaffected because it reaches the gateway inside Docker.
  Likewise `OLLAMA_HOST_PORT=11435` when another Ollama already uses 11434 (the assistant reaches Ollama inside Docker),
  and `ALERTMANAGER_HOST_PORT=9094` when another program holds `127.0.0.1:9093`.
- **The assistant says "offline" for everyone** while Ollama is running: the AI service probes the Ollama URL its
  launcher found when it started (`run-service.cmd` reads the port the `grocery-ollama-1` container published then).
  If Ollama was later recreated on another host port, the probe finds nothing. Keep the port fixed with
  `OLLAMA_HOST_PORT` in `infrastructure/local/.env`, recreate Ollama on it
  (`docker compose --profile ai up -d ollama`), and the assistant is available again within 30 s. Restarting the AI
  service tab also works. Check with `GET /api/ai/status` (`"available": true`).
- **Reach a UI (Grafana, Kafka UI …) from another device:** those ports listen on this machine only. Set
  `BIND_ADDRESS=0.0.0.0` on a trusted network.
- **Reset all local data** — `docker compose --profile all down -v` (deletes MySQL, Kafka, Redis, ES, Ollama volumes).
- **Frontend dependencies changed** — `docker compose rm -sf frontend && docker volume rm grocery_frontend-node-modules`, then `up -d --build frontend`.
- **A profile never appears after registering, or no events flow at all:** run `bash infrastructure/local/scripts/check-connectors.sh`.
  A connector can say RUNNING while its task is FAILED. Restart a failed task with
  `curl -X POST "localhost:8083/connectors/<name>/restart?includeTasks=true&onlyFailed=true"`; outbox rows written in
  the meantime are published then. Also check consumer group lag and the `*.DLT` topics in Kafka UI.
- **Every connector shows `connector UNASSIGNED` (tasks RUNNING) and restarts answer 409:** the Connect worker is stuck
  in a rebalance loop, typically after Docker Desktop froze under memory pressure. Run `docker restart
  grocery-kafka-connect-1`. The connectors resume from their stored offsets, so nothing is lost. The connect-watchdog
  only restarts FAILED tasks and cannot restart the worker itself.
- **An event failed and was dead-lettered:** sign in as an admin and open **Operations → Dead letters**
  (`/admin/operations`). It shows the payload and the exception. Fix the cause, then **Replay** it (SUPER_ADMIN or
  OPS_ADMIN). A replay runs only in the service that failed, and it is idempotent. **Discard** with a reason what
  should never be applied. The same page shows failed connector tasks (the `connect-watchdog` container restarts them
  up to 3 times in 10 minutes; see its log) and stuck orders.
- **`401 TOKEN_REVOKED`** — expected right after an approval/suspension; the app refreshes automatically.
- **503 from one service right after it was recreated:** the gateway caches DNS for at most 5 s
  (`GATEWAY_DNS_CACHE_MAX_TTL`), so this clears within seconds of the new container becoming healthy. If it lasts,
  check the container's health with `docker compose ps`.
