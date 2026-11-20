# grocery-web — FreshCart frontend

One React application with a lazily loaded dashboard area per stakeholder: Customer, Vendor, Restaurant Owner, Delivery Partner and Admin. See `DEVELOPMENT_PLAN.md` §8 at the repo root for the architecture.

**Phase 2 status (identity & stakeholders)** — built against `docs/api/phase-2-identity-and-stakeholders.md`:
- real sign-in / registration (role picker), silent session restore, sign-out, "account suspended" screen
- route guards (`RequireAuth`, `RequireRole`, `GuestOnly`, `HomeGate`) and role-based navigation with a user menu
- customer profile and delivery addresses (CRUD, default, max 10, "use my current location")
- vendor / restaurant / delivery-partner onboarding (save draft, submit for approval, status banner)
- admin: approvals queue, vendor / restaurant / delivery-partner directories (suspend, reinstate, history),
  users (suspend, reactivate, create admin), customers, and a System Status page

## Stack

React 19 · TypeScript (strict) · Vite 8 · Tailwind CSS 4 · React Router 7 · TanStack Query 5 · axios · React Hook Form + Zod · Vitest + Testing Library · ESLint (flat config)

Versions are pinned exactly in `package.json`, and `package-lock.json` is committed.

## Run it — Docker only (no Node.js on the host)

The intended way is through the root Docker Compose setup (service `frontend`, profile `frontend`). It runs this Dockerfile, bind-mounts the source for hot reload, and proxies `/api` to the gateway container:

```bash
docker compose -f infrastructure/local/docker-compose.yml --profile frontend up -d
# open http://localhost:6420
```

Standalone, from the repo root:

```bash
docker build -t grocery-web-dev frontend/grocery-web
docker run --rm -p 6420:6420 \
  -e VITE_PROXY_TARGET=http://host.docker.internal:8080 \
  -e VITE_USE_POLLING=true \
  -v "$(pwd)/frontend/grocery-web:/app" -v grocery-web-node-modules:/app/node_modules \
  grocery-web-dev
```

To run npm commands (tests, lint, adding a package), use a throwaway Node container that shares the same `node_modules` volume. Git Bash on Windows needs `MSYS_NO_PATHCONV=1` in front:

```bash
docker run --rm -v "$(pwd)/frontend/grocery-web:/app" -v grocery-web-node-modules:/app/node_modules \
  -w /app node:24.22.2-alpine3.23 npm test -- --run
```

> **IDE type-checking:** `node_modules` lives in a Docker volume, so the editor can't see it. If you want editor IntelliSense, run `npm ci` once with only the bind mount (no volume). That fills `node_modules/` on the host; it is git-ignored.

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server on `0.0.0.0:6420` with hot reload; `/api/**` proxied to the gateway |
| `npm run build` | Type-check (`tsc -b`) and production build into `dist/` |
| `npm run preview` | Serve the production build on port 4173 |
| `npm test` | Vitest in watch mode (`npm test -- --run` for a single run) |
| `npm run lint` | ESLint (TypeScript, React Hooks, React Refresh rules) |

## Environment variables (dev server)

| Variable | Default | Purpose |
|---|---|---|
| `VITE_PROXY_TARGET` | `http://localhost:8080` | Where the dev server forwards `/api/**`. Use `http://api-gateway:8080` inside Compose. |
| `VITE_USE_POLLING` | unset (`false`) | Set to `true` in containers on Windows/WSL bind mounts, which don't deliver file-change events. |
| `VITE_DEV_ACCOUNTS` | unset (shown) | `false` hides the "Dev only" accounts section on the sign-in screen. |

The browser always calls relative `/api/...` URLs, so there is no CORS setup in dev, and production serves the SPA and gateway on one origin.

## Authentication

- **Access token in memory only** (`src/shared/auth/session.ts`), never in localStorage. The refresh token is the httpOnly `grocery_rt` cookie (Path `/api/auth`); JavaScript never sees it.
- **On start** the app calls `POST /api/auth/refresh` (credentials + `X-Requested-With: XMLHttpRequest`). A valid cookie restores the session, otherwise the visitor is signed out. Guarded pages show a loader until this finishes.
- **axios interceptor** (`src/shared/api/httpClient.ts`): attaches `Authorization: Bearer …`. On `401` (`TOKEN_EXPIRED`, `TOKEN_REVOKED`, `INVALID_TOKEN`, `UNAUTHENTICATED`) it joins **one shared refresh** and retries the request once. `409 REFRESH_RACE` retries the refresh once. When the refresh fails the session ends and the guards send the user to `/login`, remembering the page (`state.from`). `403 ACCOUNT_SUSPENDED` shows `/account-suspended`.
- After a vendor or restaurant is **approved** the dashboard refreshes the token so it carries `vendor_id` / `restaurant_id` (retried a few times, because identity learns about approvals from Kafka).
- Admin write actions are hidden for admin roles without permission (`src/shared/auth/permissions.ts`, copied from the contract); the backend enforces them regardless.
- **Sign-in screen** (`src/features/auth`): one form for every stakeholder, with a tab per stakeholder (Customer, Vendor, Restaurant, Delivery, Admin) that tailors the brand panel and the sign-up link. `/login?role=VENDOR` (or the page the visitor was sent from) picks the tab. Registration and the suspended screen share the layout (`AuthShell.tsx`).
- **Dev accounts** (dev server only): under the form, a "Dev only" section lists the seeded sign-ins of the selected stakeholder (`devAccounts.ts`, created by identity-service's `DevAccountSeeder` when `DEV_SEED_ENABLED=true`); clicking one fills in the form. `DevAccountsPanel` is loaded only when `import.meta.env.DEV` is true, so a production build contains neither the section nor the credentials.

## Real-time updates (Phase 7)

Built against `docs/api/phase-7-real-time-updates.md` and ADR-0005 (SSE).

- **Stream client** (`src/shared/realtime/sseClient.ts`): `fetch` + `ReadableStream` (EventSource cannot send `Authorization`), an incremental SSE parser (`sseParser.ts`), `Authorization: Bearer`, `Accept: text/event-stream` and `Last-Event-ID` (the last notification id, kept per user in memory and sessionStorage, so a reload resumes). Reconnects with exponential backoff + ±20 % jitter (1 s → 30 s; a `retry:` from the server replaces the 1 s base; reset after a healthy minute), a 45 s heartbeat watchdog, `401` → the app's shared refresh → reconnect. `session` events: `TOKEN_EXPIRED` → refresh + reconnect at once, `REVOKED` → the "account suspended" flow, `REPLACED` → banner "Live updates are open in another tab" with **Use this tab**.
- **Hidden tabs:** the stream stays open while the tab is hidden (badge and toasts stay current; the server caps a user at 5 streams). When the tab becomes visible or the browser comes back online, a pending backoff wait is skipped.
- **RealtimeProvider** (`src/features/realtime/`, mounted in `app/providers.tsx`, connects only while signed in): connection status for the UI (`useRealtimeStatus` / `useRealtimeLive`), notification → inbox cache + unread badge + toast (at most 3 per 10 s, none while `/notifications` is open), signal → **one central invalidation map** (`invalidation.ts`, unit-tested) using the feature modules' query keys; invalidations are batched for 150 ms so a replay refetches each query once.
- **UI:** header bell for every role (unread badge, connection dot, latest 10, mark read, mark all, "View all"), `/notifications` inbox (All / Unread, paging), "Live activity" on the admin dashboard.
- **Polling** is only a fallback now: the order page (30 s), the admin order / payment monitors (30 s), the dashboard stats (60 s) and the unread badge (60 s) poll only while the stream is not live.

## Delivery (Phase 9)

Built against `docs/api/phase-9-delivery.md`. Maps use plain Leaflet (BSD-2-Clause; no React wrapper) + OpenStreetMap tiles (`src/shared/components/map/`, loaded lazily in their own chunk, `L.divIcon` markers moved with `setLatLng`, OSM attribution always visible).

- **Partner app** (`/delivery`, `src/features/deliveries/`): Online switch, live location (`watchPosition` → `PUT …/me/location` at most every 5 s, re-sent after 25 s of silence so the partner never goes stale; stops when offline, on unmount and on `409 PARTNER_OFFLINE`), offers with a countdown, the active delivery in two steps, and `/delivery/history` (outcome chips All / Delivered / Not delivered and period chips All time / Today / Last 7 days / Last 30 days, India-time days, kept in the URL as `?status=&period=` and sent to the list and the export; the overview tiles always show today and the last 7 days).
- **Navigation:** each step of the active delivery has a "Navigate to …" link (Google Maps directions to the restaurant/store, then to the customer's drop point; the coordinates leave the app only when the partner taps it). The map draws the route (straight lines: partner → pickup → customer) and starts from the partner's last known position until the phone's first GPS fix.
- **Partner performance** (dashboard, `src/features/deliveries/PartnerPerformance.tsx`): today / this week / this month / custom days (India time, in the URL), from `GET /api/delivery/stats` (the partner's own data). Headline tiles (estimated earnings, delivered, success rate, average delivery time, distance, online time), deliveries by day (columns), how trips ended and how offers were answered (donuts), estimated earnings by day (area), where the delivery time goes (bars) and online time by day. Earnings are an estimate from delivery-service's configurable pay rate (`DELIVERY_PAY_BASE`, `DELIVERY_PAY_PER_KM`); online time is recorded from delivery-service V8 on.
- **Location simulator** exists only in development (Vite dev server) and in builds made with `VITE_LOCATION_SIMULATOR=true` (test servers); `?simulate=1` switches it on where it exists. A production build never offers it, so a partner's position always comes from the phone's GPS. Set the position by clicking the map or with "At the store" / "At the customer", and "Drive" moves it in a straight line, one step every 2 s. It feeds the same reporter as the GPS, so a desktop far from the test data can play a partner.
- **Customer tracking** on `/orders/:orderId` while the order is `trackable`: the `DELIVERY_LOCATION` signal moves the partner in the cached tracking view (no refetch per ping; the ETA is recomputed with the contract's formula). The map shows the restaurant (food orders) or store, the home, the partner and the route; before a partner is assigned it already shows pickup → home. The badge follows the real stream state (Live / Reconnecting… / "Updates every 30 s" while polling), a position older than 2 minutes is called out, and after pickup the distance left is shown.
- **Choosing where to deliver** (restaurant discovery, `/restaurants` and the storefront strip): the customer's default address, "Use my location" (browser geolocation), or "Choose location" — a saved address or a point tapped on the map (guests too). That is also the way out when location access is refused. A hand-picked location travels in the URL as `?lat=&lng=&near=<name>`.
- **Admin delivery monitor** (`/admin/deliveries`): filters, offers history, reassign (SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER).
- Tests never load Leaflet: `src/test/setup.ts` mocks `leaflet` (`src/test/leafletMock.ts`); `src/test/fakeGeolocation.ts` scripts `navigator.geolocation`.

## Operations (Phase 10)

Built against `docs/api/phase-10-event-driven-hardening.md` (`src/features/operations/`). Every admin may look; replay, discard, replay all and connector restart are for SUPER_ADMIN and OPS_ADMIN (permissions `deadLetters.manage`, `connectors.restart`).

- **Operations** (`/admin/operations`, `?tab=dead-letters|connectors|stuck-orders`):
  - **Dead letters** of the 13 consuming services (`/api/admin/ops/<service>/dead-letters`), fetched in parallel and merged newest first; an unreachable service is named and the rest still show. Filters in the URL (service, statuses — default Pending + Replay failed, one request per status — event type, consumer group). Drawer with the decoded payload, exception, folded stack trace and bookkeeping; Replay, Discard (reason ≤ 300) and Replay all (per service, shows `{attempted, replayed, failed}`); `409 DEAD_LETTER_CLOSED` is explained with its `currentStatus` and refreshed. Polled every 30 s.
  - **Connectors**: each Debezium connector and task state, the failed task's trace, Restart (polled every 15 s). The same panel is on the System status page.
  - **Stuck orders** (`GET /api/admin/orders/stuck`) with a link into the order drawer (`/admin/orders?q=…&order=<id>`); refreshed by `ADMIN_ORDERS_CHANGED`.
- **Dashboard**: "Stuck orders" and "Pipeline" (connectors healthy? PENDING dead letters across services) cards.
- **Order drawer**: an "Events" tab with the order's event log (published / consumed, applied / ignored with the guard's reason / duplicate).
- **Payments**: a reconciliation summary (last run, checked, recovered, failures, last recovered payments).
- **Notifications**: `STUCK_ORDER` (hourglass icon, "Stuck order"; opens the order's drawer; refreshes the admin order keys).

## Platform settings

Built against `docs/api/platform-settings.md` (`src/features/platform-settings/`, page `/admin/settings`). Every admin may look (`settings.view`); SUPER_ADMIN and OPS_ADMIN may save (`settings.manage`), everyone else sees the page read-only.

- One card per area, each loaded from its own service (`GET /api/admin/settings/{area}`): a service that is down shows an error in its card only.
- Each field shows its unit, the default ("Default: 48 h"), a "Changed" marker for an override, a reset-to-default button and the bounds as validation messages (checked before sending, the same as the server's). Each card shows its last change and saves on its own (`PUT` with `expectedVersion`; a field that is not overridden is sent as `null`, so it keeps following the server's config).
- Saving updates the card at once and rolls back on an error. `409 SETTINGS_CHANGED` keeps the edits and offers **Reload**; `400` field errors show under their fields.
- **History** on each card (every admin) opens a drawer with `GET /api/admin/settings/{area}/history`, 20 per page: one block per save (who, when), each field with its label and old → new value with the unit, **Default** for `null`; loading, empty and error states of its own. A save refreshes it (`settingsKeys.history`).

## Agentic AI and approvals (Phase 11)

Built against `docs/api/phase-11-agentic-ai-and-approvals.md` (`src/features/ai/`). The AI never decides anything: actions with money or operational impact become approval requests a person confirms.

- **Assistant panel** ("Ask AI" launcher on every page, `AssistantProvider` in the layout) for customers (`shopping`) and admins (`admin-bi`); the agent comes from `GET /api/ai/agents` (no agent → no launcher). Conversations (new / open / delete), example prompts, and an offline state from `GET /api/ai/status` (`available: false`) that disables sending while the rest of the app works.
- **Streaming** (`chatStream.ts`): `POST /api/ai/agents/{agent}/chat` read from the `fetch` body with the shared SSE parser (Bearer token, one refresh-and-retry on 401, cancel with **Stop** / AbortController). Events: `conversation`, `tool` (chips per call; BLOCKED / FAILED styled with their reason), `approval` (inline card), `token` (text appended as it arrives), `done`, `error` (friendly message). `429 AI_RATE_LIMITED` explains the wait (Retry-After). A successful `addToCart` / `updateCartItem` refreshes the cart.
- **Model text is never HTML:** `FormattedText` keeps line breaks and builds only `**bold**` and bullet lines as React elements.
- **Approval cards** (`ApprovalCard.tsx`, also in the drawers): server-rendered title, lines, amount, fingerprint, the assistant's note, expiry countdown and status. A customer's own order action: **Confirm order** / **Confirm cancellation** (approve) or **Not now** (reject with that comment); an eligible admin: **Approve** (optional comment) / **Reject** (comment required); the requester: **Cancel request**. After approval: the placed order with its link (cart and orders refreshed) or the failure. `403 FOUR_EYES_REQUIRED` and `409 APPROVAL_NOT_PENDING` (`currentStatus`) are explained and the card is re-read.
- **Pages:** customers `/assistant/approvals` ("Assistant actions": waiting for you + history, `?id=`); admins `/admin/ai/approvals` (To decide / All with a status filter, drawer, `?id=`) and `/admin/ai/audit` (summary of the day, tool calls filtered by assistant, tool, status, user and dates; blocked attempts highlighted). The admin dashboard has an **AI insights** card (Generate → `InsightReport`, labelled "Rules fallback" when the model was down).
- **Notifications:** `AI_APPROVAL_REQUESTED`, `AI_APPROVAL_DECIDED`, `AI_ACTION_COMPLETED`, `AI_ACTION_FAILED` (labels, icons, links to the approval pages); the `AI_APPROVALS_CHANGED` signal refreshes the approval lists and the open cards.
- Tests mock the chat with `src/test/mockChatStream.ts` (an in-memory `text/event-stream` behind `fetch`, layered over `mockEventStream`).

## Data exports (CSV, Excel, PDF)

Built against `docs/api/data-exports.md` (`src/shared/export/`). Every list page of every stakeholder has an **Export** button in its header (or above its table): CSV, Excel or PDF of **every** row matching the page's filters, not just the page on screen. The services build the file from the list's own query and access rules; the page only sends its filters.

- **`ExportButton`** (`path` = the list path + `/export`, `params` = the list's filters, `total` = its `totalElements`): a menu of the three formats with what the file will hold ("All 1,234 rows that match your filters"); PDF is disabled over 5,000 rows. A table sorted by a column the server can order by (`sortable`, the column keys) exports in that order (`sort=total,desc`); otherwise the menu says the file keeps the list's own order. `DataTable` takes `sort` / `onSortChange` so a page knows its sort.
- **`ExportProvider`** (in `AppProviders`): the download goes through the shared client (Bearer token and refresh), keeps going while the user moves between pages, shows a progress card under the top bar (received size, rows, **Cancel**), then saves the file (`Content-Disposition` name) with a toast "Orders exported · orders-2026-11-15-1030.csv · 1,234 rows". A refusal shows the server's reason (`EXPORT_TOO_LARGE`, `EXPORT_BUSY`…) read from the Blob error body.
- **Admin → Reports**: every section has the same button (`ReportExportButton` in `features/admin-reports/ReportSections.tsx`): the report endpoint itself with `format`, the period and the section's filters (`scopeNote` says which dates). Excel and PDF have headings in words and the period on top; CSV keeps the reports' field names.
- Tests: `src/shared/export/ExportButton.test.tsx`, `src/roles/admin/pages/AdminOrdersExport.test.tsx`, `src/roles/admin/pages/AdminReports.test.tsx` (`mockApi` replies can carry `headers`).

## Folder structure

```
src/
├── app/                 # App.tsx, router.tsx, providers.tsx, queryClient.ts
│   ├── layout/          # AppLayout (header, sidebar, mobile menu), RoleNav, UserMenu, navigation config
│   └── pages/           # NotFound, RouteError
├── shared/
│   ├── api/             # httpClient (Bearer + refresh-on-401), ProblemDetail → ApiError, PageResponse, retry policy
│   ├── auth/            # session store, AuthProvider/useAuth, guards, roles, permissions, auth API
│   ├── realtime/        # SSE parser + client, stream message types, realtime context and hooks
│   ├── components/      # ErrorBoundary, toast/, ReasonDialog, ProfileGate, CoordinatesFields,
│   │                    # ui/ primitives (Button, Card, Input/Select/Textarea with date & time pickers, FormField,
│   │                    #     Dialog, ConfirmDialog, Tabs, Badge, Alert, DataTable, Pagination, StatCard,
│   │                    #     FeatureCard, LinkCard, SummaryStrip, TagInput, DescriptionList, …),
│   │                    # charts/ (TrendChart line/area/column, DonutChart, BarList, Meter)
│   ├── forms/           # Zod validators mirroring the contract regexes, server field-error mapping
│   ├── hooks/           # useDebouncedValue, useCurrentPosition (geolocation)
│   ├── icons/           # the app's colourful filled icons: <Icon name="…" />, art/ (shape data), iconMarkup (map markers)
│   └── utils/
├── features/            # One API module (types copied from the contract) + hooks + components per service
│   ├── auth/            # Login, Register (role picker), Account suspended
│   ├── customer/        # profile + addresses
│   ├── vendor/  restaurant/  delivery/   # onboarding forms
│   ├── stakeholders/    # shared lifecycle types, API factories, onboarding layout, admin/ (queue, directory, drawer)
│   ├── users/           # admin user management
│   ├── notifications/   # inbox API, bell, /notifications page (Phase 7)
│   ├── realtime/        # RealtimeProvider, event dispatcher, invalidation map (Phase 7)
│   ├── deliveries/      # partner app: availability, location reporter + simulator, offers, active delivery (Phase 9)
│   ├── order-tracking/  # customer live tracking (Phase 9)
│   ├── admin-deliveries/ # admin delivery monitor and reassign (Phase 9)
│   ├── operations/      # dead letters, Debezium connectors, stuck orders, dashboard cards (Phase 10)
│   ├── ai/              # assistant panel + chat stream, approval cards, insights, AI audit API (Phase 11)
│   └── system/          # health checks (GET /api/health/{service})
├── roles/               # One folder per stakeholder: routes.tsx (guarded) + pages/, lazily loaded
│   ├── customer/  vendor/  restaurant/  delivery/  admin/
└── test/                # Vitest setup, in-memory axios adapter (mockApi), session and render helpers
```

**Conventions**
- Server data goes through TanStack Query with the shared `httpClient`; query keys live next to each API module and mutations invalidate them.
- Forms use React Hook Form + Zod. Server `errors[]` (`VALIDATION_FAILED`, `PROFILE_INCOMPLETE`) are placed on the matching fields.
- Every API failure is an `ApiError`: kind, HTTP status, `errorCode`, field errors, ProblemDetail and correlation ID.
- Each role area only imports from `shared/` and `features/`, so it can later be extracted into its own app.
- Icons come from `shared/icons` only: `<Icon name="cart" />`. Pictures are multi-colour filled art in the Tailwind palette and ignore `text-*` classes; control glyphs (`arrowLeft/Right`, `chevronRight`, `plus`, `check`, `checkAll`, `close`, `menu`) are solid and follow the text colour. On a solid colour (an active nav item, a selected chip) put the picture on a small white chip. New art goes in `shared/icons/art/`; `icons.test.tsx` enforces the colour rules.
- One design system, for every role: build pages from `shared/components/ui` (a `PageHeader` with the page's menu icon, `Card`/`FeatureCard`/`LinkCard` with an `icon`, `StatCard` tiles inside a `<dl>`, `DataTable` + `Pagination`) rather than hand-styled boxes. `<Input type="date" | "time" | "datetime-local">` gets the app's popover picker on top of the native input (forms, validation, phones and tests keep the native behaviour). Motion lives in `index.css` (`animate-fade-up`, `animate-pop-in`, chart entrances) and switches off with reduced motion; entrance animations must end at `transform: none` so fixed popovers inside them stay anchored.
- The layout uses the full browser width (`PAGE_FRAME` in `AppLayout.tsx`); pages don't cap their own width, except centred error / not-found messages, the sign-in card and print layouts. `<Select>` stays a native `<select>` (forms, keyboard, tests); where the browser supports customizable select (`appearance: base-select`, Chromium) its open list is the app's own rounded panel (`.select-modern` in `index.css`), elsewhere the browser's list opens under the styled field. Everything clickable shows the pointer cursor (a base rule in `index.css`).
- Charts come from `shared/components/charts` (no chart library): one y-axis, the colour-blind-checked palette in `chartTheme.ts` assigned in order, a legend for two or more series, hover and arrow-key readouts, and the numbers always available without hovering (a legend with values, a table next to it, or `BarList` values). Use a `DonutChart` only for one whole in six or fewer parts; rank a top-N with `BarList`; show one ratio with `Meter`.
- Tests never touch the network: `src/test/mockApi.ts` swaps the axios adapter for an in-memory router, and `src/test/mockEventStream.ts` replaces `fetch` with an in-memory SSE server (installed for every test; call it again to push events).
