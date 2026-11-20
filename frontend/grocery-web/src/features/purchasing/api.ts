import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import { toPage } from '../../shared/api/pages'

/**
 * Vendor purchasing & supply (vendor-service — Phase 12 contract §4): the vendor's suppliers and purchase orders.
 * Receiving goods publishes `GoodsReceived`; inventory-service then adds the quantities to stock (once per
 * receipt, even when the request is retried with the same `Idempotency-Key`).
 */

export const PO_STATUSES = ['DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED', 'CLOSED'] as const
export type PurchaseOrderStatus = (typeof PO_STATUSES)[number]

export function isPurchaseOrderStatus(value: unknown): value is PurchaseOrderStatus {
  return typeof value === 'string' && (PO_STATUSES as readonly string[]).includes(value)
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
} as const

export const PURCHASING_ERRORS = {
  supplierInUse: 'SUPPLIER_IN_USE',
  invalidTransition: 'INVALID_STATUS_TRANSITION',
  receiptExceedsOrdered: 'RECEIPT_EXCEEDS_ORDERED',
} as const

/** `SupplierView`. */
export interface SupplierView {
  id: string
  name: string
  contactName: string | null
  phone: string | null
  email: string | null
  gstin: string | null
  /** Free text, ≤ 300. */
  address: string | null
  /** 0–90. */
  leadTimeDays: number | null
  notes: string | null
  active: boolean
  createdAt?: string
  updatedAt?: string
}

export interface SupplierRequest {
  name: string
  contactName: string | null
  phone: string | null
  email: string | null
  gstin: string | null
  address: string | null
  leadTimeDays: number | null
  notes: string | null
}

/** One line of a purchase order. `productName` is a snapshot taken when the line was saved. */
export interface PurchaseOrderLine {
  productId: string
  productName: string
  /** 1–100000. */
  orderedQuantity: number
  /** ≥ 0, 2 decimals. */
  unitCost: number
  receivedQuantity: number
  /** `orderedQuantity × unitCost` (computed here when absent). */
  lineTotal?: number
}

export interface ReceiptLine {
  productId: string
  productName?: string | null
  quantity: number
}

/** One goods receipt (a purchase order can have several). */
export interface ReceiptView {
  id: string
  receivedAt: string
  note: string | null
  lines: ReceiptLine[]
}

/** `PurchaseOrderView`. */
export interface PurchaseOrderView {
  id: string
  /** `PO-XXXXXXXX`. */
  number: string
  vendorId?: string
  /** Admin list: the vendor's business name. */
  vendorName?: string | null
  supplierId: string
  supplierName?: string | null
  status: PurchaseOrderStatus | (string & {})
  /** The day the goods are expected (`YYYY-MM-DD`), or null. */
  expectedAt: string | null
  notes: string | null
  lines: PurchaseOrderLine[]
  /** Sum of the line totals (computed here when absent). */
  totalCost?: number
  currency?: string
  receipts?: ReceiptView[]
  createdAt: string
  updatedAt?: string | null
  sentAt?: string | null
}

export interface PurchaseOrderLineRequest {
  productId: string
  orderedQuantity: number
  unitCost: number
}

export interface PurchaseOrderRequest {
  supplierId: string
  expectedAt: string | null
  notes: string | null
  lines: PurchaseOrderLineRequest[]
}

export interface ReceiveRequest {
  lines: { productId: string; quantity: number }[]
  note?: string
}

export interface PurchaseOrderListParams {
  status?: PurchaseOrderStatus
  supplierId?: string
  page?: number
  size?: number
}

export interface AdminPurchaseOrderListParams {
  vendorId?: string
  status?: PurchaseOrderStatus
  page?: number
  size?: number
}

export type PurchaseOrderTransition = 'send' | 'cancel' | 'close'

export const purchasingKeys = {
  all: ['purchasing'] as const,
  suppliers: () => [...purchasingKeys.all, 'suppliers'] as const,
  orders: () => [...purchasingKeys.all, 'orders'] as const,
  orderList: (params: PurchaseOrderListParams) => [...purchasingKeys.orders(), 'list', params] as const,
  order: (id: string) => [...purchasingKeys.orders(), 'detail', id] as const,
  admin: () => [...purchasingKeys.all, 'admin'] as const,
  adminList: (params: AdminPurchaseOrderListParams) => [...purchasingKeys.admin(), params] as const,
}

/** Units still to come for one line (never negative). */
export function openQuantity(line: Pick<PurchaseOrderLine, 'orderedQuantity' | 'receivedQuantity'>): number {
  return Math.max(0, line.orderedQuantity - (line.receivedQuantity ?? 0))
}

export function lineTotal(line: Pick<PurchaseOrderLine, 'orderedQuantity' | 'unitCost' | 'lineTotal'>): number {
  return line.lineTotal ?? Math.round(line.orderedQuantity * line.unitCost * 100) / 100
}

export function orderTotal(order: Pick<PurchaseOrderView, 'lines' | 'totalCost'>): number {
  return order.totalCost ?? Math.round(order.lines.reduce((sum, line) => sum + lineTotal(line), 0) * 100) / 100
}

function normaliseOrder(order: PurchaseOrderView): PurchaseOrderView {
  return {
    ...order,
    lines: (order.lines ?? []).map((line) => ({ ...line, receivedQuantity: line.receivedQuantity ?? 0 })),
    receipts: order.receipts ?? [],
  }
}

const suppliersBase = '/vendor/suppliers'
const ordersBase = '/vendor/purchase-orders'

/** Every supplier of the vendor (active and deactivated), as a list or a page. */
export async function listSuppliers(): Promise<SupplierView[]> {
  const data = (await httpClient.get<SupplierView[] | PageResponse<SupplierView>>(suppliersBase, { params: { size: 200 } })).data
  return toPage(data).content
}

export async function createSupplier(body: SupplierRequest): Promise<SupplierView> {
  return (await httpClient.post<SupplierView>(suppliersBase, body)).data
}

export async function updateSupplier(id: string, body: SupplierRequest): Promise<SupplierView> {
  return (await httpClient.put<SupplierView>(`${suppliersBase}/${segment(id)}`, body)).data
}

/** Deactivates the supplier; 409 SUPPLIER_IN_USE while it has open purchase orders. */
export async function deactivateSupplier(id: string): Promise<void> {
  await httpClient.delete(`${suppliersBase}/${segment(id)}`)
}

export async function listPurchaseOrders(params: PurchaseOrderListParams): Promise<PageResponse<PurchaseOrderView>> {
  const data = (await httpClient.get<PageResponse<PurchaseOrderView> | PurchaseOrderView[]>(ordersBase, { params: cleanParams(params) })).data
  const page = toPage(data)
  return { ...page, content: page.content.map(normaliseOrder) }
}

export async function fetchPurchaseOrder(id: string): Promise<PurchaseOrderView> {
  return normaliseOrder((await httpClient.get<PurchaseOrderView>(`${ordersBase}/${segment(id)}`)).data)
}

/** Creates a DRAFT. */
export async function createPurchaseOrder(body: PurchaseOrderRequest): Promise<PurchaseOrderView> {
  return normaliseOrder((await httpClient.post<PurchaseOrderView>(ordersBase, body)).data)
}

/** Only a DRAFT is editable (409 INVALID_STATUS_TRANSITION otherwise). */
export async function updatePurchaseOrder(id: string, body: PurchaseOrderRequest): Promise<PurchaseOrderView> {
  return normaliseOrder((await httpClient.put<PurchaseOrderView>(`${ordersBase}/${segment(id)}`, body)).data)
}

/** `send` (DRAFT → SENT), `cancel` (DRAFT / SENT), `close` (PARTIALLY_RECEIVED → CLOSED); 409 INVALID_STATUS_TRANSITION (`currentStatus`). */
export async function transitionPurchaseOrder(id: string, transition: PurchaseOrderTransition): Promise<PurchaseOrderView> {
  return normaliseOrder((await httpClient.post<PurchaseOrderView>(`${ordersBase}/${segment(id)}/${transition}`)).data)
}

/**
 * Records goods received. The same `idempotencyKey` for a retried request makes the server answer the first
 * receipt again instead of adding the stock twice. 422 RECEIPT_EXCEEDS_ORDERED when a line is more than what is open.
 */
export async function receivePurchaseOrder(id: string, body: ReceiveRequest, idempotencyKey: string): Promise<PurchaseOrderView> {
  const response = await httpClient.post<PurchaseOrderView>(`${ordersBase}/${segment(id)}/receive`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return normaliseOrder(response.data)
}

/** Read-only list for admins. */
export async function listAdminPurchaseOrders(params: AdminPurchaseOrderListParams): Promise<PageResponse<PurchaseOrderView>> {
  const data = (await httpClient.get<PageResponse<PurchaseOrderView> | PurchaseOrderView[]>('/admin/purchase-orders', { params: cleanParams(params) })).data
  const page = toPage(data)
  return { ...page, content: page.content.map(normaliseOrder) }
}
