import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { BadgeTone } from '../../shared/components/ui'

/** List filter of `GET /api/vendor/prices` (Phase 4 contract §3). */
export const PRICE_FILTERS = ['PRICED', 'UNPRICED', 'PENDING_APPROVAL'] as const
export type PriceFilter = (typeof PRICE_FILTERS)[number]

export const PRICE_FILTER_LABELS: Record<PriceFilter, string> = {
  PRICED: 'Priced',
  UNPRICED: 'Unpriced',
  PENDING_APPROVAL: 'Pending approval',
}

export function isPriceFilter(value: unknown): value is PriceFilter {
  return typeof value === 'string' && (PRICE_FILTERS as readonly string[]).includes(value)
}

export const PRICE_CHANGE_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'] as const
export type PriceChangeStatus = (typeof PRICE_CHANGE_STATUSES)[number]

export const PRICE_CHANGE_STATUS_LABELS: Record<PriceChangeStatus, string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
}

export const PRICE_CHANGE_STATUS_TONES: Record<PriceChangeStatus, BadgeTone> = {
  PENDING: 'amber',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'gray',
}

export function isPriceChangeStatus(value: unknown): value is PriceChangeStatus {
  return typeof value === 'string' && (PRICE_CHANGE_STATUSES as readonly string[]).includes(value)
}

/** INR with 2 decimals, `1.00 ≤ sellingPrice ≤ mrp ≤ 1000000.00`; notes and reasons ≤ 300 characters. */
export const PRICE_LIMITS = { min: 1, max: 1_000_000, decimals: 2, note: 300 } as const

/** Default of `grocery.pricing.approval-threshold-percent`; every PriceView carries the live value. */
export const DEFAULT_APPROVAL_THRESHOLD_PERCENT = 20

/** The live price of a product. */
export interface LivePrice {
  mrp: number
  sellingPrice: number
  currency: string
  /** `round((mrp − sellingPrice) / mrp × 100)`, 0–99. */
  discountPercent: number
  updatedAt: string
}

/** `PriceChangeRequestView` — a change bigger than the threshold, waiting for (or decided by) an admin. */
export interface PriceChangeRequestView {
  id: string
  productId: string
  productName: string
  unitLabel: string
  vendorId: string
  /** Admin list only, when known. */
  vendorName?: string | null
  currentMrp: number | null
  currentSellingPrice: number | null
  requestedMrp: number
  requestedSellingPrice: number
  /** Selling price change, signed, 2 decimals. */
  changePercent: number
  status: PriceChangeStatus | (string & {})
  note: string | null
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
}

export type PriceOutcome = 'APPLIED' | 'PENDING_APPROVAL'

/** `PriceView` — one product's price; `price` null = no price yet. */
export interface PriceView {
  productId: string
  productName: string
  unitLabel: string
  sku: string | null
  productStatus: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | (string & {})
  price: LivePrice | null
  pendingRequest: PriceChangeRequestView | null
  approvalThresholdPercent: number
  /** Only in the PUT response. */
  outcome?: PriceOutcome
}

/** `PriceRequest`. */
export interface PriceRequest {
  mrp: number
  sellingPrice: number
  note: string | null
}

/** `PriceHistoryEntry` — one change of the live price, newest first. */
export interface PriceHistoryEntry {
  id: string
  /** null for the first price. */
  oldMrp: number | null
  oldSellingPrice: number | null
  newMrp: number
  newSellingPrice: number
  changePercent: number | null
  source: 'VENDOR' | 'APPROVED_REQUEST' | (string & {})
  requestId: string | null
  changedBy: string | null
  changedAt: string
}

export interface PricingSummary {
  products: number
  priced: number
  unpriced: number
  pendingApproval: number
}

export interface VendorPriceListParams {
  q?: string
  filter?: PriceFilter
  page?: number
  size?: number
}

export interface PriceRequestListParams {
  status?: PriceChangeStatus
  page?: number
  size?: number
}

export const SELLING_PRICE_ABOVE_MRP = 'SELLING_PRICE_ABOVE_MRP'
export const PRICE_CHANGE_PENDING = 'PRICE_CHANGE_PENDING'
export const PRICE_UNCHANGED = 'PRICE_UNCHANGED'
export const PRICE_CHANGE_NOT_PENDING = 'PRICE_CHANGE_NOT_PENDING'

export const HISTORY_PAGE_SIZE = 10

export const vendorPriceKeys = {
  all: ['vendor', 'prices'] as const,
  list: (params: VendorPriceListParams) => [...vendorPriceKeys.all, 'list', params] as const,
  summary: () => [...vendorPriceKeys.all, 'summary'] as const,
  detail: (productId: string) => [...vendorPriceKeys.all, 'detail', productId] as const,
  history: (productId: string, page: number) => [...vendorPriceKeys.all, 'history', productId, page] as const,
  requests: (params: PriceRequestListParams) => [...vendorPriceKeys.all, 'requests', params] as const,
}

const base = '/vendor/prices'
const item = (productId: string) => `${base}/${segment(productId)}`

export async function listVendorPrices(params: VendorPriceListParams): Promise<PageResponse<PriceView>> {
  return (await httpClient.get<PageResponse<PriceView>>(base, { params: cleanParams(params) })).data
}

export async function fetchPricingSummary(): Promise<PricingSummary> {
  return (await httpClient.get<PricingSummary>(`${base}/summary`)).data
}

export async function fetchVendorPrice(productId: string): Promise<PriceView> {
  return (await httpClient.get<PriceView>(item(productId))).data
}

/**
 * 200 `APPLIED` (live now) or 202 `PENDING_APPROVAL` (an admin decides; the live price is unchanged).
 * 422 SELLING_PRICE_ABOVE_MRP, 409 PRICE_CHANGE_PENDING / PRICE_UNCHANGED / PRODUCT_ARCHIVED.
 */
export async function setVendorPrice(productId: string, body: PriceRequest): Promise<PriceView & { outcome: PriceOutcome }> {
  const response = await httpClient.put<PriceView>(item(productId), body)
  const outcome: PriceOutcome = response.data.outcome ?? (response.status === 202 ? 'PENDING_APPROVAL' : 'APPLIED')
  return { ...response.data, outcome }
}

export async function listPriceHistory(productId: string, page: number): Promise<PageResponse<PriceHistoryEntry>> {
  return (
    await httpClient.get<PageResponse<PriceHistoryEntry>>(`${item(productId)}/history`, { params: { page, size: HISTORY_PAGE_SIZE } })
  ).data
}

export async function listMyPriceRequests(params: PriceRequestListParams): Promise<PageResponse<PriceChangeRequestView>> {
  return (await httpClient.get<PageResponse<PriceChangeRequestView>>('/vendor/price-change-requests', { params: cleanParams(params) })).data
}

/** 409 PRICE_CHANGE_NOT_PENDING when it was decided (or cancelled) meanwhile. */
export async function cancelPriceRequest(id: string): Promise<PriceChangeRequestView> {
  return (await httpClient.post<PriceChangeRequestView>(`/vendor/price-change-requests/${segment(id)}/cancel`)).data
}
