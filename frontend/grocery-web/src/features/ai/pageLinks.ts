import { matchPath } from 'react-router'
import type { PageLink, ToolActivity } from './api'

/**
 * The pages each assistant may link to: the routes of its users' area (`src/roles/<role>/routes.tsx`). The server
 * builds its links in `link/Pages.java`; keep the three in step. A link to any other path, or to another website, is
 * shown as plain text, so an answer can never send the user somewhere unexpected.
 */
/** The customer area: the shopping, payment and customer support assistants all serve customers. */
const CUSTOMER_PAGES: readonly string[] = [
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

const AGENT_PAGES: Record<string, readonly string[]> = {
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
  delivery: ['/delivery', '/delivery/profile', '/delivery/history', '/delivery/assistant', '/admin/deliveries', '/notifications'],
}

/** How many links fit under one answer. */
export const MAX_ANSWER_LINKS = 4

/** `[label](/path)` in the model's text. */
export const MARKDOWN_LINK = /\[([^\]\n]{1,80})\]\(([^)\s]{1,300})\)/g

/** True when `path` is one of the assistant's own pages (a query string is fine; another site never is). */
export function isAgentPage(agent: string, path: string): boolean {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) return false
  const pathname = path.split(/[?#]/)[0] ?? ''
  return (AGENT_PAGES[agent] ?? []).some((pattern) => matchPath({ path: pattern, end: true }, pathname) !== null)
}

/** The `links` of a stream event: only well-formed `{ label, path }` entries. */
export function toPageLinks(value: unknown): PageLink[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((entry: unknown) => {
    if (typeof entry !== 'object' || entry === null) return []
    const { label, path } = entry as Record<string, unknown>
    return typeof label === 'string' && label && typeof path === 'string' && path ? [{ label, path }] : []
  })
}

/**
 * The links shown under an answer: the pages its successful tool calls pointed to, each page once and at most
 * {@link MAX_ANSWER_LINKS}, leaving out pages the answer already links to in its text and pages that are not the
 * assistant's own.
 */
export function answerLinks(agent: string, tools: readonly ToolActivity[], text: string): PageLink[] {
  const seen = new Set([...text.matchAll(MARKDOWN_LINK)].map((match) => match[2]))
  const links: PageLink[] = []
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
