import { httpClient } from '../../shared/api/httpClient'
import { STORE_KIND_CONFIG } from '../store-orders/api'

/** @typedef {import('../store-orders/api').StoreKind} StoreKind */

/**
 * Sales reports of a vendor or restaurant (Phase 8 contract §3). Days are India-time dates, both inclusive,
 * counted on the day an order was received; at most 92 days (400 INVALID_PARAMETER otherwise).
 */

export const MAX_REPORT_DAYS = 92
/** The server's default range: the last 7 days including today. */
export const DEFAULT_REPORT_DAYS = 7

export const REPORT_ERRORS = { invalidParameter: 'INVALID_PARAMETER' }

/**
 * @typedef {object} DailySales
 * @property {string} date
 * @property {number} ordersReceived
 * @property {number} ordersAccepted
 * @property {number} grossSales
 */

/**
 * @typedef {object} TopItem
 * @property {string} itemId
 * @property {string} name
 * @property {number} quantity
 * @property {number} sales
 */

/**
 * `SalesReport`
 * @typedef {object} SalesReport
 * @property {string} from
 * @property {string} to
 * @property {string} timezone
 * @property {string} currency
 * @property {number} ordersReceived Every store order created in the range.
 * @property {number} ordersAccepted Reached ACCEPTED or later (including later cancelled ones).
 * @property {number} ordersRejected REJECTED, of which `autoRejected` automatically at the deadline.
 * @property {number} autoRejected
 * @property {number} ordersCancelled Cancelled by the customer or an admin.
 * @property {number} acceptanceRate ordersAccepted / ordersReceived (0–1, 2 decimals).
 * @property {number} grossSales Item totals of accepted, not cancelled orders (the delivery fee is not the store's).
 * @property {number} itemsSold
 * @property {number} averageOrderValue
 * @property {DailySales[]} daily Every day of the range, zeros included.
 * @property {TopItem[]} topItems Top 5 by quantity.
 */

/**
 * @typedef {object} ReportRange
 * @property {string} from
 * @property {string} to
 */

export const storeReportKeys = {
  all: ['store-reports'],
  ofKind: (kind) => [...storeReportKeys.all, kind],
  sales: (kind, range) => [...storeReportKeys.ofKind(kind), 'sales', range],
}

/**
 * @param {StoreKind} kind
 * @param {ReportRange} range
 * @returns {Promise<SalesReport>}
 */
export async function fetchSalesReport(kind, range) {
  const path = `${STORE_KIND_CONFIG[kind].apiBase}/reports/sales`
  return (await httpClient.get(path, { params: { from: range.from, to: range.to } })).data
}
