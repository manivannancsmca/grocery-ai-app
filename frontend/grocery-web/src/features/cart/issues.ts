import { formatDistanceKm, formatPrice } from '../../shared/utils/format'
import { couponReasonText } from '../cart-coupons/labels'
import type { CartCoupon, CartIssue, CartLine, CartStore, CartView, DeliveryQuote, IssueCode } from './api'

/** Only a price change lets the customer continue; every other issue blocks checkout (contract §2). */
export function isBlockingCode(code: IssueCode): boolean {
  return code !== 'PRICE_CHANGED'
}

/** Money arithmetic in paise, so `499 − 248.1` is `250.9`, not `250.89999…`. */
export function roundMoney(value: number): number {
  return Math.round(value * 100) / 100
}

/** The issue of a line: its entry in `issues` when present, otherwise one derived from `line.issue`. */
export function lineIssue(cart: Pick<CartView, 'issues'>, line: CartLine): CartIssue | null {
  const listed = cart.issues.find((issue) => issue.itemId === line.itemId && (line.issue === null || issue.code === line.issue))
  if (listed) return listed
  if (!line.issue) return null
  return { code: line.issue, itemId: line.itemId, blocking: isBlockingCode(line.issue), message: '' }
}

/** Issues that are not about one line of the cart (store closed, address, minimum order, …). */
export function cartLevelIssues(cart: Pick<CartView, 'issues' | 'lines'>): CartIssue[] {
  return cart.issues.filter((issue) => !issue.itemId || !cart.lines.some((line) => line.itemId === issue.itemId))
}

/** Short label shown on a cart line ("Sold out", "Only 2 left", "Price changed: ₹90 → ₹50"). */
export function lineIssueLabel(issue: CartIssue, line: CartLine): string {
  switch (issue.code) {
    case 'PRICE_CHANGED':
      return line.unitPrice !== null
        ? `Price changed: ${formatPrice(line.priceWhenAdded)} → ${formatPrice(line.unitPrice)}`
        : 'Price changed'
    case 'OUT_OF_STOCK':
      return 'Sold out'
    case 'INSUFFICIENT_STOCK':
      return line.availableQuantity !== null ? `Only ${line.availableQuantity} left` : 'Not enough stock'
    case 'ITEM_UNAVAILABLE':
      return 'No longer available'
    case 'PRICING_UNAVAILABLE':
      return 'Price could not be checked'
    case 'AVAILABILITY_UNKNOWN':
      return 'Stock could not be checked'
    case 'STORE_UNAVAILABLE':
      return 'Store unavailable'
    case 'STORE_CLOSED':
      return 'Closed right now'
    case 'NOT_ACCEPTING_ORDERS':
      return 'Not accepting orders'
    default:
      return issue.message || 'Needs attention'
  }
}

/** What to do about a line's issue, in a few words next to its label (null when the label says it all). */
export function lineIssueHint(issue: CartIssue, line: CartLine): string | null {
  switch (issue.code) {
    case 'PRICE_CHANGED':
      if (line.unitPrice === null) return null
      return line.unitPrice > line.priceWhenAdded ? 'You’ll pay the new price.' : 'It costs less now.'
    case 'OUT_OF_STOCK':
    case 'ITEM_UNAVAILABLE':
      return 'Remove it to check out.'
    case 'INSUFFICIENT_STOCK':
      return `You have ${line.quantity} in your cart.`
    case 'PRICING_UNAVAILABLE':
    case 'AVAILABILITY_UNKNOWN':
      return 'Try again in a moment.'
    default:
      return null
  }
}

/** A problem with the chosen delivery address, in a few words under it (null for other issues). */
export function addressIssueLabel(issue: CartIssue, delivery: DeliveryQuote | null | undefined): string | null {
  switch (issue.code) {
    case 'OUT_OF_DELIVERY_AREA':
      return delivery
        ? `Outside the delivery area — this store delivers up to ${delivery.maxDistanceKm} km.`
        : 'Outside the delivery area — choose another address.'
    case 'ADDRESS_LOCATION_MISSING':
      return 'No map location — edit the address to add it.'
    default:
      return null
  }
}

export interface IssueContext {
  lines: readonly CartLine[]
  store: CartStore | null
  subtotal: number
  /** Checkout preview only. */
  delivery?: DeliveryQuote | null
  /** Phase 12: the applied coupon (checkout preview). */
  coupon?: CartCoupon | null
}

function storeName(store: CartStore | null): string {
  if (store) return store.name
  return 'The store'
}

/** One clear sentence for any issue code of the contract (falls back to the server message). */
export function issueText(issue: CartIssue, context: IssueContext): string {
  const line = issue.itemId ? context.lines.find((candidate) => candidate.itemId === issue.itemId) : undefined
  const item = line?.name ?? 'An item'
  const store = storeName(context.store)
  const delivery = context.delivery ?? null

  switch (issue.code) {
    case 'PRICE_CHANGED':
      return line && line.unitPrice !== null
        ? `${item} now costs ${formatPrice(line.unitPrice)} (was ${formatPrice(line.priceWhenAdded)}).`
        : issue.message || 'A price has changed since you added the item.'
    case 'ITEM_UNAVAILABLE':
      return `${item} is no longer available. Remove it to continue.`
    case 'OUT_OF_STOCK':
      return `${item} is sold out. Remove it to continue.`
    case 'INSUFFICIENT_STOCK':
      return line && line.availableQuantity !== null
        ? `Only ${line.availableQuantity} of ${item} left. Reduce the quantity to ${line.availableQuantity} or less to continue.`
        : `There is not enough stock of ${item}. Reduce the quantity to continue.`
    case 'STORE_UNAVAILABLE':
      return `${store} is not available on FreshCart right now, so this cart cannot be ordered.`
    case 'STORE_CLOSED':
      return `${store} is closed right now. You can order when it opens again.`
    case 'NOT_ACCEPTING_ORDERS':
      return `${store} has paused new orders for a while. Please try again later.`
    case 'PRICING_UNAVAILABLE':
      return line
        ? `We could not check the current price of ${item}. Please try again in a moment.`
        : 'We could not check the current prices. Please try again in a moment.'
    case 'AVAILABILITY_UNKNOWN':
      return line
        ? `We could not check whether ${item} is in stock. Please try again in a moment.`
        : 'We could not check stock right now. Please try again in a moment.'
    case 'ADDRESS_REQUIRED':
      return 'Add a delivery address to see the delivery fee and place your order.'
    case 'ADDRESS_LOCATION_MISSING':
      return 'This address has no map location, so the delivery distance cannot be worked out. Edit the address and add its location.'
    case 'OUT_OF_DELIVERY_AREA': {
      const distance = formatDistanceKm(delivery?.distanceKm)
      if (delivery && distance) {
        return `This address is outside the delivery area: it is ${distance} away and ${store} delivers up to ${delivery.maxDistanceKm} km.`
      }
      return `This address is outside the delivery area of ${store}. Choose another address.`
    }
    case 'BELOW_MINIMUM_ORDER': {
      const minimum = delivery?.minimumOrder
      if (minimum !== null && minimum !== undefined) {
        const shortBy = roundMoney(minimum - context.subtotal)
        return shortBy > 0
          ? `The minimum order is ${formatPrice(minimum)}. Add ${formatPrice(shortBy)} more to place this order.`
          : `The minimum order is ${formatPrice(minimum)}.`
      }
      return issue.message || 'Your order is below the minimum order value.'
    }
    case 'EMPTY_CART':
      return 'Your cart is empty.'
    case 'COUPON_NOT_APPLICABLE': {
      const coupon = context.coupon ?? null
      const reason = couponReasonText(coupon?.reason, {
        code: coupon?.code,
        minOrderSubtotal: coupon?.minOrderSubtotal,
        subtotal: context.subtotal,
        storeName: context.store?.name,
      })
      const name = coupon ? `Coupon ${coupon.code}` : 'The coupon on your cart'
      return `${name} can’t be used any more.${reason ? ` ${reason}` : ''} Remove it or change your cart to continue.`
    }
    default:
      return issue.message || 'Something in your cart needs attention.'
  }
}

/** Rupees still missing for free delivery, or null when the hint does not apply. */
export function freeDeliveryShortfall(subtotal: number, delivery: DeliveryQuote | null): number | null {
  if (!delivery || delivery.freeAbove === null || delivery.fee <= 0) return null
  const missing = roundMoney(delivery.freeAbove - subtotal)
  return missing > 0 ? missing : null
}
