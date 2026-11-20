# Performance tests (k6)

Phase 13 contract §4: three scenarios sized for a laptop, run with the `grafana/k6` image against the running stack.

| Script | Load | What one iteration does | Thresholds |
|---|---|---|---|
| `browse.js` | 50 VUs, anonymous (ramp 30 s, hold `DURATION`, ramp down 15 s) | search (half this run's products, half generic terms) → product page + its rating summary → sometimes the category tree; 1–3 s think time | p95 < 800 ms for `search`, `product`, `product-reviews`, `categories`; errors < 1 %; checks > 99 % |
| `shop.js` | 20 VUs, one signed-in customer each | cart → add an item → checkout preview (cart cleared every 5th iteration); 2–4 s think time | p95 < 800 ms for `cart`, `cart-add`, `cart-clear`, `preview`; errors < 1 %; checks > 99 % |
| `checkout.js` | 5 VUs, one customer each | add to cart → preview → **place** (Idempotency-Key) → wait for the payment session → simulator pay + verify → wait for `CONFIRMED` → cancel (refund, stock released; `CANCEL_AFTER=false` keeps the orders) | p95 < 2 s for `place`, < 800 ms for `preview`, `cart-add`; errors < 1 %; checks > 99 %. Reported (no threshold): `order_confirmed` = place → CONFIRMED |

`lib/setup.js` is the shared setup (k6 `setup()`): one approved store at a random spot with priced products holding
100 000 units, and one customer per VU with an address next to the store — all through the API, every name tagged
with `RUN_ID`. `lib/http.js` wraps the calls (tag `name` per request type, 429 retry); `lib/summary.js` writes the
summary.

## Run (Docker only)

From the repository root, with the stack up (payment simulator on):

```bash
bash tests/performance/run-k6.sh browse.js
bash tests/performance/run-k6.sh shop.js
bash tests/performance/run-k6.sh checkout.js
DURATION=5m VUS=80 bash tests/performance/run-k6.sh browse.js      # bigger / longer
```

which is

```bash
docker run --rm --memory 512m --network grocery_default -v "$PWD/tests/performance:/scripts" -w /scripts \
  -e API_URL=http://api-gateway:8080 grafana/k6:1.8.2 run --no-usage-report browse.js
```

Variables: `API_URL`, `VUS`, `DURATION` (hold time, default `2m`), `RUN_ID`, `PRODUCTS` (browse), `CANCEL_AFTER`
(checkout), `SIMULATE_CLIENT_IPS`, `K6_NETWORK`, `K6_MEMORY`, `ADMIN_EMAIL`/`ADMIN_PASSWORD`.

**Client IPs.** The gateway rate-limits anonymous calls and logins per client IP (`userOrIpKeyResolver` /
`ipKeyResolver`, 20 req/s and 2 logins/s). In AWS every shopper arrives with their own IP through the load balancer;
here every VU would share the k6 container's IP and the test would only measure the rate limiter. With
`SIMULATE_CLIENT_IPS=true` (default) each VU sends its own `X-Forwarded-For`, i.e. k6 plays the load balancer (the
gateway trusts one proxy hop). Signed-in calls are limited per user, so `shop`/`checkout` use one customer per VU.

## Results

- stdout: per request name p95 / p99 / avg / max / count, the custom trends, and every threshold PASS/FAIL;
- `reports/<scenario>-summary.json`: the full k6 summary (all metrics, thresholds), for CI artifacts and comparisons;
- exit code 99 when a threshold fails (k6's convention) — the Phase 14 pipeline fails the stage on it.

**Prometheus (remote write).** With the `observability` profile running, stream the metrics into Prometheus and see
them next to the services' own dashboards:

```bash
PROMETHEUS_RW=http://prometheus:9090/api/v1/write bash tests/performance/run-k6.sh checkout.js
# = k6 run -o experimental-prometheus-rw with K6_PROMETHEUS_RW_SERVER_URL, K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max
```

Prometheus must accept remote writes (`--web.enable-remote-write-receiver`); the metrics arrive as `k6_*`
(e.g. `k6_http_req_duration_p95{name="search"}`).

## Last local run (2026-11-13, development laptop, whole stack in one Docker Desktop VM, other test suites running)

| Scenario | Iterations | Requests (rate incl. setup) | Errors | Checks | p95 / p99 per request | Thresholds |
|---|---|---|---|---|---|---|
| browse (50 VUs, 171 s) | 3 304 | 10 823 (63/s) | 0 % | 100 % | search 35 / 100 ms · product 21 / 57 ms · product-reviews 21 / 40 ms · categories 33 / 99 ms | all PASS |
| shop (20 VUs, 173 s) | 756 | 2 569 (15/s) | 0 % | 100 % | cart 49 / 68 ms · cart-add 77 / 100 ms · preview 56 / 88 ms · cart-clear 25 / 55 ms | all PASS |
| checkout (5 VUs, 158 s) | 234 orders | 3 527 (22/s) | 0 % | 100 % | place 173 / 216 ms · cart-add 82 / 93 ms · preview 56 / 79 ms · `order_confirmed` 2.25 s / 2.63 s | all PASS |

The first `shop` run of the day failed (errors 2.33 %, checks 96.2 %, cart p99 1.78 s): for ~10 s pricing-service did not
answer cart-service's `POST /internal/prices/quote` in time ("Request cancelled"), cart-service's `pricing` circuit
breaker opened (137 warnings 17:12:37–17:12:48) and add-to-cart/preview failed fast with PRICING_UNAVAILABLE — while
other agents' builds and API tests loaded the same laptop. The rerun was clean. Watch `grocery_*` / Hikari / JVM
pause metrics of pricing-service when it recurs.
