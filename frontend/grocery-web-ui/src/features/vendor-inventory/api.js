import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/** List filter of `GET /api/vendor/inventory` (Phase 4 contract §2). */
export const STOCK_FILTERS = ['LOW_STOCK', 'OUT_OF_STOCK', 'IN_STOCK']
/** @typedef {(typeof STOCK_FILTERS)[number]} StockFilter */

/** @type {Record<StockFilter, string>} */
export const STOCK_FILTER_LABELS = {
  LOW_STOCK: 'Low stock',
  OUT_OF_STOCK: 'Out of stock',
  IN_STOCK: 'In stock',
}

/**
 * @param {unknown} value
 * @returns {value is StockFilter}
 */
export function isStockFilter(value) {
  return typeof value === 'string' && STOCK_FILTERS.includes(value)
}

export const ADJUSTMENT_TYPES = ['INCREASE', 'DECREASE', 'SET']
/** @typedef {(typeof ADJUSTMENT_TYPES)[number]} AdjustmentType */

/** @type {Record<AdjustmentType, string>} */
export const ADJUSTMENT_TYPE_LABELS = {
  INCREASE: 'Increase',
  DECREASE: 'Decrease',
  SET: 'Set counted stock',
}

export const ADJUSTMENT_REASONS = ['RESTOCK', 'RETURN', 'DAMAGED', 'EXPIRED', 'STOCK_COUNT', 'CORRECTION', 'OTHER']
/** @typedef {(typeof ADJUSTMENT_REASONS)[number]} AdjustmentReason */

/** @type {Record<AdjustmentReason, string>} */
export const ADJUSTMENT_REASON_LABELS = {
  RESTOCK: 'Restock (new delivery)',
  RETURN: 'Customer return',
  DAMAGED: 'Damaged',
  EXPIRED: 'Expired',
  STOCK_COUNT: 'Stock count',
  CORRECTION: 'Correction',
  OTHER: 'Other',
}

/**
 * Movements also record what orders do to the stock (from Phase 6).
 * @typedef {AdjustmentType | 'RESERVE' | 'RELEASE' | 'COMMIT'} MovementType
 */

/** @type {Record<MovementType, string>} */
export const MOVEMENT_TYPE_LABELS = {
  INCREASE: 'Increase',
  DECREASE: 'Decrease',
  SET: 'Stock count',
  RESERVE: 'Reserved for an order',
  RELEASE: 'Reservation released',
  COMMIT: 'Sent with an order',
}

/**
 * @param {string} type
 * @returns {string}
 */
export function movementTypeLabel(type) {
  return type in MOVEMENT_TYPE_LABELS ? MOVEMENT_TYPE_LABELS[type] : type
}

/**
 * @param {string} reason
 * @returns {string}
 */
export function adjustmentReasonLabel(reason) {
  return reason in ADJUSTMENT_REASON_LABELS ? ADJUSTMENT_REASON_LABELS[reason] : reason
}

/** Limits from the contract: quantities are whole sellable units. */
export const STOCK_LIMITS = {
  /** INCREASE / DECREASE: 1 to this. */
  adjustQuantity: 100_000,
  /** SET, and the most that can ever be on hand (422 STOCK_LIMIT_EXCEEDED). */
  onHand: 1_000_000,
  /** Low-stock threshold: 0 (alerts off) to this. */
  threshold: 100_000,
  note: 300,
}

/**
 * `AdjustmentRequest`.
 * @typedef {object} AdjustmentRequest
 * @property {AdjustmentType} type
 * @property {number} quantity
 * @property {AdjustmentReason} reason
 * @property {string | null} note
 */

/**
 * `StockView` — one product's stock. `available = onHand - reserved`.
 * @typedef {object} StockView
 * @property {string} productId
 * @property {string} productName
 * @property {string} unitLabel
 * @property {string | null} sku
 * @property {'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | (string & {})} productStatus
 * @property {number} onHand
 * @property {number} reserved
 * @property {number} available
 * @property {number} lowStockThreshold 0 = no low-stock alerts.
 * @property {boolean} lowStock
 * @property {boolean} inStock
 * @property {string} updatedAt
 */

/**
 * `StockMovementView` — one ledger entry, newest first.
 * @typedef {object} StockMovementView
 * @property {string} id
 * @property {MovementType | (string & {})} type
 * @property {number} quantityDelta Change of on-hand stock (0 for RESERVE / RELEASE).
 * @property {number} onHandAfter
 * @property {number} reservedAfter
 * @property {AdjustmentReason | (string & {}) | null} reason
 * @property {string | null} note
 * @property {string | null} reference Order id for RESERVE / RELEASE / COMMIT.
 * @property {string | null} actorUserId
 * @property {string} createdAt
 */

/**
 * @typedef {object} InventorySummary
 * @property {number} products
 * @property {number} inStock
 * @property {number} lowStock Low but not out.
 * @property {number} outOfStock
 */

/**
 * @typedef {object} InventoryListParams
 * @property {string} [q] Product name contains / SKU equals.
 * @property {StockFilter} [filter]
 * @property {number} [page]
 * @property {number} [size]
 */

export const INSUFFICIENT_STOCK = 'INSUFFICIENT_STOCK'
export const STOCK_BELOW_RESERVED = 'STOCK_BELOW_RESERVED'
export const STOCK_LIMIT_EXCEEDED = 'STOCK_LIMIT_EXCEEDED'

export const MOVEMENTS_PAGE_SIZE = 10

export const inventoryKeys = {
  all: ['vendor', 'inventory'],
  list: (params) => [...inventoryKeys.all, 'list', params],
  summary: () => [...inventoryKeys.all, 'summary'],
  movements: (productId, page) => [...inventoryKeys.all, 'movements', productId, page],
}

const base = '/vendor/inventory'
/** @param {string} productId */
const item = (productId) => `${base}/${segment(productId)}`

/**
 * @param {InventoryListParams} params
 * @returns {Promise<PageResponse<StockView>>}
 */
export async function listInventory(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/** @returns {Promise<InventorySummary>} */
export async function fetchInventorySummary() {
  return (await httpClient.get(`${base}/summary`)).data
}

/**
 * 409 INSUFFICIENT_STOCK / STOCK_BELOW_RESERVED (with `available` and `reserved`), 422 STOCK_LIMIT_EXCEEDED.
 * @param {string} productId
 * @param {AdjustmentRequest} body
 * @returns {Promise<StockView>}
 */
export async function adjustStock(productId, body) {
  return (await httpClient.post(`${item(productId)}/adjustments`, body)).data
}

/**
 * @param {string} productId
 * @param {number} lowStockThreshold
 * @returns {Promise<StockView>}
 */
export async function setLowStockThreshold(productId, lowStockThreshold) {
  return (await httpClient.put(`${item(productId)}/threshold`, { lowStockThreshold })).data
}

/**
 * @param {string} productId
 * @param {number} page
 * @returns {Promise<PageResponse<StockMovementView>>}
 */
export async function listStockMovements(productId, page) {
  return (await httpClient.get(`${item(productId)}/movements`, { params: { page, size: MOVEMENTS_PAGE_SIZE } })).data
}

/**
 * One row of `POST /api/vendor/inventory/adjustments/bulk`: an `AdjustmentRequest` for one product.
 * @typedef {AdjustmentRequest & { productId: string }} BulkAdjustmentItem
 */

/** Contract: 1–100 items per bulk request, each product at most once. */
export const BULK_MAX_ITEMS = 100

/**
 * All or nothing: one failing item fails the whole request and nothing changes. The error names the item in its
 * `productId` property (409 INSUFFICIENT_STOCK / STOCK_BELOW_RESERVED, 422 STOCK_LIMIT_EXCEEDED, 404 PRODUCT_NOT_FOUND, …).
 * Resolves with the new stock of every item, in request order.
 * @param {BulkAdjustmentItem[]} items
 * @returns {Promise<StockView[]>}
 */
export async function bulkAdjustStock(items) {
  return (await httpClient.post(`${base}/adjustments/bulk`, { items })).data
}
