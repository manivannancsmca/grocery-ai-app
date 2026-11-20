import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { OrderStatus, OrderSummary, OrderType, OrderView } from '../orders/api'

/** Admin order monitoring (order-service — Phase 6 contract §4). Reads: any admin role. */

/**
 * Phase 8: admins may cancel any non-terminal order up to READY_FOR_PICKUP, which `adminCancellable` reports
 * (customers only until the store accepts — `cancellable`). Older answers without it fall back to `cancellable`.
 */
interface AdminCancelRule {
  adminCancellable?: boolean
}

export type AdminOrderSummary = OrderSummary & AdminCancelRule & { customerId: string }
export type AdminOrderView = OrderView & AdminCancelRule & { customerId: string }

/** Phase 9: once a partner picked the order up, nobody can cancel it — not even support. */
const PAST_PICKUP: readonly OrderStatus[] = ['OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED']

/** Whether the admin "Cancel order" action applies to this order. */
export function adminCanCancel(order: AdminCancelRule & { cancellable?: boolean; status?: OrderStatus }): boolean {
  if (order.status && PAST_PICKUP.includes(order.status)) return false
  return order.adminCancellable ?? order.cancellable ?? false
}

export interface AdminOrderListParams {
  status?: OrderStatus
  type?: OrderType
  customerId?: string
  storeId?: string
  /** Order number: exact, case-insensitive. */
  q?: string
  /** Dates (`YYYY-MM-DD`, India time), inclusive. */
  from?: string
  to?: string
  page?: number
  size?: number
}

/** `GET /api/admin/stats` — one day in India time. */
export interface OrderStats {
  date: string
  timezone: string
  /** Orders created that day. */
  orders: number
  byStatus: Partial<Record<OrderStatus, number>>
  byType: Partial<Record<OrderType, number>>
  /** Sum of totals of orders confirmed (paid) that day. */
  confirmedRevenue: number
  averageOrderValue: number
  currency: string
}

/** Phase 10 §4: why the watchdog flagged an order (status → threshold). */
export const STUCK_ORDER_REASONS = [
  'STOCK_CHECK_OVERDUE',
  'PAYMENT_EXPIRY_OVERDUE',
  'STORE_RESPONSE_OVERDUE',
  'PREPARATION_OVERDUE',
  'PICKUP_OVERDUE',
  'DELIVERY_OVERDUE',
] as const
export type StuckOrderReason = (typeof STUCK_ORDER_REASONS)[number]

/** One entry of `GET /api/admin/orders/stuck` (longest overdue first). */
export interface StuckOrder {
  orderId: string
  orderNumber: string
  type: OrderType
  status: OrderStatus
  reason: StuckOrderReason | (string & {})
  /** When the order entered `status`. */
  since: string
  detectedAt: string
  minutesOverdue: number
  store: { id: string; name: string }
  customerId: string
}

export type OrderEventDirection = 'PUBLISHED' | 'CONSUMED'
/** `IGNORED` = rejected by a state guard (reason in `detail`); `DUPLICATE` = already processed. */
export type OrderEventOutcome = 'APPLIED' | 'IGNORED' | 'DUPLICATE'

/** One entry of `GET /api/admin/orders/{id}/events` (Phase 10 §5), oldest first. */
export interface OrderEventEntry {
  eventId: string
  eventType: string
  sourceTopic: string | null
  direction: OrderEventDirection | (string & {})
  outcome: OrderEventOutcome | (string & {})
  detail: string | null
  occurredAt: string | null
  recordedAt: string
  correlationId: string | null
}

export const adminOrderKeys = {
  all: ['admin', 'orders'] as const,
  list: (params: AdminOrderListParams) => [...adminOrderKeys.all, 'list', params] as const,
  detail: (id: string) => [...adminOrderKeys.all, 'detail', id] as const,
  /** Under `all`, so every ADMIN_ORDERS_CHANGED signal (and STUCK_ORDER notification) refreshes them. */
  stuck: () => [...adminOrderKeys.all, 'stuck'] as const,
  events: (id: string) => [...adminOrderKeys.all, 'events', id] as const,
  /** Every day's stats — the prefix of `stats(date)`. */
  allStats: () => ['admin', 'order-stats'] as const,
  stats: (date: string | null) => [...adminOrderKeys.allStats(), date] as const,
}

/** `YYYY-MM-DD` — the `from` / `to` filters of the admin order and payment lists (`<input type="date">` value). */
export function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

const base = '/admin/orders'

export async function listAdminOrders(params: AdminOrderListParams): Promise<PageResponse<AdminOrderSummary>> {
  return (await httpClient.get<PageResponse<AdminOrderSummary>>(base, { params: cleanParams(params) })).data
}

export async function fetchAdminOrder(id: string): Promise<AdminOrderView> {
  return (await httpClient.get<AdminOrderView>(`${base}/${segment(id)}`)).data
}

/** SUPER_ADMIN, OPS_ADMIN, SUPPORT · reason required (≤ 300) · 409 ORDER_NOT_CANCELLABLE. */
export async function adminCancelOrder(id: string, reason: string): Promise<AdminOrderView> {
  return (await httpClient.post<AdminOrderView>(`${base}/${segment(id)}/cancel`, { reason })).data
}

/** Both Phase 10 lists are plain JSON arrays; anything else is reported instead of breaking the page. */
function listOf<T>(data: unknown): T[] {
  if (!Array.isArray(data)) throw new Error('Unexpected answer from the order service.')
  return data as T[]
}

/** Phase 10 §4 · any admin · longest overdue first. */
export async function fetchStuckOrders(): Promise<StuckOrder[]> {
  return listOf<StuckOrder>((await httpClient.get<unknown>(`${base}/stuck`)).data)
}

/** Phase 10 §5 · any admin · oldest first · 404 ORDER_NOT_FOUND. */
export async function fetchOrderEvents(id: string): Promise<OrderEventEntry[]> {
  return listOf<OrderEventEntry>((await httpClient.get<unknown>(`${base}/${segment(id)}/events`)).data)
}

/** Without `date`: today in India time. */
export async function fetchOrderStats(date: string | null = null): Promise<OrderStats> {
  return (await httpClient.get<OrderStats>('/admin/stats', { params: cleanParams({ date }) })).data
}
