import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
import type { FoodType } from '../../shared/utils/foodType'

/** Cart and checkout preview (cart-service, role CUSTOMER — Phase 5 contract §2–3). */

export type StoreType = 'GROCERY' | 'RESTAURANT'
export type CartItemType = 'PRODUCT' | 'MENU_ITEM'

/** Issue codes (contract §2). Every code except PRICE_CHANGED blocks checkout. */
export const ISSUE_CODES = [
  'PRICE_CHANGED',
  'ITEM_UNAVAILABLE',
  'OUT_OF_STOCK',
  'INSUFFICIENT_STOCK',
  'STORE_UNAVAILABLE',
  'STORE_CLOSED',
  'NOT_ACCEPTING_ORDERS',
  'PRICING_UNAVAILABLE',
  'AVAILABILITY_UNKNOWN',
  'ADDRESS_REQUIRED',
  'ADDRESS_LOCATION_MISSING',
  'OUT_OF_DELIVERY_AREA',
  'BELOW_MINIMUM_ORDER',
  'EMPTY_CART',
  // Phase 12 §2: the applied coupon is no longer valid (blocking until it is removed or the cart is fixed).
  'COUPON_NOT_APPLICABLE',
] as const
export type IssueCode = (typeof ISSUE_CODES)[number]

export interface CartStore {
  id: string
  type: StoreType
  name: string
}

export interface CartLine {
  /** productId or menuItemId. */
  itemId: string
  type: CartItemType
  name: string
  unitLabel: string | null
  imageUrl: string | null
  foodType: FoodType | null
  quantity: number
  /** Live selling price; null when it cannot be priced now. */
  unitPrice: number | null
  /** Grocery only. */
  mrp: number | null
  lineTotal: number | null
  /** Unit price when the line was created or its quantity last changed. */
  priceWhenAdded: number
  /** Grocery only: live available stock. */
  availableQuantity: number | null
  /** The line's issue code, if any. */
  issue: IssueCode | null
}

export interface CartIssue {
  code: IssueCode
  itemId: string | null
  blocking: boolean
  message: string
}

/** Why a coupon cannot be used (Phase 12 contract §2). */
export const COUPON_REASONS = [
  'COUPON_NOT_FOUND',
  'COUPON_PAUSED',
  'COUPON_NOT_STARTED',
  'COUPON_EXPIRED',
  'COUPON_WRONG_STORE',
  'COUPON_MIN_ORDER',
  'COUPON_USAGE_LIMIT',
  'COUPON_CUSTOMER_LIMIT',
] as const
export type CouponReason = (typeof COUPON_REASONS)[number]

/** The coupon applied to the cart, as the checkout preview evaluates it now. */
export interface CartCoupon {
  code: string
  /** false: it stays visible with the blocking issue COUPON_NOT_APPLICABLE until removed or the cart is fixed. */
  valid: boolean
  /** Off the item subtotal. */
  discount: number
  /** FREE_DELIVERY: the delivery fee taken off. */
  deliveryFeeDiscount: number
  reason?: CouponReason | (string & {}) | null
  message?: string | null
  /** With COUPON_MIN_ORDER (optional). */
  minOrderSubtotal?: number | null
}

export interface CartView {
  /** null for an empty cart. */
  store: CartStore | null
  lines: CartLine[]
  /** Sum of quantities. */
  itemCount: number
  /** Sum of lineTotal of priced lines. */
  subtotal: number
  mrpTotal: number
  savings: number
  currency: string
  issues: CartIssue[]
  /** null for a cart that was never written. */
  updatedAt: string | null
  /** Phase 12: the applied coupon (always on the preview; the cart's own answers may carry it too). */
  coupon?: CartCoupon | null
  /** Phase 12: coupon discount off the subtotal (0 without one). */
  discount?: number
  /** Phase 12: delivery fee taken off by a FREE_DELIVERY coupon (0 without one). */
  deliveryFeeDiscount?: number
}

export interface CheckoutAddress {
  id: string
  label: string
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

export interface DeliveryQuote {
  distanceKm: number
  fee: number
  /** Subtotal above which delivery is free; null = never free (restaurants). */
  freeAbove: number | null
  maxDistanceKm: number
  /** null = no minimum (restaurants). */
  minimumOrder: number | null
}

/** `GET /api/cart/preview` — CartView plus address, delivery fee and total. */
export interface CheckoutPreview extends CartView {
  address: CheckoutAddress | null
  /** null when there is no usable address or the cart is empty. */
  delivery: DeliveryQuote | null
  /** subtotal + delivery fee − discount − deliveryFeeDiscount (all prices include taxes). */
  total: number
  /** No blocking issue. */
  canCheckout: boolean
  pricedAt: string
}

/** What to add — exactly one kind of item. */
export type CartItemTarget =
  | { type: 'PRODUCT'; productId: string }
  | { type: 'MENU_ITEM'; restaurantId: string; menuItemId: string }

/** `AddItemRequest`; `replaceCart: true` empties a cart from another store first. */
export type AddItemRequest = CartItemTarget & { quantity: number; replaceCart?: boolean }

/** 1–20 per line (`422 QUANTITY_LIMIT` above). */
export const MAX_LINE_QUANTITY = 20
/** At most 30 lines (`422 CART_LIMIT_REACHED`). */
export const MAX_CART_LINES = 30

export const CART_STORE_CONFLICT = 'CART_STORE_CONFLICT'
/** 422 on `PUT /api/cart/coupon` (with `reason` and, for COUPON_MIN_ORDER, `minOrderSubtotal`); 409 on placement. */
export const COUPON_NOT_APPLICABLE = 'COUPON_NOT_APPLICABLE'
/** Codes are stored upper-case: 4–20 letters, digits or dashes. */
export const COUPON_CODE_PATTERN = /^[A-Z0-9-]{4,20}$/

/** The id a cart line carries for this target (productId or menuItemId). */
export function targetItemId(target: CartItemTarget): string {
  return target.type === 'PRODUCT' ? target.productId : target.menuItemId
}

export function findLine(cart: CartView | undefined, type: CartItemType, itemId: string): CartLine | undefined {
  return cart?.lines.find((line) => line.type === type && line.itemId === itemId)
}

export function emptyCart(currency = 'INR'): CartView {
  return {
    store: null,
    lines: [],
    itemCount: 0,
    subtotal: 0,
    mrpTotal: 0,
    savings: 0,
    currency,
    issues: [],
    updatedAt: new Date().toISOString(),
  }
}

export const cartKeys = {
  /** Everything cart-related; the cart itself is the single query `['cart']`. */
  all: ['cart'] as const,
  cart: () => cartKeys.all,
  previews: () => [...cartKeys.all, 'preview'] as const,
  preview: (addressId: string | null) => [...cartKeys.previews(), addressId] as const,
  /** Mutation key of every write touching one item (so all its steppers know it is busy). */
  itemWrite: (itemId: string) => ['cart-write', itemId] as const,
  clear: () => ['cart-write', '*clear*'] as const,
  /** Phase 12: applying or removing the coupon. */
  couponWrite: () => ['cart-write', '*coupon*'] as const,
}

export async function fetchCart(): Promise<CartView> {
  return (await httpClient.get<CartView>('/cart')).data
}

export async function addCartItem(body: AddItemRequest): Promise<CartView> {
  return (await httpClient.post<CartView>('/cart/items', body)).data
}

/** 0–20; 0 removes the line. */
export async function updateCartItem(itemId: string, quantity: number): Promise<CartView> {
  return (await httpClient.put<CartView>(`/cart/items/${segment(itemId)}`, { quantity })).data
}

export async function removeCartItem(itemId: string): Promise<CartView> {
  return (await httpClient.delete<CartView>(`/cart/items/${segment(itemId)}`)).data
}

export async function clearCart(): Promise<void> {
  await httpClient.delete('/cart')
}

/** Phase 12: validates the code (one coupon per cart; a new one replaces the old). 422 COUPON_NOT_APPLICABLE. */
export async function applyCartCoupon(code: string): Promise<CartView> {
  return (await httpClient.put<CartView>('/cart/coupon', { code })).data
}

export async function removeCartCoupon(): Promise<CartView> {
  return (await httpClient.delete<CartView>('/cart/coupon')).data
}

/** Without `addressId` the customer's default address is used. */
export async function fetchCheckoutPreview(addressId: string | null): Promise<CheckoutPreview> {
  return (await httpClient.get<CheckoutPreview>('/cart/preview', { params: cleanParams({ addressId }) })).data
}
