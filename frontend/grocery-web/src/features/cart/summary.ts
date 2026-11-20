import type { CartCoupon, CartIssue, CartLine, CartView, CheckoutPreview, DeliveryQuote } from './api'
import { cartLevelIssues, issueText, lineIssue, roundMoney } from './issues'

/** Below this many in stock, a line says "Only 3 left" (it also explains a "+" that stopped at the stock). */
export const LOW_STOCK_THRESHOLD = 5

const clampPercent = (value: number) => Math.min(100, Math.max(4, value))

/** How far the items are from free delivery (grocery stores with a threshold), or that it is reached. */
export type FreeDeliveryProgress =
  | { kind: 'short'; shortfall: number; freeAbove: number; percent: number }
  | { kind: 'reached'; freeAbove: number }

/**
 * Free delivery for this subtotal: "add ₹X more" while a fee is charged below the threshold, "reached" once the fee
 * is waived by it. Null without a threshold (restaurants), without a quote, or when a coupon already pays the fee.
 */
export function freeDeliveryProgress(
  subtotal: number,
  delivery: DeliveryQuote | null | undefined,
  deliveryFeeDiscount = 0,
): FreeDeliveryProgress | null {
  if (!delivery || delivery.freeAbove === null) return null
  const missing = roundMoney(delivery.freeAbove - subtotal)
  if (missing > 0) {
    if (delivery.fee <= 0 || deliveryFeeDiscount >= delivery.fee) return null
    return { kind: 'short', shortfall: missing, freeAbove: delivery.freeAbove, percent: clampPercent((subtotal / delivery.freeAbove) * 100) }
  }
  return delivery.fee === 0 ? { kind: 'reached', freeAbove: delivery.freeAbove } : null
}

export interface MinimumOrderGap {
  minimum: number
  shortfall: number
  percent: number
}

/** How much is missing to reach the store's minimum order, or null when there is none or it is reached. */
export function minimumOrderGap(subtotal: number, delivery: DeliveryQuote | null | undefined): MinimumOrderGap | null {
  const minimum = delivery?.minimumOrder
  if (minimum === null || minimum === undefined || minimum <= 0) return null
  const missing = roundMoney(minimum - subtotal)
  return missing > 0 ? { minimum, shortfall: missing, percent: clampPercent((subtotal / minimum) * 100) } : null
}

/** What the line saves against its MRP (grocery), in rupees. */
export function lineSaving(line: Pick<CartLine, 'mrp' | 'unitPrice' | 'quantity'>): number {
  if (line.mrp === null || line.unitPrice === null || line.mrp <= line.unitPrice) return 0
  return roundMoney((line.mrp - line.unitPrice) * line.quantity)
}

/** "18" for 18 % off the MRP; 0 without a discount. */
export function discountPercent(line: Pick<CartLine, 'mrp' | 'unitPrice'>): number {
  if (line.mrp === null || line.unitPrice === null || line.mrp <= line.unitPrice) return 0
  return Math.round(((line.mrp - line.unitPrice) / line.mrp) * 100)
}

const issueKey = (issue: CartIssue) => `${issue.code}-${issue.itemId ?? 'cart'}`

/**
 * Every issue of the cart page, without repeats: the cart's own (lines and store; fresh after every write) and, from
 * the checkout preview, the ones that are not about a line (address, delivery area, minimum order, coupon). A preview
 * still being re-fetched after a write may talk about lines that changed or went meanwhile, so its line issues are not
 * used.
 */
export function cartPageIssues(cart: Pick<CartView, 'issues' | 'lines'>, preview?: Pick<CheckoutPreview, 'issues'> | null): CartIssue[] {
  const fromLines = cart.lines.map((line) => lineIssue(cart, line)).filter((issue): issue is CartIssue => issue !== null)
  const all = [...fromLines, ...cartLevelIssues(cart), ...(preview ? preview.issues.filter((issue) => !issue.itemId) : [])]
  const seen = new Set<string>()
  return all.filter((issue) => {
    const key = issueKey(issue)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** Store-wide reasons the cart cannot be ordered now (shown as a pill next to the store's name). */
export function storeStatusLabel(issues: readonly CartIssue[]): string | null {
  const codes = new Set(issues.filter((issue) => !issue.itemId).map((issue) => issue.code))
  if (codes.has('STORE_UNAVAILABLE')) return 'Unavailable'
  if (codes.has('STORE_CLOSED')) return 'Closed now'
  if (codes.has('NOT_ACCEPTING_ORDERS')) return 'Paused new orders'
  return null
}

/** Issues that clear up on their own (a check that could not run): worth a "Check again". */
export function hasTransientIssue(issues: readonly CartIssue[]): boolean {
  return issues.some((issue) => issue.code === 'PRICING_UNAVAILABLE' || issue.code === 'AVAILABILITY_UNKNOWN')
}

/**
 * What the live region reads after a re-check: every issue as one sentence each ("Tomato now costs ₹50 (was ₹90).
 * Onion is sold out. …"), or that everything is fine.
 */
export function issuesAnnouncement(issues: readonly CartIssue[], context: Parameters<typeof issueText>[1]): string {
  if (issues.length === 0) return 'Prices and stock checked: everything in your cart is available.'
  return `Prices and stock checked. ${issues.map((issue) => issueText(issue, context)).join(' ')}`
}

/** The cart page's bill: from the checkout preview (with the delivery fee) or, until it is there, from the cart. */
export interface CartBill {
  itemCount: number
  subtotal: number
  mrpTotal: number
  /** null without a preview or a usable address. */
  delivery: DeliveryQuote | null
  /** The coupon whose discounts are in the bill (its code names the rows). */
  coupon: CartCoupon | null
  discount: number
  deliveryFeeDiscount: number
  total: number
  /** The total includes the delivery fee (a preview with a quote). */
  complete: boolean
  /** MRP savings, the coupon and a waived delivery fee together. */
  saved: number
  minimum: MinimumOrderGap | null
  /** Not shown while the minimum order is not reached (one nudge at a time). */
  freeDelivery: FreeDeliveryProgress | null
}

export function cartBill(cart: CartView, preview: CheckoutPreview | null | undefined, coupon: CartCoupon | null): CartBill {
  const source = preview ?? cart
  const valid = coupon?.valid ? coupon : null
  const discount = preview?.discount ?? valid?.discount ?? 0
  const deliveryFeeDiscount = preview ? (preview.deliveryFeeDiscount ?? valid?.deliveryFeeDiscount ?? 0) : 0
  const delivery = preview?.delivery ?? null
  // No nudge towards free delivery to an address the store does not deliver to.
  const undeliverable = preview?.issues.some((issue) => issue.code === 'OUT_OF_DELIVERY_AREA' || issue.code === 'ADDRESS_LOCATION_MISSING') ?? false
  const minimum = minimumOrderGap(source.subtotal, delivery)
  return {
    itemCount: source.itemCount,
    subtotal: source.subtotal,
    mrpTotal: source.mrpTotal,
    delivery,
    coupon,
    discount,
    deliveryFeeDiscount,
    total: preview ? preview.total : roundMoney(cart.subtotal - discount),
    complete: Boolean(preview?.delivery),
    saved: roundMoney(source.savings + discount + deliveryFeeDiscount),
    minimum,
    freeDelivery: minimum || undeliverable ? null : freeDeliveryProgress(source.subtotal, delivery, deliveryFeeDiscount),
  }
}

/**
 * The coupon to show: whether there is one comes from the cart (fresher after a write), its details from the preview
 * when it is about the same code (the preview also knows the delivery-fee discount).
 */
export function currentCoupon(
  cartCoupon: CartCoupon | null | undefined,
  previewCoupon: CartCoupon | null | undefined,
): CartCoupon | null {
  if (cartCoupon === undefined) return previewCoupon ?? null
  if (cartCoupon === null) return null
  return previewCoupon && previewCoupon.code === cartCoupon.code ? previewCoupon : cartCoupon
}
