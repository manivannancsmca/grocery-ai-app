/**
 * What notification-service sends on `GET /api/notifications/stream` (Phase 7 contract §2–§4).
 * Payloads are parsed defensively: an unknown signal type or a malformed payload is ignored, never thrown.
 */

/** Connection state of the live-updates stream, as the UI shows it. */
export type RealtimeStatus = 'connecting' | 'live' | 'reconnecting' | 'offline'

/** SSE `event:` names of the stream. */
export const STREAM_EVENTS = { notification: 'notification', signal: 'signal', session: 'session' } as const

/**
 * `DELIVERY` (Phase 9) is assumed for the partner's and the delivery managers' notifications; `REVIEW` (Phase 12) for
 * the review notifications should notification-service give them a category of their own (returns are orders).
 * Types decide labels and icons anyway.
 */
export const NOTIFICATION_CATEGORIES = ['ORDER', 'PAYMENT', 'STOCK', 'PRICING', 'ACCOUNT', 'APPROVAL', 'DELIVERY', 'REVIEW'] as const
export type NotificationCategory = (typeof NOTIFICATION_CATEGORIES)[number]

export const NOTIFICATION_SEVERITIES = ['INFO', 'SUCCESS', 'WARNING', 'ERROR'] as const
export type NotificationSeverity = (typeof NOTIFICATION_SEVERITIES)[number]

/** Persistent notification types (Phase 7 contract §4, Phase 8 contract §6, Phase 9 §7, Phase 10 §4). */
export const NOTIFICATION_TYPES = [
  'ORDER_CONFIRMED',
  'NEW_ORDER',
  // Phase 8: the store accepted / finished the customer's order; a store's order was rejected at the deadline.
  'ORDER_ACCEPTED',
  'ORDER_READY',
  'ORDER_AUTO_REJECTED',
  'ORDER_REJECTED',
  'ORDER_CANCELLED',
  'PAYMENT_FAILED',
  'REFUND_COMPLETED',
  'REFUND_FAILED',
  'LOW_STOCK',
  'PRICE_APPROVAL_REQUESTED',
  'PRICE_CHANGE_APPROVED',
  'PRICE_CHANGE_REJECTED',
  'APPROVAL_REQUESTED',
  'ACCOUNT_APPROVED',
  'ACCOUNT_REJECTED',
  'ACCOUNT_SUSPENDED',
  'ACCOUNT_REINSTATED',
  // Phase 9 (contract §7). Partner: a new offer, the order is ready at the store, the delivery was taken away.
  'DELIVERY_OFFER',
  'PICKUP_READY',
  'DELIVERY_REASSIGNED',
  'DELIVERY_CANCELLED',
  // Customer: a partner accepted, picked up, delivered — or could not deliver.
  'PARTNER_ASSIGNED',
  'OUT_FOR_DELIVERY',
  'ORDER_DELIVERED',
  'DELIVERY_FAILED',
  // Admins (and delivery managers): a failed delivery to refund, a delivery nobody accepted.
  'DELIVERY_FAILED_ADMIN',
  'NO_DELIVERY_PARTNER',
  // Phase 10 (contract §4): the watchdog flagged an order (WARNING; SUPER_ADMIN, OPS_ADMIN, SUPPORT; `/admin/orders`).
  'STUCK_ORDER',
  // Phase 11 (contract §8): an assistant's action needs a decision, was not approved / expired, ran, or failed.
  'AI_APPROVAL_REQUESTED',
  'AI_APPROVAL_DECIDED',
  'AI_ACTION_COMPLETED',
  'AI_ACTION_FAILED',
  // Phase 12 §1 (reviews): a published review for the store owner (INFO, `/vendor/reviews` · `/restaurant/reviews`),
  // a flagged one for SUPER_ADMIN / OPS_ADMIN / SUPPORT (WARNING, `/admin/reviews`), and for the customer: their
  // review was rejected or removed, or the store replied (`/account/reviews`).
  'NEW_REVIEW',
  'REVIEW_FLAGGED',
  'REVIEW_NOT_PUBLISHED',
  'REVIEW_REPLY',
  // Phase 12 §3 (returns): requested (store owner, WARNING, `/vendor/returns` · `/restaurant/returns`), escalated
  // (admins, WARNING, `/admin/returns`), approved (customer, SUCCESS) or rejected (customer, WARNING, with the comment).
  'RETURN_REQUESTED',
  'RETURN_ESCALATED',
  'RETURN_APPROVED',
  'RETURN_REJECTED',
] as const
export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

/**
 * The contract's severity per type, used when a payload carries none (Phase 11 §8: requested INFO, not approved /
 * expired WARNING, done SUCCESS, failed ERROR).
 */
export const DEFAULT_SEVERITY_BY_TYPE: Partial<Record<NotificationType, NotificationSeverity>> = {
  AI_APPROVAL_REQUESTED: 'INFO',
  AI_APPROVAL_DECIDED: 'WARNING',
  AI_ACTION_COMPLETED: 'SUCCESS',
  AI_ACTION_FAILED: 'ERROR',
  // Phase 12 §1 / §3 (REVIEW_NOT_PUBLISHED has no severity in the contract: a warning, like the other refusals).
  NEW_REVIEW: 'INFO',
  REVIEW_FLAGGED: 'WARNING',
  REVIEW_NOT_PUBLISHED: 'WARNING',
  REVIEW_REPLY: 'INFO',
  RETURN_REQUESTED: 'WARNING',
  RETURN_ESCALATED: 'WARNING',
  RETURN_APPROVED: 'SUCCESS',
  RETURN_REJECTED: 'WARNING',
}

/** `NotificationView` — one inbox entry; its `id` (UUIDv7) is also the SSE `id:`. */
export interface NotificationView {
  id: string
  type: NotificationType | (string & {})
  category: NotificationCategory | (string & {})
  severity: NotificationSeverity | (string & {})
  title: string
  body: string
  /** In-app route, or null. */
  link: string | null
  data: Record<string, unknown>
  read: boolean
  createdAt: string
}

export type ApprovalKind = 'VENDOR' | 'RESTAURANT' | 'DELIVERY_PARTNER' | 'PRICE_CHANGE'
export type AccountKind = 'VENDOR' | 'RESTAURANT' | 'DELIVERY_PARTNER' | 'USER'

/** `data` of each signal type (contract §2). Ids are optional here: a signal without one refreshes more broadly. */
export interface SignalDataMap {
  ORDER_UPDATED: { orderId?: string; status?: string; event?: string; returnId?: string }
  ADMIN_ORDERS_CHANGED: { orderId?: string; event?: string }
  STOCK_CHANGED: { productId?: string; vendorId?: string; inStock?: boolean }
  PRICE_CHANGED: { productId?: string; vendorId?: string }
  RESTAURANT_UPDATED: { restaurantId?: string; acceptingOrders?: boolean; status?: string }
  INVENTORY_UPDATED: { productId?: string }
  PRICING_UPDATED: { productId?: string; requestId?: string }
  APPROVALS_CHANGED: { kind?: ApprovalKind }
  ACCOUNT_UPDATED: { kind?: AccountKind; status?: string }
  /** Phase 8: one of the signed-in store's orders changed (the order board refreshes in every open tab). */
  STORE_ORDERS_CHANGED: { orderId?: string; status?: string }
  /** Phase 9 (partner): an offer was made or closed (expired, rejected, withdrawn). */
  DELIVERY_OFFERS_CHANGED: { offerId?: string; deliveryId?: string; orderId?: string }
  /** Phase 9 (partner): the partner's delivery changed (e.g. assigned). */
  DELIVERY_UPDATED: { deliveryId?: string; orderId?: string }
  /** Phase 9 (SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER): any delivery event. */
  ADMIN_DELIVERIES_CHANGED: { deliveryId?: string; orderId?: string }
  /** Phase 9 (the order's customer): the partner's new position — patched into the tracking cache, never refetched. */
  DELIVERY_LOCATION: { orderId?: string; latitude?: number; longitude?: number; heading?: number | null; at?: string }
  /** Phase 11: an approval request changed (the requester, the customer it is for and its deciders). */
  AI_APPROVALS_CHANGED: { approvalId?: string }
  /** Phase 12 (the customer): one of their reviews was published, flagged, rejected, removed or replied to. */
  REVIEW_UPDATED: { reviewId?: string; status?: string; event?: string }
  /** Phase 12 (admins): a review needs moderation or was moderated. */
  ADMIN_REVIEWS_CHANGED: { reviewId?: string; status?: string; event?: string }
  /** Phase 12 (the store owner): a review of the store or its items appeared, disappeared or got a reply. */
  STORE_REVIEWS_CHANGED: { reviewId?: string; targetType?: string; targetId?: string; event?: string }
  /** Phase 12 (the store owner): one of the store's returns changed. */
  STORE_RETURNS_CHANGED: { returnId?: string; orderId?: string; status?: string }
  /** Phase 12 (admins): a return was requested, escalated or decided. */
  ADMIN_RETURNS_CHANGED: { returnId?: string; orderId?: string; status?: string }
  RESYNC: Record<string, never>
}

export type SignalType = keyof SignalDataMap

export const SIGNAL_TYPES: readonly SignalType[] = [
  'ORDER_UPDATED',
  'ADMIN_ORDERS_CHANGED',
  'STOCK_CHANGED',
  'PRICE_CHANGED',
  'RESTAURANT_UPDATED',
  'INVENTORY_UPDATED',
  'PRICING_UPDATED',
  'APPROVALS_CHANGED',
  'ACCOUNT_UPDATED',
  'STORE_ORDERS_CHANGED',
  'DELIVERY_OFFERS_CHANGED',
  'DELIVERY_UPDATED',
  'ADMIN_DELIVERIES_CHANGED',
  'DELIVERY_LOCATION',
  'AI_APPROVALS_CHANGED',
  'REVIEW_UPDATED',
  'ADMIN_REVIEWS_CHANGED',
  'STORE_REVIEWS_CHANGED',
  'STORE_RETURNS_CHANGED',
  'ADMIN_RETURNS_CHANGED',
  'RESYNC',
]

/** An ephemeral "something changed — refetch" message. Never stored, never replayed. */
export type Signal = { [K in SignalType]: { type: K; data: SignalDataMap[K]; at: string } }[SignalType]

export const SESSION_REASONS = ['TOKEN_EXPIRED', 'REVOKED', 'REPLACED'] as const
export type SessionReason = (typeof SESSION_REASONS)[number]

const APPROVAL_KINDS: readonly string[] = ['VENDOR', 'RESTAURANT', 'DELIVERY_PARTNER', 'PRICE_CHANGE']
const ACCOUNT_KINDS: readonly string[] = ['VENDOR', 'RESTAURANT', 'DELIVERY_PARTNER', 'USER']

function parseObject(json: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(json)
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function flag(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined
}

function finite(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function oneOf<T extends string>(value: unknown, allowed: readonly string[]): T | undefined {
  return typeof value === 'string' && allowed.includes(value) ? (value as T) : undefined
}

/** Drops undefined entries so a parsed payload only holds what the server sent. */
function defined<T extends object>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as T
}

/** `data:` of a `notification` event, or null when it is not a usable NotificationView. */
export function parseNotification(json: string): NotificationView | null {
  const raw = parseObject(json)
  const id = text(raw?.id)
  const type = text(raw?.type)
  const title = text(raw?.title)
  if (!raw || !id || !type || !title) return null
  const data = typeof raw.data === 'object' && raw.data !== null && !Array.isArray(raw.data) ? (raw.data as Record<string, unknown>) : {}
  return {
    id,
    type,
    category: text(raw.category) ?? 'ORDER',
    severity: text(raw.severity) ?? DEFAULT_SEVERITY_BY_TYPE[type as NotificationType] ?? 'INFO',
    title,
    body: typeof raw.body === 'string' ? raw.body : '',
    link: text(raw.link) ?? null,
    data,
    read: raw.read === true,
    createdAt: text(raw.createdAt) ?? new Date().toISOString(),
  }
}

/** `data:` of a `signal` event, or null for a malformed payload or a signal type this app does not know. */
export function parseSignal(json: string): Signal | null {
  const raw = parseObject(json)
  const type = oneOf<SignalType>(raw?.type, SIGNAL_TYPES)
  if (!raw || !type) return null
  const data = (typeof raw.data === 'object' && raw.data !== null ? raw.data : {}) as Record<string, unknown>
  const at = text(raw.at) ?? new Date().toISOString()

  switch (type) {
    case 'ORDER_UPDATED':
      return {
        type,
        at,
        data: defined({ orderId: text(data.orderId), status: text(data.status), event: text(data.event), returnId: text(data.returnId) }),
      }
    case 'ADMIN_ORDERS_CHANGED':
      return { type, at, data: defined({ orderId: text(data.orderId), event: text(data.event) }) }
    case 'STOCK_CHANGED':
      return { type, at, data: defined({ productId: text(data.productId), vendorId: text(data.vendorId), inStock: flag(data.inStock) }) }
    case 'PRICE_CHANGED':
      return { type, at, data: defined({ productId: text(data.productId), vendorId: text(data.vendorId) }) }
    case 'RESTAURANT_UPDATED':
      return {
        type,
        at,
        data: defined({ restaurantId: text(data.restaurantId), acceptingOrders: flag(data.acceptingOrders), status: text(data.status) }),
      }
    case 'INVENTORY_UPDATED':
      return { type, at, data: defined({ productId: text(data.productId) }) }
    case 'PRICING_UPDATED':
      return { type, at, data: defined({ productId: text(data.productId), requestId: text(data.requestId) }) }
    case 'APPROVALS_CHANGED':
      return { type, at, data: defined({ kind: oneOf<ApprovalKind>(data.kind, APPROVAL_KINDS) }) }
    case 'ACCOUNT_UPDATED':
      return { type, at, data: defined({ kind: oneOf<AccountKind>(data.kind, ACCOUNT_KINDS), status: text(data.status) }) }
    case 'STORE_ORDERS_CHANGED':
      return { type, at, data: defined({ orderId: text(data.orderId), status: text(data.status) }) }
    case 'DELIVERY_OFFERS_CHANGED':
      return { type, at, data: defined({ offerId: text(data.offerId), deliveryId: text(data.deliveryId), orderId: text(data.orderId) }) }
    case 'DELIVERY_UPDATED':
    case 'ADMIN_DELIVERIES_CHANGED':
      return { type, at, data: defined({ deliveryId: text(data.deliveryId), orderId: text(data.orderId) }) }
    case 'DELIVERY_LOCATION':
      return {
        type,
        at,
        data: defined({
          orderId: text(data.orderId),
          latitude: finite(data.latitude),
          longitude: finite(data.longitude),
          heading: data.heading === null ? null : finite(data.heading),
          // The position's own time; the envelope's `at` is when the signal was sent.
          at: text(data.at) ?? at,
        }),
      }
    case 'AI_APPROVALS_CHANGED':
      return { type, at, data: defined({ approvalId: text(data.approvalId) }) }
    case 'REVIEW_UPDATED':
    case 'ADMIN_REVIEWS_CHANGED':
      return { type, at, data: defined({ reviewId: text(data.reviewId), status: text(data.status), event: text(data.event) }) }
    case 'STORE_REVIEWS_CHANGED':
      return {
        type,
        at,
        data: defined({
          reviewId: text(data.reviewId),
          targetType: text(data.targetType),
          targetId: text(data.targetId),
          event: text(data.event),
        }),
      }
    case 'STORE_RETURNS_CHANGED':
    case 'ADMIN_RETURNS_CHANGED':
      return { type, at, data: defined({ returnId: text(data.returnId), orderId: text(data.orderId), status: text(data.status) }) }
    case 'RESYNC':
      return { type, at, data: {} }
  }
}

/** `data:` of a `session` event (`{ "reason": … }`), or null. */
export function parseSessionReason(json: string): SessionReason | null {
  return oneOf<SessionReason>(parseObject(json)?.reason, SESSION_REASONS) ?? null
}
