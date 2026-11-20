import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { CancellationReason, CancelledBy } from '../orders/api'

/**
 * A store's view of its orders (Phase 8 contract §2): vendor-service for GROCERY orders, restaurant-service for
 * RESTAURANT orders. Same API and shapes in both; only the path prefix and the defaults differ.
 */

export type StoreKind = 'VENDOR' | 'RESTAURANT'

/** Phase 9 adds `PICKED_UP`: the delivery partner took the order (terminal success; it leaves the board). */
export const STORE_ORDER_STATUSES = ['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'PICKED_UP', 'REJECTED', 'CANCELLED'] as const
export type StoreOrderStatus = (typeof STORE_ORDER_STATUSES)[number]
/** `ACTIVE` = NEW, ACCEPTED, PREPARING and READY_FOR_PICKUP (never PICKED_UP). */
export type StoreOrderStatusFilter = StoreOrderStatus | 'ACTIVE'

export function isStoreOrderStatus(value: unknown): value is StoreOrderStatus {
  return typeof value === 'string' && (STORE_ORDER_STATUSES as readonly string[]).includes(value)
}

/** What a store user may pick when rejecting. */
export const STORE_REJECT_REASONS = ['OUT_OF_STOCK', 'STORE_CLOSED', 'TOO_BUSY', 'OTHER'] as const
export type StoreRejectReason = (typeof STORE_REJECT_REASONS)[number]
/** NO_RESPONSE is set by the auto-reject at the deadline (`auto: true`). */
export type StoreRejectionReason = StoreRejectReason | 'NO_RESPONSE'

export interface StoreOrderItem {
  itemId: string
  name: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

/** Recipient name and a coarse area (landmark / line 2 or city) — never the phone or the full address. */
export interface StoreOrderCustomer {
  name: string | null
  area: string | null
}

export interface StoreOrderRejection {
  reason: StoreRejectionReason | (string & {})
  note: string | null
  /** True when the deadline passed without an answer. */
  auto: boolean
  at: string
}

export interface StoreOrderCancellation {
  reason: CancellationReason | (string & {})
  by: CancelledBy | (string & {})
  at: string
}

export interface StoreOrderTimelineEntry {
  status: StoreOrderStatus
  at: string
}

/** `StoreOrderView` */
export interface StoreOrderView {
  orderId: string
  orderNumber: string
  storeId: string
  status: StoreOrderStatus
  items: StoreOrderItem[]
  /** Sum of quantities. */
  itemCount: number
  /** Item total — what the store sells (the delivery fee is not the store's). */
  subtotal: number
  total: number
  currency: string
  customer: StoreOrderCustomer | null
  /** The customer's note, or null. */
  note: string | null
  receivedAt: string
  /** NEW orders not accepted by then are rejected automatically. */
  acceptBy: string
  acceptedAt: string | null
  readyInMinutes: number | null
  readyBy: string | null
  preparingAt: string | null
  readyAt: string | null
  /** Phase 9: not in the contract's StoreOrderView; read when present, else the PICKED_UP timeline entry. */
  pickedUpAt?: string | null
  rejection: StoreOrderRejection | null
  cancellation: StoreOrderCancellation | null
  timeline: StoreOrderTimelineEntry[]
}

export interface StoreOrderListParams {
  status?: StoreOrderStatusFilter
  page?: number
  /** ≤ 100. */
  size?: number
}

export interface RejectStoreOrderRequest {
  reason: StoreRejectReason
  /** ≤ 300; required for OTHER. */
  note?: string
}

/** Limits from the contract. */
export const STORE_ORDER_LIMITS = { readyInMin: 5, readyInMax: 180, rejectNote: 300, pageSize: 100 } as const

/** The "Ready in" choices on the board (minutes). */
export const READY_IN_CHOICES = [10, 15, 20, 30, 45, 60] as const

export const STORE_ORDER_ERRORS = {
  notFound: 'STORE_ORDER_NOT_FOUND',
  /** Accept / reject of an order that is no longer NEW (property `status`). */
  notPending: 'STORE_ORDER_NOT_PENDING',
  /** Accept after `acceptBy`. */
  acceptanceExpired: 'ACCEPTANCE_EXPIRED',
  /** Preparing / ready from a status that does not allow it (property `status`). */
  invalidTransition: 'INVALID_STORE_ORDER_TRANSITION',
} as const

export interface StoreKindConfig {
  /** API path prefix (relative to `/api`). */
  apiBase: string
  /** The board and the reports in the app. */
  ordersPath: string
  reportsPath: string
  /** Contract §2: vendor 15, restaurant 20. */
  defaultReadyInMinutes: number
  /** "Packing" for groceries, "Preparing" for restaurants. */
  preparingLabel: string
  startPreparingLabel: string
  storeNoun: string
}

export const STORE_KIND_CONFIG: Record<StoreKind, StoreKindConfig> = {
  VENDOR: {
    apiBase: '/vendor',
    ordersPath: '/vendor/orders',
    reportsPath: '/vendor/reports',
    defaultReadyInMinutes: 15,
    preparingLabel: 'Packing',
    startPreparingLabel: 'Start packing',
    storeNoun: 'store',
  },
  RESTAURANT: {
    apiBase: '/restaurant',
    ordersPath: '/restaurant/orders',
    reportsPath: '/restaurant/reports',
    defaultReadyInMinutes: 20,
    preparingLabel: 'Preparing',
    startPreparingLabel: 'Start preparing',
    storeNoun: 'restaurant',
  },
}

export const storeOrderKeys = {
  all: ['store-orders'] as const,
  ofKind: (kind: StoreKind) => [...storeOrderKeys.all, kind] as const,
  lists: (kind: StoreKind) => [...storeOrderKeys.ofKind(kind), 'list'] as const,
  list: (kind: StoreKind, params: StoreOrderListParams) => [...storeOrderKeys.lists(kind), params] as const,
  /** PICKED_UP + REJECTED + CANCELLED orders closed today (India time). */
  closedToday: (kind: StoreKind, day: string) => [...storeOrderKeys.ofKind(kind), 'closed', day] as const,
  /** Who collects the orders still at the store (delivery-service); refreshed with the board. */
  deliveries: (kind: StoreKind) => [...storeOrderKeys.ofKind(kind), 'deliveries'] as const,
}

/** The partner collecting an order: the store hands the order over to them and may call them. */
export interface StoreDeliveryPartner {
  /** Full name. */
  name: string
  phone: string | null
  /** BICYCLE, SCOOTER, MOTORCYCLE or CAR. */
  vehicleType: string | null
  /** Null for a bicycle. */
  vehicleNumber: string | null
}

export interface StoreDeliveryLocation {
  latitude: number
  longitude: number
  heading: number | null
  /** When the partner's phone sent it; an old one means the phone stopped reporting. */
  at: string
}

/**
 * `GET /api/delivery/store/deliveries` (docs/api/phase-9-delivery.md, store addendum): a delivery of an order still at
 * the store — PENDING (the store has not accepted yet), SEARCHING (looking for a partner) or ASSIGNED.
 */
export interface StoreDelivery {
  deliveryId: string
  orderId: string
  orderNumber: string
  status: 'PENDING' | 'SEARCHING' | 'ASSIGNED' | (string & {})
  /** SEARCHING and nobody was free within the largest radius yet (it keeps trying). */
  assignmentFailed: boolean
  assignedAt: string | null
  /** Null until a partner accepts. */
  partner: StoreDeliveryPartner | null
  /** ASSIGNED with a known position only (also the two fields below). */
  partnerLocation: StoreDeliveryLocation | null
  /** Straight line, partner → store, 0.1 km. */
  distanceToStoreKm: number | null
  etaToStoreMinutes: number | null
}

/** The caller's store's deliveries still at the store, oldest first (at most 100). */
export async function fetchStoreDeliveries(): Promise<StoreDelivery[]> {
  return (await httpClient.get<StoreDelivery[]>('/delivery/store/deliveries')).data
}

const ordersBase = (kind: StoreKind) => `${STORE_KIND_CONFIG[kind].apiBase}/orders`
const orderPath = (kind: StoreKind, orderId: string) => `${ordersBase(kind)}/${segment(orderId)}`

/** NEW first (by `acceptBy`, soonest first), then the rest newest first. */
export async function listStoreOrders(kind: StoreKind, params: StoreOrderListParams): Promise<PageResponse<StoreOrderView>> {
  return (await httpClient.get<PageResponse<StoreOrderView>>(ordersBase(kind), { params: cleanParams(params) })).data
}

/** Another store's order or an unknown id → 404 STORE_ORDER_NOT_FOUND. */
export async function fetchStoreOrder(kind: StoreKind, orderId: string): Promise<StoreOrderView> {
  return (await httpClient.get<StoreOrderView>(orderPath(kind, orderId))).data
}

/** 409 STORE_ORDER_NOT_PENDING / ACCEPTANCE_EXPIRED; accepting an ACCEPTED order again → 200 unchanged. */
export async function acceptStoreOrder(kind: StoreKind, orderId: string, readyInMinutes: number): Promise<StoreOrderView> {
  return (await httpClient.post<StoreOrderView>(`${orderPath(kind, orderId)}/accept`, { readyInMinutes })).data
}

/** Only from NEW (409 STORE_ORDER_NOT_PENDING); rejecting again → 200 unchanged. */
export async function rejectStoreOrder(kind: StoreKind, orderId: string, body: RejectStoreOrderRequest): Promise<StoreOrderView> {
  return (await httpClient.post<StoreOrderView>(`${orderPath(kind, orderId)}/reject`, body)).data
}

/** From ACCEPTED; 409 INVALID_STORE_ORDER_TRANSITION otherwise. */
export async function markStoreOrderPreparing(kind: StoreKind, orderId: string): Promise<StoreOrderView> {
  return (await httpClient.post<StoreOrderView>(`${orderPath(kind, orderId)}/preparing`)).data
}

/** From ACCEPTED or PREPARING; 409 INVALID_STORE_ORDER_TRANSITION otherwise. */
export async function markStoreOrderReady(kind: StoreKind, orderId: string): Promise<StoreOrderView> {
  return (await httpClient.post<StoreOrderView>(`${orderPath(kind, orderId)}/ready`)).data
}
