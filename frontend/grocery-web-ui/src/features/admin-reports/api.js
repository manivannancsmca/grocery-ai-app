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

/**
 * @typedef {object} ReportRange
 * @property {string} from
 * @property {string} to
 */

/**
 * Why a chosen range cannot be requested, or null when it can.
 * @param {string} from
 * @param {string} to
 * @returns {string | null}
 */
export function adminReportRangeError(from, to) {
  if (!isIsoDay(from) || !isIsoDay(to)) return 'Choose a start date and an end date.'
  if (from > to) return 'The start date must be on or before the end date.'
  const days = daysInRange(from, to)
  if (days > MAX_ADMIN_REPORT_DAYS) return `Choose at most ${MAX_ADMIN_REPORT_DAYS} days — this range has ${days}.`
  return null
}

/**
 * `GET /api/admin/reports/overview`. Amounts in rupees.
 * @typedef {object} ReportOverview
 * @property {string} [from]
 * @property {string} [to]
 * @property {string} [currency]
 * @property {number} grossSales GMV of confirmed orders.
 * @property {number} netSales GMV − refunds.
 * @property {number} orders
 * @property {number} averageOrderValue
 * @property {number} cancellationRate Cancelled (and rejected) of all orders, 0–1.
 * @property {number} refunds Refunded amount.
 * @property {{ platform: number | null, store: number | null, total: number }} couponDiscounts Coupon discounts of confirmed orders: `total`, and by who funds the coupon. Orders placed before the funding was recorded (grocery-event-schemas 1.7.0) are in neither `platform` nor `store`, so the two can add up to less than `total`; null from a server that does not split them.
 * @property {number} newCustomers Customers whose first order fell in the range.
 */

/**
 * `GET /api/admin/reports/sales/daily` — one row per day of the range.
 * @typedef {object} DailySalesRow
 * @property {string} date
 * @property {number} orders
 * @property {number} gmv
 * @property {number} discounts
 * @property {number} refunds
 * @property {number} net
 */

/**
 * @typedef {object} TopStoreRow
 * @property {string} storeId
 * @property {string | null} storeName
 * @property {'GROCERY' | 'RESTAURANT' | (string & {})} storeType
 * @property {number} orders
 * @property {number} gmv
 */

/**
 * @typedef {object} TopProductRow
 * @property {string} itemId
 * @property {string} name
 * @property {string | null} [storeName]
 * @property {string | null} [itemType] PRODUCT or MENU_ITEM.
 * @property {number} units
 * @property {number} revenue
 */

/**
 * `GET /api/admin/reports/delivery/performance` — one row per day; averages in minutes (null without deliveries).
 * @typedef {object} DeliveryPerformanceRow
 * @property {string} date
 * @property {number} deliveries
 * @property {number | null} avgConfirmToAssignedMinutes
 * @property {number | null} avgAssignedToPickedUpMinutes
 * @property {number | null} avgPickedUpToDeliveredMinutes
 * @property {number} failed
 */

/**
 * @typedef {object} PromotionRow
 * @property {string} couponCode
 * @property {string | null} [couponName]
 * @property {number} orders
 * @property {number} discountGiven
 * @property {number} gmv GMV of the orders that used it.
 * @property {'PLATFORM' | 'STORE' | (string & {}) | null} fundedBy Who funds it; null when every order with it was placed before the funding was recorded.
 */

/**
 * @typedef {object} RatedTarget
 * @property {string} targetType
 * @property {string} targetId
 * @property {string | null} targetName
 * @property {number} averageRating
 * @property {number} ratingCount
 */

/**
 * `GET /api/admin/reports/ratings`.
 * @typedef {object} RatingsReport
 * @property {RatedTarget[]} lowest
 * @property {RatedTarget[]} highest
 */

/**
 * `GET /api/admin/reports/purchasing` — goods received per vendor and day.
 * @typedef {object} PurchasingRow
 * @property {string} vendorId
 * @property {string | null} [vendorName]
 * @property {string} date
 * @property {number} [receipts]
 * @property {number} units
 * @property {number} cost
 */

/** @typedef {'gmv' | 'orders'} StoreMetric */

/** @typedef {ReportRange & { type?: 'GROCERY' | 'RESTAURANT', storeId?: string }} DailySalesParams */

export const REPORT_PATHS = {
  overview: '/admin/reports/overview',
  daily: '/admin/reports/sales/daily',
  topStores: '/admin/reports/stores/top',
  topProducts: '/admin/reports/products/top',
  delivery: '/admin/reports/delivery/performance',
  promotions: '/admin/reports/promotions',
  ratings: '/admin/reports/ratings',
  purchasing: '/admin/reports/purchasing',
}

/** @typedef {keyof typeof REPORT_PATHS} ReportName */

export const adminReportKeys = {
  all: ['admin', 'reports'],
  report: (name, params) => [...adminReportKeys.all, name, params],
}

/**
 * A list report as rows: a JSON array, or an object holding one (`rows`, `days`, `content`, `items`).
 * @template T
 * @param {unknown} data
 * @returns {T[]}
 */
function rowsOf(data) {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    for (const key of ['rows', 'days', 'content', 'items', 'stores', 'products', 'coupons']) {
      const value = data[key]
      if (Array.isArray(value)) return value
    }
  }
  return []
}

/**
 * @param {ReportName} name
 * @param {object} params
 * @returns {Promise<unknown>}
 */
async function get(name, params) {
  return (await httpClient.get(REPORT_PATHS[name], { params: cleanParams(params) })).data
}

/**
 * The total is the server's; without one (an older server), the funded parts are all there is.
 * @param {Partial<ReportOverview['couponDiscounts']> | undefined} data
 * @returns {ReportOverview['couponDiscounts']}
 */
function couponDiscounts(data) {
  const platform = data?.platform ?? null
  const store = data?.store ?? null
  return { platform, store, total: data?.total ?? (platform ?? 0) + (store ?? 0) }
}

/**
 * @param {ReportRange} range
 * @returns {Promise<ReportOverview>}
 */
export async function fetchOverview(range) {
  const data = await get('overview', range)
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

/**
 * @param {DailySalesParams} params
 * @returns {Promise<DailySalesRow[]>}
 */
export async function fetchDailySales(params) {
  return rowsOf(await get('daily', params))
}

/**
 * @param {ReportRange & { metric: StoreMetric, limit: number }} params
 * @returns {Promise<TopStoreRow[]>}
 */
export async function fetchTopStores(params) {
  return rowsOf(await get('topStores', params))
}

/**
 * @param {ReportRange & { limit: number }} params
 * @returns {Promise<TopProductRow[]>}
 */
export async function fetchTopProducts(params) {
  return rowsOf(await get('topProducts', params))
}

/**
 * @param {ReportRange} range
 * @returns {Promise<DeliveryPerformanceRow[]>}
 */
export async function fetchDeliveryPerformance(range) {
  return rowsOf(await get('delivery', range))
}

/**
 * @param {ReportRange} range
 * @returns {Promise<PromotionRow[]>}
 */
export async function fetchPromotions(range) {
  return rowsOf(await get('promotions', range))
}

/**
 * @param {ReportRange & { targetType?: string, limit: number }} params
 * @returns {Promise<RatingsReport>}
 */
export async function fetchRatings(params) {
  const data = await get('ratings', params)
  return { lowest: data?.lowest ?? [], highest: data?.highest ?? [] }
}

/**
 * @param {ReportRange & { vendorId?: string }} params
 * @returns {Promise<PurchasingRow[]>}
 */
export async function fetchPurchasing(params) {
  return rowsOf(await get('purchasing', params))
}
