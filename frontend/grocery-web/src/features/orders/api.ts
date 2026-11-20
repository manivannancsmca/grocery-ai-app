import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { FoodType } from '../../shared/utils/foodType'
import type { CartItemType, CartStore, StoreType } from '../cart/api'
import type { OrderReturnSummary } from '../returns/api'

/** Orders (order-service — Phase 6 contract §2 and §4; Phase 8 contract §4 adds the store's fulfilment steps). */

export const ORDER_STATUSES = [
  'CREATED',
  'AWAITING_PAYMENT',
  'CONFIRMED',
  // Phase 8: the store accepted it, is preparing / packing it, and it is ready for a delivery partner.
  'ACCEPTED',
  'PREPARING',
  'READY_FOR_PICKUP',
  // Phase 9: the partner picked it up, delivered it, or could not deliver it (admins decide on a refund).
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'DELIVERY_FAILED',
  'REJECTED',
  'CANCELLED',
] as const
export type OrderStatus = (typeof ORDER_STATUSES)[number]
/** `GET /api/orders?status=` also accepts `OPEN` (CREATED + AWAITING_PAYMENT). */
export type OrderStatusFilter = OrderStatus | 'OPEN'

export type OrderType = StoreType
export const ORDER_TYPES: readonly OrderType[] = ['GROCERY', 'RESTAURANT']

export function isOrderStatus(value: unknown): value is OrderStatus {
  return typeof value === 'string' && (ORDER_STATUSES as readonly string[]).includes(value)
}

export function isOrderType(value: unknown): value is OrderType {
  return typeof value === 'string' && (ORDER_TYPES as readonly string[]).includes(value)
}

/** order-service's own view of the payment, kept from payment events. */
export type OrderPaymentStatus =
  | 'NOT_STARTED'
  | 'PENDING'
  | 'PAID'
  | 'EXPIRED'
  | 'NOT_REQUIRED'
  | 'REFUND_PENDING'
  | 'REFUNDED'
  | 'PARTIALLY_REFUNDED'

export type CancellationReason =
  | 'CUSTOMER_CANCELLED'
  | 'ADMIN_CANCELLED'
  | 'PAYMENT_TIMEOUT'
  | 'RESERVATION_EXPIRED'
  /** Phase 8: the store rejected the order (note = "<reason>: <note>"). */
  | 'STORE_REJECTED'
  /** Phase 8: nobody at the store accepted it before the deadline. */
  | 'STORE_NO_RESPONSE'
export type CancelledBy = 'CUSTOMER' | 'ADMIN' | 'SYSTEM' | 'STORE'

export interface OrderItem {
  itemId: string
  type: CartItemType
  name: string
  unitLabel: string | null
  imageUrl: string | null
  foodType: FoodType | null
  quantity: number
  unitPrice: number
  /** Grocery only. */
  mrp: number | null
  lineTotal: number
}

/** Snapshot of the delivery address taken when the order was placed. */
export interface OrderAddress {
  label: string | null
  recipientName: string
  phone: string
  line1: string
  line2: string | null
  landmark: string | null
  city: string
  state: string
  postalCode: string
  latitude: number | null
  longitude: number | null
}

export interface PaymentFailure {
  code: string
  description: string
  at: string
}

export interface OrderPayment {
  status: OrderPaymentStatus
  /** End of the payment window (15 minutes from creation). */
  dueAt: string | null
  paidAt: string | null
  refundedAmount: number
  /** Most recent declined attempt while the order is still payable. */
  lastFailure: PaymentFailure | null
}

export interface OrderCancellation {
  reason: CancellationReason
  note: string | null
  by: CancelledBy
  at: string
}

export interface UnavailableItem {
  itemId: string
  name: string
  requested: number
  available: number
}

export interface OrderRejection {
  reason: 'OUT_OF_STOCK' | (string & {})
  unavailableItems: UnavailableItem[]
}

export interface OrderTimelineEntry {
  status: OrderStatus
  at: string
  reason: string | null
}

/** Phase 9: the delivery partner as order-service records it (first name only for customers). */
export interface OrderDelivery {
  partnerName: string | null
  assignedAt: string | null
  pickedUpAt: string | null
  deliveredAt: string | null
}

export interface OrderView {
  id: string
  /** Short and unique, shown to people (e.g. `GA-7K3F9Q2M`). */
  orderNumber: string
  type: OrderType
  status: OrderStatus
  store: CartStore
  items: OrderItem[]
  /** Sum of quantities. */
  itemCount: number
  subtotal: number
  mrpTotal: number
  savings: number
  deliveryFee: number
  total: number
  currency: string
  deliveryAddress: OrderAddress
  deliveryDistanceKm: number | null
  note: string | null
  payment: OrderPayment
  /** Customer cancel: only until the store accepts (CREATED, AWAITING_PAYMENT, CONFIRMED). */
  cancellable: boolean
  cancellation: OrderCancellation | null
  rejection: OrderRejection | null
  /** Phase 8: set when the store accepts (null before; absent from older answers). */
  acceptedAt?: string | null
  /** Phase 8: the preparation time the store promised, in minutes. */
  readyInMinutes?: number | null
  /** Phase 8: `acceptedAt + readyInMinutes`. */
  readyBy?: string | null
  /** Phase 9: null until a partner accepts; absent from older answers. */
  delivery?: OrderDelivery | null
  /** Phase 9: live tracking applies (from ACCEPTED to OUT_FOR_DELIVERY). */
  trackable?: boolean
  /** Phase 12: the coupon redeemed for this order (null / absent without one). */
  couponCode?: string | null
  /** Phase 12: coupon discount off the subtotal (`total` already has it taken off). */
  discount?: number | null
  /** Phase 12: delivery fee taken off by a FREE_DELIVERY coupon. */
  deliveryFeeDiscount?: number | null
  /**
   * Phase 12: the customer may request a return now (DELIVERED, within the return window — a platform setting, 48 h by
   * default — no open request, something left).
   */
  returnable?: boolean
  /** Phase 12: the order's return requests. */
  returns?: OrderReturnSummary[]
  /** Oldest first. */
  timeline: OrderTimelineEntry[]
  createdAt: string
  updatedAt: string
}

/** The payment as the lists give it. */
export interface OrderSummaryPayment {
  status: OrderPaymentStatus
  /**
   * End of the payment window (`OrderPayment.dueAt`), only while the order is AWAITING_PAYMENT (null otherwise).
   * Absent from older answers.
   */
  dueAt?: string | null
}

export interface OrderSummary {
  id: string
  orderNumber: string
  type: OrderType
  status: OrderStatus
  store: CartStore
  itemCount: number
  total: number
  currency: string
  firstItemName: string
  payment: OrderSummaryPayment
  createdAt: string
  /** Phase 12. */
  couponCode?: string | null
  discount?: number | null
  deliveryFeeDiscount?: number | null
}

export interface PlaceOrderRequest {
  /** Absent → the default address (same rule as the checkout preview). */
  addressId?: string
  /** The total the customer saw; must equal the live total (409 TOTAL_CHANGED otherwise). */
  expectedTotal: number
  /** Delivery instructions, ≤ 300. */
  note?: string
}

/** Limits from the contract. */
export const ORDER_LIMITS = { note: 300, cancelReason: 300 } as const

/** Error codes of the order endpoints. */
export const ORDER_ERRORS = {
  checkoutBlocked: 'CHECKOUT_BLOCKED',
  totalChanged: 'TOTAL_CHANGED',
  dependencyUnavailable: 'DEPENDENCY_UNAVAILABLE',
  keyReused: 'IDEMPOTENCY_KEY_REUSED',
  addressNotFound: 'ADDRESS_NOT_FOUND',
  notFound: 'ORDER_NOT_FOUND',
  notCancellable: 'ORDER_NOT_CANCELLABLE',
  /** Phase 12: redeeming the cart's coupon failed (`reason`); no order was placed. */
  couponNotApplicable: 'COUPON_NOT_APPLICABLE',
} as const

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

export interface MyOrderListParams {
  status?: OrderStatusFilter
  page?: number
  size?: number
}

export const orderKeys = {
  all: ['orders'] as const,
  lists: () => [...orderKeys.all, 'list'] as const,
  list: (params: MyOrderListParams) => [...orderKeys.lists(), params] as const,
  detail: (id: string) => [...orderKeys.all, 'detail', id] as const,
}

const item = (id: string) => `/orders/${segment(id)}`

/** `201 OrderView`; a replay of the same key + body answers 201 with the original order. */
export async function placeOrder(body: PlaceOrderRequest, idempotencyKey: string): Promise<OrderView> {
  return (await httpClient.post<OrderView>('/orders', body, { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } })).data
}

/** Newest first. */
export async function listMyOrders(params: MyOrderListParams): Promise<PageResponse<OrderSummary>> {
  return (await httpClient.get<PageResponse<OrderSummary>>('/orders', { params: cleanParams(params) })).data
}

/** Another customer's order → 404 ORDER_NOT_FOUND. */
export async function fetchOrder(id: string): Promise<OrderView> {
  return (await httpClient.get<OrderView>(item(id))).data
}

/** Idempotent; 409 ORDER_NOT_CANCELLABLE. `reason` optional (≤ 300). */
export async function cancelOrder(id: string, reason: string | null): Promise<OrderView> {
  return (await httpClient.post<OrderView>(`${item(id)}/cancel`, reason ? { reason } : {})).data
}
