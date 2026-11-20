// Scenario "checkout" (Phase 13 contract §4): place an order, pay through the payment simulator and wait until it is
// CONFIRMED (the Kafka saga: inventory reservation → payment → order), 5 virtual users.
//   docker run --rm --memory 512m --network grocery_default -v "$PWD/tests/performance:/scripts" -w /scripts \
//     grafana/k6:1.8.2 run checkout.js
// Thresholds: p95 < 2 s for placement, < 800 ms for the preview, errors < 1 %, checks > 99 %.
// Custom trend `order_confirmed` = POST /api/orders → status CONFIRMED (end to end, includes the simulator payment).
// Each confirmed order is cancelled afterwards (refunded, stock released) unless CANCEL_AFTER=false.
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { Counter, Trend } from 'k6/metrics'
import { api, json } from './lib/http.js'
import { createWorld } from './lib/setup.js'
import { summarize, TREND_STATS } from './lib/summary.js'

const VUS = Number(__ENV.VUS || 5)
const HOLD = __ENV.DURATION || '2m'
const CANCEL_AFTER = (__ENV.CANCEL_AFTER || 'true') === 'true'

const orderConfirmed = new Trend('order_confirmed', true)
const ordersConfirmed = new Counter('orders_confirmed')
const ordersNotConfirmed = new Counter('orders_not_confirmed')

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: TREND_STATS,
  stages: [
    { duration: '20s', target: VUS },
    { duration: HOLD, target: VUS },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{name:place}': ['p(95)<2000'],
    'http_req_duration{name:preview}': ['p(95)<800'],
    'http_req_duration{name:cart-add}': ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  return createWorld({ customers: VUS, products: 4 })
}

function poll(label, probe, timeoutSeconds) {
  const deadline = Date.now() + timeoutSeconds * 1000
  while (Date.now() < deadline) {
    const value = probe()
    if (value) return value
    sleep(0.25)
  }
  return null
}

export default function (data) {
  const customer = data.customers[(exec.vu.idInTest - 1) % data.customers.length]
  const token = customer.token
  const product = data.products[Math.floor(Math.random() * data.products.length)]

  api('DELETE', '/api/cart', { token, name: 'cart-clear' })
  const added = api('POST', '/api/cart/items', {
    token,
    name: 'cart-add',
    body: { type: 'PRODUCT', productId: product.id, quantity: 1 + Math.floor(Math.random() * 2) },
  })
  check(added, { 'item added': (r) => r.status === 200 || r.status === 201 })

  const preview = api('GET', `/api/cart/preview?addressId=${customer.addressId}`, { token, name: 'preview' })
  const total = json(preview)?.total
  if (!check(preview, { 'preview ready': (r) => r.status === 200 && json(r)?.canCheckout === true })) {
    sleep(1)
    return
  }

  const started = Date.now()
  const placed = api('POST', '/api/orders', {
    token,
    name: 'place',
    body: { addressId: customer.addressId, expectedTotal: total, note: 'k6 checkout' },
    headers: { 'Idempotency-Key': `k6-${data.tag}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}` },
  })
  if (!check(placed, { 'order placed (201)': (r) => r.status === 201 })) {
    sleep(1)
    return
  }
  const orderId = json(placed).id

  const session = poll(
    'checkout session',
    () => api('GET', `/api/payments/orders/${orderId}/checkout`, { token, name: 'checkout-session', expect: [{ min: 200, max: 299 }, 404, 409] }).status === 200,
    20,
  )
  if (!check(session, { 'checkout session ready': (s) => Boolean(s) })) {
    ordersNotConfirmed.add(1)
    return
  }
  const simulated = json(api('POST', `/api/payments/simulator/orders/${orderId}/pay`, { token, name: 'pay', body: { outcome: 'SUCCESS' } }))
  const verified = api('POST', '/api/payments/razorpay/verify', {
    token,
    name: 'verify',
    body: {
      razorpayOrderId: simulated && simulated.razorpayOrderId,
      razorpayPaymentId: simulated && simulated.razorpayPaymentId,
      razorpaySignature: simulated && simulated.razorpaySignature,
    },
  })
  check(verified, { 'payment verified': (r) => r.status === 200 })

  const confirmed = poll(
    'CONFIRMED',
    () => json(api('GET', `/api/orders/${orderId}`, { token, name: 'order-status' }))?.status === 'CONFIRMED',
    30,
  )
  if (check(confirmed, { 'order CONFIRMED': (c) => Boolean(c) })) {
    orderConfirmed.add(Date.now() - started)
    ordersConfirmed.add(1)
  } else {
    ordersNotConfirmed.add(1)
  }

  if (CANCEL_AFTER) {
    const cancelled = api('POST', `/api/orders/${orderId}/cancel`, { token, name: 'cancel', body: { reason: 'k6 load test' } })
    check(cancelled, { 'order cancelled': (r) => r.status === 200 })
  }
  sleep(1)
}

export const handleSummary = summarize('checkout')
