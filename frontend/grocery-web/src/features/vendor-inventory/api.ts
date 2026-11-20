import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/** List filter of `GET /api/vendor/inventory` (Phase 4 contract §2). */
export const STOCK_FILTERS = ['LOW_STOCK', 'OUT_OF_STOCK', 'IN_STOCK'] as const
export type StockFilter = (typeof STOCK_FILTERS)[number]

export const STOCK_FILTER_LABELS: Record<StockFilter, string> = {
  LOW_STOCK: 'Low stock',
  OUT_OF_STOCK: 'Out of stock',
  IN_STOCK: 'In stock',
}

export function isStockFilter(value: unknown): value is StockFilter {
  return typeof value === 'string' && (STOCK_FILTERS as readonly string[]).includes(value)
}

export const ADJUSTMENT_TYPES = ['INCREASE', 'DECREASE', 'SET'] as const
export type AdjustmentType = (typeof ADJUSTMENT_TYPES)[number]

export const ADJUSTMENT_TYPE_LABELS: Record<AdjustmentType, string> = {
  INCREASE: 'Increase',
  DECREASE: 'Decrease',
  SET: 'Set counted stock',
}

export const ADJUSTMENT_REASONS = ['RESTOCK', 'RETURN', 'DAMAGED', 'EXPIRED', 'STOCK_COUNT', 'CORRECTION', 'OTHER'] as const
export type AdjustmentReason = (typeof ADJUSTMENT_REASONS)[number]

export const ADJUSTMENT_REASON_LABELS: Record<AdjustmentReason, string> = {
  RESTOCK: 'Restock (new delivery)',
  RETURN: 'Customer return',
  DAMAGED: 'Damaged',
  EXPIRED: 'Expired',
  STOCK_COUNT: 'Stock count',
  CORRECTION: 'Correction',
  OTHER: 'Other',
}

/** Movements also record what orders do to the stock (from Phase 6). */
export type MovementType = AdjustmentType | 'RESERVE' | 'RELEASE' | 'COMMIT'

export const MOVEMENT_TYPE_LABELS: Record<MovementType, string> = {
  INCREASE: 'Increase',
  DECREASE: 'Decrease',
  SET: 'Stock count',
  RESERVE: 'Reserved for an order',
  RELEASE: 'Reservation released',
  COMMIT: 'Sent with an order',
}

export function movementTypeLabel(type: string): string {
  return type in MOVEMENT_TYPE_LABELS ? MOVEMENT_TYPE_LABELS[type as MovementType] : type
}

export function adjustmentReasonLabel(reason: string): string {
  return reason in ADJUSTMENT_REASON_LABELS ? ADJUSTMENT_REASON_LABELS[reason as AdjustmentReason] : reason
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
} as const

/** `AdjustmentRequest`. */
export interface AdjustmentRequest {
  type: AdjustmentType
  quantity: number
  reason: AdjustmentReason
  note: string | null
}

/** `StockView` — one product's stock. `available = onHand - reserved`. */
export interface StockView {
  productId: string
  productName: string
  unitLabel: string
  sku: string | null
  productStatus: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | (string & {})
  onHand: number
  reserved: number
  available: number
  /** 0 = no low-stock alerts. */
  lowStockThreshold: number
  lowStock: boolean
  inStock: boolean
  updatedAt: string
}

/** `StockMovementView` — one ledger entry, newest first. */
export interface StockMovementView {
  id: string
  type: MovementType | (string & {})
  /** Change of on-hand stock (0 for RESERVE / RELEASE). */
  quantityDelta: number
  onHandAfter: number
  reservedAfter: number
  reason: AdjustmentReason | (string & {}) | null
  note: string | null
  /** Order id for RESERVE / RELEASE / COMMIT. */
  reference: string | null
  actorUserId: string | null
  createdAt: string
}

export interface InventorySummary {
  products: number
  inStock: number
  /** Low but not out. */
  lowStock: number
  outOfStock: number
}

export interface InventoryListParams {
  /** Product name contains / SKU equals. */
  q?: string
  filter?: StockFilter
  page?: number
  size?: number
}

export const INSUFFICIENT_STOCK = 'INSUFFICIENT_STOCK'
export const STOCK_BELOW_RESERVED = 'STOCK_BELOW_RESERVED'
export const STOCK_LIMIT_EXCEEDED = 'STOCK_LIMIT_EXCEEDED'

export const MOVEMENTS_PAGE_SIZE = 10

export const inventoryKeys = {
  all: ['vendor', 'inventory'] as const,
  list: (params: InventoryListParams) => [...inventoryKeys.all, 'list', params] as const,
  summary: () => [...inventoryKeys.all, 'summary'] as const,
  movements: (productId: string, page: number) => [...inventoryKeys.all, 'movements', productId, page] as const,
}

const base = '/vendor/inventory'
const item = (productId: string) => `${base}/${segment(productId)}`

export async function listInventory(params: InventoryListParams): Promise<PageResponse<StockView>> {
  return (await httpClient.get<PageResponse<StockView>>(base, { params: cleanParams(params) })).data
}

export async function fetchInventorySummary(): Promise<InventorySummary> {
  return (await httpClient.get<InventorySummary>(`${base}/summary`)).data
}

/** 409 INSUFFICIENT_STOCK / STOCK_BELOW_RESERVED (with `available` and `reserved`), 422 STOCK_LIMIT_EXCEEDED. */
export async function adjustStock(productId: string, body: AdjustmentRequest): Promise<StockView> {
  return (await httpClient.post<StockView>(`${item(productId)}/adjustments`, body)).data
}

export async function setLowStockThreshold(productId: string, lowStockThreshold: number): Promise<StockView> {
  return (await httpClient.put<StockView>(`${item(productId)}/threshold`, { lowStockThreshold })).data
}

export async function listStockMovements(productId: string, page: number): Promise<PageResponse<StockMovementView>> {
  return (
    await httpClient.get<PageResponse<StockMovementView>>(`${item(productId)}/movements`, { params: { page, size: MOVEMENTS_PAGE_SIZE } })
  ).data
}

/** One row of `POST /api/vendor/inventory/adjustments/bulk`: an `AdjustmentRequest` for one product. */
export interface BulkAdjustmentItem extends AdjustmentRequest {
  productId: string
}

/** Contract: 1–100 items per bulk request, each product at most once. */
export const BULK_MAX_ITEMS = 100

/**
 * All or nothing: one failing item fails the whole request and nothing changes. The error names the item in its
 * `productId` property (409 INSUFFICIENT_STOCK / STOCK_BELOW_RESERVED, 422 STOCK_LIMIT_EXCEEDED, 404 PRODUCT_NOT_FOUND, …).
 * Resolves with the new stock of every item, in request order.
 */
export async function bulkAdjustStock(items: BulkAdjustmentItem[]): Promise<StockView[]> {
  return (await httpClient.post<StockView[]>(`${base}/adjustments/bulk`, { items })).data
}
