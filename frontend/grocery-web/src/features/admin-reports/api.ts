import { httpClient } from '../../shared/api/httpClient'
import { cleanParams } from '../../shared/api/types'
import { daysInRange, isIsoDay } from '../../shared/utils/indiaDate'

/**
 * Admin reports (reporting-service — Phase 12 contract §7), built only from events. `from` / `to` are India dates,
 * both included, at most 366 days apart; `?format=csv` answers the same report as CSV with a header row, `xlsx` / `pdf`
 * as a file for people (headings in words, the period on top). The Reports page downloads them with the shared
 * `ExportButton`.
 */

export const MAX_ADMIN_REPORT_DAYS = 366
export const DEFAULT_ADMIN_REPORT_DAYS = 30

export interface ReportRange {
  from: string
  to: string
}

/** Why a chosen range cannot be requested, or null when it can. */
export function adminReportRangeError(from: string, to: string): string | null {
  if (!isIsoDay(from) || !isIsoDay(to)) return 'Choose a start date and an end date.'
  if (from > to) return 'The start date must be on or before the end date.'
  const days = daysInRange(from, to)
  if (days > MAX_ADMIN_REPORT_DAYS) return `Choose at most ${MAX_ADMIN_REPORT_DAYS} days — this range has ${days}.`
  return null
}

/** `GET /api/admin/reports/overview`. Amounts in rupees. */
export interface ReportOverview {
  from?: string
  to?: string
  currency?: string
  /** GMV of confirmed orders. */
  grossSales: number
  /** GMV − refunds. */
  netSales: number
  orders: number
  averageOrderValue: number
  /** Cancelled (and rejected) of all orders, 0–1. */
  cancellationRate: number
  /** Refunded amount. */
  refunds: number
  /**
   * Coupon discounts of confirmed orders: `total`, and by who funds the coupon. Orders placed before the funding was
   * recorded (grocery-event-schemas 1.7.0) are in neither `platform` nor `store`, so the two can add up to less than
   * `total`; null from a server that does not split them.
   */
  couponDiscounts: { platform: number | null; store: number | null; total: number }
  /** Customers whose first order fell in the range. */
  newCustomers: number
}

/** `GET /api/admin/reports/sales/daily` — one row per day of the range. */
export interface DailySalesRow {
  date: string
  orders: number
  gmv: number
  discounts: number
  refunds: number
  net: number
}

export interface TopStoreRow {
  storeId: string
  storeName: string | null
  storeType: 'GROCERY' | 'RESTAURANT' | (string & {})
  orders: number
  gmv: number
}

export interface TopProductRow {
  itemId: string
  name: string
  storeName?: string | null
  /** PRODUCT or MENU_ITEM. */
  itemType?: string | null
  units: number
  revenue: number
}

/** `GET /api/admin/reports/delivery/performance` — one row per day; averages in minutes (null without deliveries). */
export interface DeliveryPerformanceRow {
  date: string
  deliveries: number
  avgConfirmToAssignedMinutes: number | null
  avgAssignedToPickedUpMinutes: number | null
  avgPickedUpToDeliveredMinutes: number | null
  failed: number
}

export interface PromotionRow {
  couponCode: string
  couponName?: string | null
  orders: number
  discountGiven: number
  /** GMV of the orders that used it. */
  gmv: number
  /** Who funds it; null when every order with it was placed before the funding was recorded. */
  fundedBy: 'PLATFORM' | 'STORE' | (string & {}) | null
}

export interface RatedTarget {
  targetType: string
  targetId: string
  targetName: string | null
  averageRating: number
  ratingCount: number
}

/** `GET /api/admin/reports/ratings`. */
export interface RatingsReport {
  lowest: RatedTarget[]
  highest: RatedTarget[]
}

/** `GET /api/admin/reports/purchasing` — goods received per vendor and day. */
export interface PurchasingRow {
  vendorId: string
  vendorName?: string | null
  date: string
  receipts?: number
  units: number
  cost: number
}

export type StoreMetric = 'gmv' | 'orders'

export interface DailySalesParams extends ReportRange {
  type?: 'GROCERY' | 'RESTAURANT'
  storeId?: string
}

export const REPORT_PATHS = {
  overview: '/admin/reports/overview',
  daily: '/admin/reports/sales/daily',
  topStores: '/admin/reports/stores/top',
  topProducts: '/admin/reports/products/top',
  delivery: '/admin/reports/delivery/performance',
  promotions: '/admin/reports/promotions',
  ratings: '/admin/reports/ratings',
  purchasing: '/admin/reports/purchasing',
} as const

export type ReportName = keyof typeof REPORT_PATHS

export const adminReportKeys = {
  all: ['admin', 'reports'] as const,
  report: (name: ReportName, params: object) => [...adminReportKeys.all, name, params] as const,
}

/** A list report as rows: a JSON array, or an object holding one (`rows`, `days`, `content`, `items`). */
function rowsOf<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[]
  if (data && typeof data === 'object') {
    for (const key of ['rows', 'days', 'content', 'items', 'stores', 'products', 'coupons']) {
      const value = (data as Record<string, unknown>)[key]
      if (Array.isArray(value)) return value as T[]
    }
  }
  return []
}

async function get(name: ReportName, params: object): Promise<unknown> {
  return (await httpClient.get<unknown>(REPORT_PATHS[name], { params: cleanParams(params) })).data
}

/** The total is the server's; without one (an older server), the funded parts are all there is. */
function couponDiscounts(data: Partial<ReportOverview['couponDiscounts']> | undefined): ReportOverview['couponDiscounts'] {
  const platform = data?.platform ?? null
  const store = data?.store ?? null
  return { platform, store, total: data?.total ?? (platform ?? 0) + (store ?? 0) }
}

export async function fetchOverview(range: ReportRange): Promise<ReportOverview> {
  const data = (await get('overview', range)) as Partial<ReportOverview>
  return {
    ...data,
    grossSales: data.grossSales ?? 0,
    netSales: data.netSales ?? 0,
    orders: data.orders ?? 0,
    averageOrderValue: data.averageOrderValue ?? 0,
    cancellationRate: data.cancellationRate ?? 0,
    refunds: data.refunds ?? 0,
    couponDiscounts: couponDiscounts(data.couponDiscounts),
    newCustomers: data.newCustomers ?? 0,
  }
}

export async function fetchDailySales(params: DailySalesParams): Promise<DailySalesRow[]> {
  return rowsOf<DailySalesRow>(await get('daily', params))
}

export async function fetchTopStores(params: ReportRange & { metric: StoreMetric; limit: number }): Promise<TopStoreRow[]> {
  return rowsOf<TopStoreRow>(await get('topStores', params))
}

export async function fetchTopProducts(params: ReportRange & { limit: number }): Promise<TopProductRow[]> {
  return rowsOf<TopProductRow>(await get('topProducts', params))
}

export async function fetchDeliveryPerformance(range: ReportRange): Promise<DeliveryPerformanceRow[]> {
  return rowsOf<DeliveryPerformanceRow>(await get('delivery', range))
}

export async function fetchPromotions(range: ReportRange): Promise<PromotionRow[]> {
  return rowsOf<PromotionRow>(await get('promotions', range))
}

export async function fetchRatings(params: ReportRange & { targetType?: string; limit: number }): Promise<RatingsReport> {
  const data = (await get('ratings', params)) as Partial<RatingsReport> | null
  return { lowest: data?.lowest ?? [], highest: data?.highest ?? [] }
}

export async function fetchPurchasing(params: ReportRange & { vendorId?: string }): Promise<PurchasingRow[]> {
  return rowsOf<PurchasingRow>(await get('purchasing', params))
}

