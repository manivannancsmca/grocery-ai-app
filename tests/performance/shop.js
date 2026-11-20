// Scenario "shop" (Phase 13 contract §4): signed-in customers fill their cart and open the checkout preview, 20 VUs.
//   docker run --rm --memory 512m --network grocery_default -v "$PWD/tests/performance:/scripts" -w /scripts \
//     grafana/k6:1.8.2 run shop.js
// Thresholds: p95 < 800 ms for the cart calls and the preview, errors < 1 %, checks > 99 %.
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { api, json } from './lib/http.js'
import { createWorld } from './lib/setup.js'
import { summarize, TREND_STATS } from './lib/summary.js'

const VUS = Number(__ENV.VUS || 20)
const HOLD = __ENV.DURATION || '2m'

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: TREND_STATS,
  stages: [
    { duration: '20s', target: VUS },
    { duration: HOLD, target: VUS },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{name:cart}': ['p(95)<800'],
    'http_req_duration{name:cart-add}': ['p(95)<800'],
    'http_req_duration{name:cart-clear}': ['p(95)<800'],
    'http_req_duration{name:preview}': ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  // one customer per VU: the gateway limits each user to 20 requests/s, like a real shopper's session
  return createWorld({ customers: VUS, products: 8 })
}

export default function (data) {
  const customer = data.customers[(exec.vu.idInTest - 1) % data.customers.length]
  const token = customer.token
  const product = data.products[Math.floor(Math.random() * data.products.length)]

  if (exec.vu.iterationInScenario % 5 === 0) {
    const cleared = api('DELETE', '/api/cart', { token, name: 'cart-clear' })
    check(cleared, { 'cart cleared': (r) => r.status === 200 || r.status === 204 })
  }

  const cart = api('GET', '/api/cart', { token, name: 'cart' })
  check(cart, { 'cart 200': (r) => r.status === 200 })
  sleep(0.5 + Math.random())

  const added = api('POST', '/api/cart/items', { token, name: 'cart-add', body: { type: 'PRODUCT', productId: product.id, quantity: 1 } })
  check(added, { 'item added': (r) => r.status === 200 || r.status === 201 })
  sleep(0.5 + Math.random())

  const preview = api('GET', `/api/cart/preview?addressId=${customer.addressId}`, { token, name: 'preview' })
  const canCheckout = check(preview, {
    'preview 200': (r) => r.status === 200,
    'preview can check out': (r) => json(r)?.canCheckout === true,
  })
  if (!canCheckout && exec.vu.iterationInScenario < 50) {
    console.warn(`preview blocked: ${JSON.stringify(json(preview)?.issues ?? preview.body).slice(0, 300)}`)
  }
  sleep(1 + Math.random())
}

export const handleSummary = summarize('shop')
