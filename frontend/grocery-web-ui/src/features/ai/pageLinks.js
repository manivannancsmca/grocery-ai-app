import { matchPath } from 'react-router'

/** @typedef {import('./api').PageLink} PageLink */
/** @typedef {import('./api').ToolActivity} ToolActivity */

/**
 * The pages each assistant may link to: the routes of its users' area (`src/roles/<role>/routes.jsx`). The server
 * builds its links in `link/Pages.java`; keep the three in step. A link to any other path, or to another website, is
 * shown as plain text, so an answer can never send the user somewhere unexpected.
 */
/**
 * The customer area: the shopping, payment and customer support assistants all serve customers.
 * @type {readonly string[]}
 */
const CUSTOMER_PAGES = [
  '/customer/home',
  '/search',
  '/products/:productId',
  '/restaurants',
  '/restaurants/:restaurantId',
  '/cart',
  '/checkout',
  '/orders',
  '/orders/:orderId',
  '/orders/:orderId/review',
  '/account/profile',
  '/account/addresses',
  '/account/reviews',
  '/account/returns',
  '/account/complaints',
  '/assistant/approvals',
  '/notifications',
]

/** @type {Record<string, readonly string[]>} */
const AGENT_PAGES = {
  shopping: CUSTOMER_PAGES,
  payment: CUSTOMER_PAGES,
  support: CUSTOMER_PAGES,
  'admin-bi': [
    '/admin',
    '/admin/approvals',
    '/admin/price-approvals',
    '/admin/vendors',
    '/admin/restaurants',
    '/admin/delivery-partners',
    '/admin/customers',
    '/admin/users',
    '/admin/catalog',
    '/admin/orders',
    '/admin/payments',
    '/admin/deliveries',
    '/admin/operations',
    '/admin/ai/approvals',
    '/admin/ai/audit',
    '/admin/reviews',
    '/admin/returns',
    '/admin/coupons',
    '/admin/purchase-orders',
    '/admin/reports',
    '/notifications',
  ],
  vendor: [
    '/vendor',
    '/vendor/profile',
    '/vendor/products',
    '/vendor/products/:productId',
    '/vendor/inventory',
    '/vendor/pricing',
    '/vendor/orders',
    '/vendor/reports',
    '/vendor/reviews',
    '/vendor/returns',
    '/vendor/coupons',
    '/vendor/suppliers',
    '/vendor/purchase-orders',
    '/vendor/purchase-orders/:purchaseOrderId',
    '/vendor/assistant',
    '/notifications',
  ],
  restaurant: [
    '/restaurant',
    '/restaurant/profile',
    '/restaurant/menu',
    '/restaurant/orders',
    '/restaurant/reports',
    '/restaurant/reviews',
    '/restaurant/returns',
    '/restaurant/coupons',
    '/restaurant/assistant',
    '/notifications',
  ],
  // Delivery partners have their own pages; delivery managers work in the admin area's delivery monitor.
  delivery: [
    '/delivery',
    '/delivery/profile',
    '/delivery/history',
    '/delivery/assistant',
    '/admin/deliveries',
    '/notifications',
  ],
}

/** How many links fit under one answer. */
export const MAX_ANSWER_LINKS = 4

/** `[label](/path)` in the model's text. */
export const MARKDOWN_LINK = /\[([^\]\n]{1,80})\]\(([^)\s]{1,300})\)/g

/**
 * True when `path` is one of the assistant's own pages (a query string is fine; another site never is).
 * @param {string} agent
 * @param {string} path
 * @returns {boolean}
 */
export function isAgentPage(agent, path) {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) return false
  const pathname = path.split(/[?#]/)[0] ?? ''
  return (AGENT_PAGES[agent] ?? []).some((pattern) => matchPath({ path: pattern, end: true }, pathname) !== null)
}

/**
 * The `links` of a stream event: only well-formed `{ label, path }` entries.
 * @param {unknown} value
 * @returns {PageLink[]}
 */
export function toPageLinks(value) {
  if (!Array.isArray(value)) return []
  return value.flatMap((entry) => {
    if (typeof entry !== 'object' || entry === null) return []
    const { label, path } = entry
    return typeof label === 'string' && label && typeof path === 'string' && path ? [{ label, path }] : []
  })
}

/**
 * The links shown under an answer: the pages its successful tool calls pointed to, each page once and at most
 * {@link MAX_ANSWER_LINKS}, leaving out pages the answer already links to in its text and pages that are not the
 * assistant's own.
 * @param {string} agent
 * @param {readonly ToolActivity[]} tools
 * @param {string} text
 * @returns {PageLink[]}
 */
export function answerLinks(agent, tools, text) {
  const seen = new Set([...text.matchAll(MARKDOWN_LINK)].map((match) => match[2]))
  const links = []
  for (const tool of tools) {
    if (tool.status !== 'SUCCEEDED') continue
    for (const link of tool.links ?? []) {
      if (seen.has(link.path) || !isAgentPage(agent, link.path)) continue
      seen.add(link.path)
      links.push(link)
    }
  }
  return links.slice(0, MAX_ANSWER_LINKS)
}
