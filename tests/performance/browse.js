// Scenario "browse" (Phase 13 contract §4): anonymous search and product pages, 50 virtual users.
//   docker run --rm --memory 512m --network grocery_default -v "$PWD/tests/performance:/scripts" -w /scripts \
//     grafana/k6:1.8.2 run browse.js
// Thresholds: p95 < 800 ms for search (and the product page reads), errors < 1 %, checks > 99 %.
import { check, group, sleep } from 'k6'
import { api, json } from './lib/http.js'
import { createWorld } from './lib/setup.js'
import { summarize, TREND_STATS } from './lib/summary.js'

const VUS = Number(__ENV.VUS || 50)
const HOLD = __ENV.DURATION || '2m'
const GENERIC_TERMS = ['rice', 'dal', 'atta', 'oil', 'masala', 'tomato', 'milk', 'sugar']

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: TREND_STATS,
  stages: [
    { duration: '30s', target: VUS },
    { duration: HOLD, target: VUS },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{name:search}': ['p(95)<800'],
    'http_req_duration{name:product}': ['p(95)<800'],
    'http_req_duration{name:product-reviews}': ['p(95)<800'],
    'http_req_duration{name:categories}': ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  const world = createWorld({ customers: 0, products: Number(__ENV.PRODUCTS || 12) })
  return { tag: world.tag, products: world.products }
}

const pick = (list) => list[Math.floor(Math.random() * list.length)]

export default function (data) {
  group('search', () => {
    // half the searches hit this run's products, half the whole catalogue
    const term = Math.random() < 0.5 ? `${pick(data.products).word} ${data.tag}` : pick(GENERIC_TERMS)
    const response = api('GET', `/api/search/products?q=${encodeURIComponent(term)}&page=0&size=24`, { name: 'search' })
    check(response, {
      'search 200': (r) => r.status === 200,
      'search has a page': (r) => Array.isArray(json(r)?.content),
    })
  })
  sleep(0.5 + Math.random())

  group('product page', () => {
    const product = pick(data.products)
    const page = api('GET', `/api/catalog/products/${product.id}`, { name: 'product' })
    check(page, { 'product 200': (r) => r.status === 200, 'product in stock': (r) => json(r)?.inStock === true })
    const reviews = api('GET', `/api/reviews/summary?targetType=PRODUCT&targetId=${product.id}`, { name: 'product-reviews' })
    check(reviews, { 'reviews summary 200': (r) => r.status === 200 })
  })
  sleep(0.5 + Math.random())

  if (Math.random() < 0.25) {
    const categories = api('GET', '/api/catalog/categories', { name: 'categories' })
    check(categories, { 'categories 200': (r) => r.status === 200 })
    sleep(0.5)
  }
}

export const handleSummary = summarize('browse')
