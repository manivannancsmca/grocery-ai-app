import { httpClient } from '../../shared/api/httpClient'

/**
 * Health payload of `GET /api/health/{service}` (contract §6).
 * @typedef {object} HealthResponse
 * @property {'UP' | 'DOWN' | (string & {})} status
 */

/** Services the gateway exposes health for, in display order. */
export const HEALTH_SERVICES = [
  'gateway',
  'identity',
  'customer',
  'vendor',
  'restaurant',
  'delivery',
  'catalog-command',
  'catalog-query',
  'inventory',
  'pricing',
  'cart',
  'order',
  'payment',
  'notification',
  'review',
  'reporting',
]
/** @typedef {(typeof HEALTH_SERVICES)[number]} HealthService */

/**
 * Display name and purpose of each service (System status; Phase 10 Operations names the dead letters' service).
 * @type {Record<HealthService, { name: string, description: string }>}
 */
export const SERVICE_LABELS = {
  gateway: { name: 'API gateway', description: 'Routing, JWT validation, rate limits' },
  identity: { name: 'Identity', description: 'Accounts, login, tokens' },
  customer: { name: 'Customer', description: 'Profiles and addresses' },
  vendor: { name: 'Vendor', description: 'Grocery vendor onboarding' },
  restaurant: { name: 'Restaurant', description: 'Restaurant onboarding, menus, accepting orders' },
  delivery: { name: 'Delivery', description: 'Delivery partners' },
  'catalog-command': { name: 'Catalog (command)', description: 'Categories, brands and vendor products' },
  'catalog-query': { name: 'Catalog (query)', description: 'Search, suggestions, product and restaurant pages' },
  inventory: { name: 'Inventory', description: 'Stock levels, adjustments and reservations' },
  pricing: { name: 'Pricing', description: 'Grocery prices, price approvals and quotes' },
  cart: { name: 'Cart', description: 'Carts and the checkout preview' },
  order: { name: 'Order', description: 'Orders, their status history, cancellations and order stats' },
  payment: { name: 'Payment', description: 'Razorpay payments, the payment window and refunds' },
  notification: { name: 'Notification', description: 'Notification inbox and live updates (SSE)' },
  review: { name: 'Reviews', description: 'Ratings and reviews, moderation and store replies' },
  reporting: { name: 'Reporting', description: 'Admin reports built from events (sales, deliveries, promotions)' },
}

/**
 * Health of one service through the API gateway. Public endpoint: no token (an expired token must not
 * turn the check into a 401). `503 { "status": "DOWN" }` is a valid answer, not a failure.
 * @param {HealthService} service
 * @returns {Promise<HealthResponse>}
 */
export async function fetchServiceHealth(service) {
  const response = await httpClient.get(`/health/${service}`, {
    skipAuth: true,
    validateStatus: (status) => status === 200 || status === 503,
  })
  return response.data
}

/**
 * Identity service health — the storefront / dashboard connectivity card.
 * @returns {Promise<HealthResponse>}
 */
export function fetchIdentityHealth() {
  return fetchServiceHealth('identity')
}
