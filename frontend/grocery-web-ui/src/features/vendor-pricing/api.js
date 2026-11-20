import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */

/** List filter of `GET /api/vendor/prices` (Phase 4 contract §3). */
export const PRICE_FILTERS = ['PRICED', 'UNPRICED', 'PENDING_APPROVAL']
/** @typedef {(typeof PRICE_FILTERS)[number]} PriceFilter */

/** @type {Record<PriceFilter, string>} */
export const PRICE_FILTER_LABELS = {
  PRICED: 'Priced',
  UNPRICED: 'Unpriced',
  PENDING_APPROVAL: 'Pending approval',
}

/**
 * @param {unknown} value
 * @returns {value is PriceFilter}
 */
export function isPriceFilter(value) {
  return typeof value === 'string' && PRICE_FILTERS.includes(value)
}

export const PRICE_CHANGE_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']
/** @typedef {(typeof PRICE_CHANGE_STATUSES)[number]} PriceChangeStatus */

/** @type {Record<PriceChangeStatus, string>} */
export const PRICE_CHANGE_STATUS_LABELS = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
}

/** @type {Record<PriceChangeStatus, BadgeTone>} */
export const PRICE_CHANGE_STATUS_TONES = {
  PENDING: 'amber',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'gray',
}

/**
 * @param {unknown} value
 * @returns {value is PriceChangeStatus}
 */
export function isPriceChangeStatus(value) {
  return typeof value === 'string' && PRICE_CHANGE_STATUSES.includes(value)
}

/** INR with 2 decimals, `1.00 ≤ sellingPrice ≤ mrp ≤ 1000000.00`; notes and reasons ≤ 300 characters. */
export const PRICE_LIMITS = { min: 1, max: 1_000_000, decimals: 2, note: 300 }

/** Default of `grocery.pricing.approval-threshold-percent`; every PriceView carries the live value. */
export const DEFAULT_APPROVAL_THRESHOLD_PERCENT = 20

/**
 * The live price of a product.
 * @typedef {object} LivePrice
 * @property {number} mrp
 * @property {number} sellingPrice
 * @property {string} currency
 * @property {number} discountPercent `round((mrp − sellingPrice) / mrp × 100)`, 0–99.
 * @property {string} updatedAt
 */

/**
 * `PriceChangeRequestView` — a change bigger than the threshold, waiting for (or decided by) an admin.
 * @typedef {object} PriceChangeRequestView
 * @property {string} id
 * @property {string} productId
 * @property {string} productName
 * @property {string} unitLabel
 * @property {string} vendorId
 * @property {string | null} [vendorName] Admin list only, when known.
 * @property {number | null} currentMrp
 * @property {number | null} currentSellingPrice
 * @property {number} requestedMrp
 * @property {number} requestedSellingPrice
 * @property {number} changePercent Selling price change, signed, 2 decimals.
 * @property {PriceChangeStatus | (string & {})} status
 * @property {string | null} note
 * @property {string} requestedBy
 * @property {string} requestedAt
 * @property {string | null} decidedBy
 * @property {string | null} decidedAt
 * @property {string | null} decisionReason
 */

/** @typedef {'APPLIED' | 'PENDING_APPROVAL'} PriceOutcome */

/**
 * `PriceView` — one product's price; `price` null = no price yet.
 * @typedef {object} PriceView
 * @property {string} productId
 * @property {string} productName
 * @property {string} unitLabel
 * @property {string | null} sku
 * @property {'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | (string & {})} productStatus
 * @property {LivePrice | null} price
 * @property {PriceChangeRequestView | null} pendingRequest
 * @property {number} approvalThresholdPercent
 * @property {PriceOutcome} [outcome] Only in the PUT response.
 */

/**
 * `PriceRequest`.
 * @typedef {object} PriceRequest
 * @property {number} mrp
 * @property {number} sellingPrice
 * @property {string | null} note
 */

/**
 * `PriceHistoryEntry` — one change of the live price, newest first.
 * @typedef {object} PriceHistoryEntry
 * @property {string} id
 * @property {number | null} oldMrp null for the first price.
 * @property {number | null} oldSellingPrice
 * @property {number} newMrp
 * @property {number} newSellingPrice
 * @property {number | null} changePercent
 * @property {'VENDOR' | 'APPROVED_REQUEST' | (string & {})} source
 * @property {string | null} requestId
 * @property {string | null} changedBy
 * @property {string} changedAt
 */

/**
 * @typedef {object} PricingSummary
 * @property {number} products
 * @property {number} priced
 * @property {number} unpriced
 * @property {number} pendingApproval
 */

/**
 * @typedef {object} VendorPriceListParams
 * @property {string} [q]
 * @property {PriceFilter} [filter]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} PriceRequestListParams
 * @property {PriceChangeStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

export const SELLING_PRICE_ABOVE_MRP = 'SELLING_PRICE_ABOVE_MRP'
export const PRICE_CHANGE_PENDING = 'PRICE_CHANGE_PENDING'
export const PRICE_UNCHANGED = 'PRICE_UNCHANGED'
export const PRICE_CHANGE_NOT_PENDING = 'PRICE_CHANGE_NOT_PENDING'

export const HISTORY_PAGE_SIZE = 10

export const vendorPriceKeys = {
  all: ['vendor', 'prices'],
  list: (params) => [...vendorPriceKeys.all, 'list', params],
  summary: () => [...vendorPriceKeys.all, 'summary'],
  detail: (productId) => [...vendorPriceKeys.all, 'detail', productId],
  history: (productId, page) => [...vendorPriceKeys.all, 'history', productId, page],
  requests: (params) => [...vendorPriceKeys.all, 'requests', params],
}

const base = '/vendor/prices'
/** @param {string} productId */
const item = (productId) => `${base}/${segment(productId)}`

/**
 * @param {VendorPriceListParams} params
 * @returns {Promise<PageResponse<PriceView>>}
 */
export async function listVendorPrices(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/** @returns {Promise<PricingSummary>} */
export async function fetchPricingSummary() {
  return (await httpClient.get(`${base}/summary`)).data
}

/**
 * @param {string} productId
 * @returns {Promise<PriceView>}
 */
export async function fetchVendorPrice(productId) {
  return (await httpClient.get(item(productId))).data
}

/**
 * 200 `APPLIED` (live now) or 202 `PENDING_APPROVAL` (an admin decides; the live price is unchanged).
 * 422 SELLING_PRICE_ABOVE_MRP, 409 PRICE_CHANGE_PENDING / PRICE_UNCHANGED / PRODUCT_ARCHIVED.
 * @param {string} productId
 * @param {PriceRequest} body
 * @returns {Promise<PriceView & { outcome: PriceOutcome }>}
 */
export async function setVendorPrice(productId, body) {
  const response = await httpClient.put(item(productId), body)
  const outcome = response.data.outcome ?? (response.status === 202 ? 'PENDING_APPROVAL' : 'APPLIED')
  return { ...response.data, outcome }
}

/**
 * @param {string} productId
 * @param {number} page
 * @returns {Promise<PageResponse<PriceHistoryEntry>>}
 */
export async function listPriceHistory(productId, page) {
  return (await httpClient.get(`${item(productId)}/history`, { params: { page, size: HISTORY_PAGE_SIZE } })).data
}

/**
 * @param {PriceRequestListParams} params
 * @returns {Promise<PageResponse<PriceChangeRequestView>>}
 */
export async function listMyPriceRequests(params) {
  return (await httpClient.get('/vendor/price-change-requests', { params: cleanParams(params) })).data
}

/**
 * 409 PRICE_CHANGE_NOT_PENDING when it was decided (or cancelled) meanwhile.
 * @param {string} id
 * @returns {Promise<PriceChangeRequestView>}
 */
export async function cancelPriceRequest(id) {
  return (await httpClient.post(`/vendor/price-change-requests/${segment(id)}/cancel`)).data
}
