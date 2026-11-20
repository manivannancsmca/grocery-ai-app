# End-to-end UI journeys (Playwright)

Phase 13 contract §4 (`docs/api/phase-13-observability-and-testing.md`): the four user journeys, driven in Chromium
against the running local stack. Data that is not the subject of a journey (approved stores, stocked products,
partners, paid orders, an admin's AI proposal) is created through the API gateway, like the smoke tests in
`infrastructure/local/scripts`. Every run uses unique names, e-mails, coupon codes and a random map location, so runs
never collide and can be repeated.

| Spec | Journey |
|---|---|
| `tests/01-customer-shop.spec.ts` | **Customer:** registers, adds an address (form), searches, adds 2 to the cart, applies the store coupon at checkout, places the order, pays in the payment-simulator dialog, and sees the order change live when the store accepts it (SSE, no reload). |
| `tests/02-fulfil-deliver-rate.spec.ts` | **Vendor** accepts, packs and marks the order ready on the order board · **partner** goes online (browser geolocation at the store), accepts the offer, switches to the location simulator, picks up, drives towards the customer and delivers · **customer** follows it live (status + live tracking map) and rates the product, the store and the delivery. Three browser contexts side by side. |
| `tests/03-admin.spec.ts` | **Admin** approves a vendor's application, opens Operations → dead letters, decides an AI approval (a four-eyes *suspend vendor* proposed by an OPS_ADMIN through the scripted assistant) and opens the reports. |
| `tests/04-assistant.spec.ts` | **Customer assistant** (`AI_PROVIDER=scripted`): "add 2 sona masuri …" fills the cart, "checkout" shows the approval card (nothing placed yet), "Confirm order" places exactly one order. Skipped when the orchestrator runs another model. |

## Run (Docker only)

From the repository root, with the stack up (`frontend` and `core` … `assistant` profiles, payment simulator,
`AI_PROVIDER=scripted`):

```bash
bash tests/e2e/run-e2e.sh                          # all journeys (≈ 3–4 min on a laptop)
bash tests/e2e/run-e2e.sh tests/02-fulfil-deliver-rate.spec.ts
E2E_RETRIES=1 bash tests/e2e/run-e2e.sh            # CI: one retry, flaky tests show as "flaky" in the report
```

`run-e2e.sh` is a `docker run` of `mcr.microsoft.com/playwright:v1.64.1-noble` (same version as `@playwright/test` in
`package.json` — bump both together) on the compose network `grocery_default`:

```bash
docker run --rm --init --ipc=host --memory 2g --network grocery_default \
  -v "$PWD/tests/e2e:/work" -v grocery-e2e-node-modules:/work/node_modules -w /work \
  -e APP_URL=http://localhost:6420 -e FRONTEND_UPSTREAM=frontend:6420 -e API_URL=http://api-gateway:8080 \
  mcr.microsoft.com/playwright:v1.64.1-noble sh -c 'npm ci && npx playwright test'
```

| Variable | Default (in `run-e2e.sh`) | Meaning |
|---|---|---|
| `APP_URL` | `http://localhost:6420` | the frontend as the browser sees it |
| `FRONTEND_UPSTREAM` | `frontend:6420` | where `scripts/tcp-forward.mjs` forwards `APP_URL`'s port; empty = no forwarder (the browser reaches `APP_URL` itself) |
| `API_URL` | `http://api-gateway:8080` | the gateway, for API fixtures |
| `E2E_WORKERS` / `E2E_RETRIES` | `1` / `0` | parallel workers / retries |
| `E2E_NETWORK` | `grocery_default` | docker network to join |
| `DOCKER` | `docker` | docker command (e.g. a wrapper that throttles heavy containers) |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | bootstrap admin | the SUPER_ADMIN used by fixtures and journey 3 |

**Why the forwarder:** the browser must open `http://localhost:6420` — Vite's dev server rejects other Host names,
the gateway's CORS allow-list is `http://localhost:6420`, and the `Secure` refresh cookie is only kept over plain HTTP
for `localhost`. Inside the container, `scripts/tcp-forward.mjs` (Playwright's `webServer`) listens on
`localhost:6420` and pipes the bytes to `frontend:6420`. Against a built frontend behind a real host name (Phase 14),
set `APP_URL` to it and `FRONTEND_UPSTREAM=` (empty).

## Results

- `playwright-report/index.html` — HTML report (`npx playwright show-report playwright-report`);
- `test-results/results.json`, `test-results/junit.xml` — for CI;
- `test-results/artifacts/<test>/` — on failure: screenshot of every open page, video, `trace.zip`
  (`npx playwright show-trace …`) and `error-context.md`.

## Notes and known issues

- **First click on the vendor/restaurant order board is lost** (frontend bug, reported): `StoreOrderAlertsProvider`
  unlocks audio on the first capture-phase `pointerdown` and removes the "Click to enable sound alerts" button from the
  page header; the board moves between pointerdown and pointerup, so that first click never reaches its button.
  Journey 2 clicks the page title first and says so in a comment — remove that line when the bug is fixed.
- The rating page names the delivery target "Delivery partner" (review-service's `DELIVERY_PARTNER_NAME`), not the
  rider's first name the frontend's unit-test fixtures use; journey 2 follows the real API.
- Selectors are the accessible roles and names the frontend's Vitest tests use (`getByRole`, `getByLabel`).
- Type-check: `docker run --rm -v "$PWD/tests/e2e:/work" -v grocery-e2e-node-modules:/work/node_modules -w /work
  node:24.22.2-alpine3.23 sh -c "npm ci && npx tsc --noEmit"`.
