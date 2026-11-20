import { httpClient } from '../../shared/api/httpClient'
import { STORE_KIND_CONFIG, type StoreKind } from '../store-orders/api'

/**
 * Sales reports of a vendor or restaurant (Phase 8 contract §3). Days are India-time dates, both inclusive,
 * counted on the day an order was received; at most 92 days (400 INVALID_PARAMETER otherwise).
 */

export const MAX_REPORT_DAYS = 92
/** The server's default range: the last 7 days including today. */
export const DEFAULT_REPORT_DAYS = 7

export const REPORT_ERRORS = { invalidParameter: 'INVALID_PARAMETER' } as const

export interface DailySales {
  date: string
  ordersReceived: number
  ordersAccepted: number
  grossSales: number
}

export interface TopItem {
  itemId: string
  name: string
  quantity: number
  sales: number
}

/** `SalesReport` */
export interface SalesReport {
  from: string
  to: string
  timezone: string
  currency: string
  /** Every store order created in the range. */
  ordersReceived: number
  /** Reached ACCEPTED or later (including later cancelled ones). */
  ordersAccepted: number
  /** REJECTED, of which `autoRejected` automatically at the deadline. */
  ordersRejected: number
  autoRejected: number
  /** Cancelled by the customer or an admin. */
  ordersCancelled: number
  /** ordersAccepted / ordersReceived (0–1, 2 decimals). */
  acceptanceRate: number
  /** Item totals of accepted, not cancelled orders (the delivery fee is not the store's). */
  grossSales: number
  itemsSold: number
  averageOrderValue: number
  /** Every day of the range, zeros included. */
  daily: DailySales[]
  /** Top 5 by quantity. */
  topItems: TopItem[]
}

export interface ReportRange {
  from: string
  to: string
}

export const storeReportKeys = {
  all: ['store-reports'] as const,
  ofKind: (kind: StoreKind) => [...storeReportKeys.all, kind] as const,
  sales: (kind: StoreKind, range: ReportRange) => [...storeReportKeys.ofKind(kind), 'sales', range] as const,
}

export async function fetchSalesReport(kind: StoreKind, range: ReportRange): Promise<SalesReport> {
  const path = `${STORE_KIND_CONFIG[kind].apiBase}/reports/sales`
  return (await httpClient.get<SalesReport>(path, { params: { from: range.from, to: range.to } })).data
}
