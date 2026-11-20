import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
import { toPage } from '../../shared/api/pages'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Vendor purchasing & supply (vendor-service — Phase 12 contract §4): the vendor's suppliers and purchase orders.
 * Receiving goods publishes `GoodsReceived`; inventory-service then adds the quantities to stock (once per
 * receipt, even when the request is retried with the same `Idempotency-Key`).
 */

export const PO_STATUSES = ['DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED', 'CLOSED']
/** @typedef {(typeof PO_STATUSES)[number]} PurchaseOrderStatus */

/**
 * @param {unknown} value
 * @returns {value is PurchaseOrderStatus}
 */
export function isPurchaseOrderStatus(value) {
  return typeof value === 'string' && PO_STATUSES.includes(value)
}

/** Limits from the contract. */
export const PURCHASING_LIMITS = {
  supplierName: 120,
  supplierAddress: 300,
  supplierNotes: 500,
  leadTimeDaysMax: 90,
  quantityMax: 100_000,
  poNotes: 500,
  receiptNote: 300,
  /** Lines per purchase order (a sane UI limit; the contract sets none). */
  linesMax: 100,
}

export const PURCHASING_ERRORS = {
  supplierInUse: 'SUPPLIER_IN_USE',
  invalidTransition: 'INVALID_STATUS_TRANSITION',
  receiptExceedsOrdered: 'RECEIPT_EXCEEDS_ORDERED',
}

/**
 * `SupplierView`.
 * @typedef {object} SupplierView
 * @property {string} id
 * @property {string} name
 * @property {string | null} contactName
 * @property {string | null} phone
 * @property {string | null} email
 * @property {string | null} gstin
 * @property {string | null} address Free text, ≤ 300.
 * @property {number | null} leadTimeDays 0–90.
 * @property {string | null} notes
 * @property {boolean} active
 * @property {string} [createdAt]
 * @property {string} [updatedAt]
 */

/**
 * @typedef {object} SupplierRequest
 * @property {string} name
 * @property {string | null} contactName
 * @property {string | null} phone
 * @property {string | null} email
 * @property {string | null} gstin
 * @property {string | null} address
 * @property {number | null} leadTimeDays
 * @property {string | null} notes
 */

/**
 * One line of a purchase order. `productName` is a snapshot taken when the line was saved.
 * @typedef {object} PurchaseOrderLine
 * @property {string} productId
 * @property {string} productName
 * @property {number} orderedQuantity 1–100000.
 * @property {number} unitCost ≥ 0, 2 decimals.
 * @property {number} receivedQuantity
 * @property {number} [lineTotal] `orderedQuantity × unitCost` (computed here when absent).
 */

/**
 * @typedef {object} ReceiptLine
 * @property {string} productId
 * @property {string | null} [productName]
 * @property {number} quantity
 */

/**
 * One goods receipt (a purchase order can have several).
 * @typedef {object} ReceiptView
 * @property {string} id
 * @property {string} receivedAt
 * @property {string | null} note
 * @property {ReceiptLine[]} lines
 */

/**
 * `PurchaseOrderView`.
 * @typedef {object} PurchaseOrderView
 * @property {string} id
 * @property {string} number `PO-XXXXXXXX`.
 * @property {string} [vendorId]
 * @property {string | null} [vendorName] Admin list: the vendor's business name.
 * @property {string} supplierId
 * @property {string | null} [supplierName]
 * @property {PurchaseOrderStatus | (string & {})} status
 * @property {string | null} expectedAt The day the goods are expected (`YYYY-MM-DD`), or null.
 * @property {string | null} notes
 * @property {PurchaseOrderLine[]} lines
 * @property {number} [totalCost] Sum of the line totals (computed here when absent).
 * @property {string} [currency]
 * @property {ReceiptView[]} [receipts]
 * @property {string} createdAt
 * @property {string | null} [updatedAt]
 * @property {string | null} [sentAt]
 */

/**
 * @typedef {object} PurchaseOrderLineRequest
 * @property {string} productId
 * @property {number} orderedQuantity
 * @property {number} unitCost
 */

/**
 * @typedef {object} PurchaseOrderRequest
 * @property {string} supplierId
 * @property {string | null} expectedAt
 * @property {string | null} notes
 * @property {PurchaseOrderLineRequest[]} lines
 */

/**
 * @typedef {object} ReceiveRequest
 * @property {{ productId: string, quantity: number }[]} lines
 * @property {string} [note]
 */

/**
 * @typedef {object} PurchaseOrderListParams
 * @property {PurchaseOrderStatus} [status]
 * @property {string} [supplierId]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} AdminPurchaseOrderListParams
 * @property {string} [vendorId]
 * @property {PurchaseOrderStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {'send' | 'cancel' | 'close'} PurchaseOrderTransition */

export const purchasingKeys = {
  all: ['purchasing'],
  suppliers: () => [...purchasingKeys.all, 'suppliers'],
  orders: () => [...purchasingKeys.all, 'orders'],
  orderList: (params) => [...purchasingKeys.orders(), 'list', params],
  order: (id) => [...purchasingKeys.orders(), 'detail', id],
  admin: () => [...purchasingKeys.all, 'admin'],
  adminList: (params) => [...purchasingKeys.admin(), params],
}

/**
 * Units still to come for one line (never negative).
 * @param {Pick<PurchaseOrderLine, 'orderedQuantity' | 'receivedQuantity'>} line
 * @returns {number}
 */
export function openQuantity(line) {
  return Math.max(0, line.orderedQuantity - (line.receivedQuantity ?? 0))
}

/**
 * @param {Pick<PurchaseOrderLine, 'orderedQuantity' | 'unitCost' | 'lineTotal'>} line
 * @returns {number}
 */
export function lineTotal(line) {
  return line.lineTotal ?? Math.round(line.orderedQuantity * line.unitCost * 100) / 100
}

/**
 * @param {Pick<PurchaseOrderView, 'lines' | 'totalCost'>} order
 * @returns {number}
 */
export function orderTotal(order) {
  return order.totalCost ?? Math.round(order.lines.reduce((sum, line) => sum + lineTotal(line), 0) * 100) / 100
}

/**
 * @param {PurchaseOrderView} order
 * @returns {PurchaseOrderView}
 */
function normaliseOrder(order) {
  return {
    ...order,
    lines: (order.lines ?? []).map((line) => ({ ...line, receivedQuantity: line.receivedQuantity ?? 0 })),
    receipts: order.receipts ?? [],
  }
}

const suppliersBase = '/vendor/suppliers'
const ordersBase = '/vendor/purchase-orders'

/**
 * Every supplier of the vendor (active and deactivated), as a list or a page.
 * @returns {Promise<SupplierView[]>}
 */
export async function listSuppliers() {
  const data = (await httpClient.get(suppliersBase, { params: { size: 200 } })).data
  return toPage(data).content
}

/**
 * @param {SupplierRequest} body
 * @returns {Promise<SupplierView>}
 */
export async function createSupplier(body) {
  return (await httpClient.post(suppliersBase, body)).data
}

/**
 * @param {string} id
 * @param {SupplierRequest} body
 * @returns {Promise<SupplierView>}
 */
export async function updateSupplier(id, body) {
  return (await httpClient.put(`${suppliersBase}/${segment(id)}`, body)).data
}

/**
 * Deactivates the supplier; 409 SUPPLIER_IN_USE while it has open purchase orders.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deactivateSupplier(id) {
  await httpClient.delete(`${suppliersBase}/${segment(id)}`)
}

/**
 * @param {PurchaseOrderListParams} params
 * @returns {Promise<PageResponse<PurchaseOrderView>>}
 */
export async function listPurchaseOrders(params) {
  const data = (await httpClient.get(ordersBase, { params: cleanParams(params) })).data
  const page = toPage(data)
  return { ...page, content: page.content.map(normaliseOrder) }
}

/**
 * @param {string} id
 * @returns {Promise<PurchaseOrderView>}
 */
export async function fetchPurchaseOrder(id) {
  return normaliseOrder((await httpClient.get(`${ordersBase}/${segment(id)}`)).data)
}

/**
 * Creates a DRAFT.
 * @param {PurchaseOrderRequest} body
 * @returns {Promise<PurchaseOrderView>}
 */
export async function createPurchaseOrder(body) {
  return normaliseOrder((await httpClient.post(ordersBase, body)).data)
}

/**
 * Only a DRAFT is editable (409 INVALID_STATUS_TRANSITION otherwise).
 * @param {string} id
 * @param {PurchaseOrderRequest} body
 * @returns {Promise<PurchaseOrderView>}
 */
export async function updatePurchaseOrder(id, body) {
  return normaliseOrder((await httpClient.put(`${ordersBase}/${segment(id)}`, body)).data)
}

/**
 * `send` (DRAFT → SENT), `cancel` (DRAFT / SENT), `close` (PARTIALLY_RECEIVED → CLOSED); 409 INVALID_STATUS_TRANSITION (`currentStatus`).
 * @param {string} id
 * @param {PurchaseOrderTransition} transition
 * @returns {Promise<PurchaseOrderView>}
 */
export async function transitionPurchaseOrder(id, transition) {
  return normaliseOrder((await httpClient.post(`${ordersBase}/${segment(id)}/${transition}`)).data)
}

/**
 * Records goods received. The same `idempotencyKey` for a retried request makes the server answer the first
 * receipt again instead of adding the stock twice. 422 RECEIPT_EXCEEDS_ORDERED when a line is more than what is open.
 * @param {string} id
 * @param {ReceiveRequest} body
 * @param {string} idempotencyKey
 * @returns {Promise<PurchaseOrderView>}
 */
export async function receivePurchaseOrder(id, body, idempotencyKey) {
  const response = await httpClient.post(`${ordersBase}/${segment(id)}/receive`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return normaliseOrder(response.data)
}

/**
 * Read-only list for admins.
 * @param {AdminPurchaseOrderListParams} params
 * @returns {Promise<PageResponse<PurchaseOrderView>>}
 */
export async function listAdminPurchaseOrders(params) {
  const data = (await httpClient.get('/admin/purchase-orders', { params: cleanParams(params) })).data
  const page = toPage(data)
  return { ...page, content: page.content.map(normaliseOrder) }
}
