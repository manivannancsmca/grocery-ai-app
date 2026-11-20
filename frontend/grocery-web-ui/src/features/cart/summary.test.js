import { describe, expect, it } from 'vitest'
import { cartLine, cartView, checkoutPreview, GREEN_BASKET, issue } from '../../test/cartFixtures'
import { addressIssueLabel, lineIssueHint } from './issues'
import {
  cartBill,
  cartPageIssues,
  currentCoupon,
  discountPercent,
  freeDeliveryProgress,
  hasTransientIssue,
  issuesAnnouncement,
  lineSaving,
  minimumOrderGap,
  storeStatusLabel,
} from './summary'

const DELIVERY = { distanceKm: 4.6, fee: 37, freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }

describe('cart page summary', () => {
  it('nudges towards free delivery until the threshold, then says it is reached', () => {
    expect(freeDeliveryProgress(434, DELIVERY)).toEqual({
      kind: 'short',
      shortfall: 65,
      freeAbove: 499,
      percent: (434 / 499) * 100,
    })
    expect(freeDeliveryProgress(248.1, DELIVERY)).toMatchObject({ kind: 'short', shortfall: 250.9 })
    // A tiny basket still shows a sliver of progress.
    expect(freeDeliveryProgress(5, DELIVERY)).toMatchObject({ percent: 4 })
    expect(freeDeliveryProgress(520, { ...DELIVERY, fee: 0 })).toEqual({ kind: 'reached', freeAbove: 499 })
    // Restaurants (no threshold), no quote, or a coupon already paying the fee: no nudge.
    expect(freeDeliveryProgress(100, { ...DELIVERY, freeAbove: null })).toBeNull()
    expect(freeDeliveryProgress(100, null)).toBeNull()
    expect(freeDeliveryProgress(100, DELIVERY, 37)).toBeNull()
  })

  it('says how much is missing for the minimum order', () => {
    expect(minimumOrderGap(60, DELIVERY)).toEqual({ minimum: 99, shortfall: 39, percent: (60 / 99) * 100 })
    expect(minimumOrderGap(99, DELIVERY)).toBeNull()
    expect(minimumOrderGap(10, { ...DELIVERY, minimumOrder: null })).toBeNull()
    expect(minimumOrderGap(10, undefined)).toBeNull()
  })

  it('works out what a line saves against the MRP', () => {
    const tomato = cartLine({ quantity: 2, unitPrice: 49, mrp: 60 })
    expect(lineSaving(tomato)).toBe(22)
    expect(discountPercent(tomato)).toBe(18)
    expect(lineSaving(cartLine({ unitPrice: 60, mrp: 60 }))).toBe(0)
    expect(discountPercent(cartLine({ unitPrice: null, mrp: 60 }))).toBe(0)
  })

  it('collects the issues of the cart and the preview without repeats, and ignores stale line issues of the preview', () => {
    const cart = cartView(
      [cartLine({ itemId: 'p1', name: 'Tomato', issue: 'OUT_OF_STOCK' }), cartLine({ itemId: 'p2', name: 'Onion' })],
      { issues: [issue('STORE_CLOSED')] },
    )
    const preview = checkoutPreview(cart, {
      issues: [
        issue('STORE_CLOSED'),
        issue('BELOW_MINIMUM_ORDER'),
        issue('OUT_OF_STOCK', 'p2'),
        issue('OUT_OF_STOCK', 'gone'),
      ],
    })
    expect(cartPageIssues(cart, preview).map((entry) => `${entry.code}:${entry.itemId ?? '-'}`)).toEqual([
      'OUT_OF_STOCK:p1',
      'STORE_CLOSED:-',
      'BELOW_MINIMUM_ORDER:-',
    ])
    expect(storeStatusLabel(cartPageIssues(cart, preview))).toBe('Closed now')
    expect(storeStatusLabel([issue('NOT_ACCEPTING_ORDERS')])).toBe('Paused new orders')
    expect(storeStatusLabel([issue('OUT_OF_STOCK', 'p1')])).toBeNull()
    expect(hasTransientIssue([issue('AVAILABILITY_UNKNOWN', 'p1')])).toBe(true)
    expect(hasTransientIssue([issue('OUT_OF_STOCK', 'p1')])).toBe(false)
  })

  it('announces the outcome of the price and stock check', () => {
    const line = cartLine({ itemId: 'p1', name: 'Tomato', unitPrice: 50, priceWhenAdded: 90 })
    const context = { lines: [line], store: GREEN_BASKET, subtotal: 50 }
    expect(issuesAnnouncement([], context)).toBe('Prices and stock checked: everything in your cart is available.')
    expect(issuesAnnouncement([issue('PRICE_CHANGED', 'p1'), issue('STORE_CLOSED')], context)).toBe(
      'Prices and stock checked. Tomato now costs ₹50 (was ₹90). Green Basket is closed right now. You can order when it opens again.',
    )
  })

  it('takes the coupon from the cart and its details from the preview', () => {
    const fromCart = { code: 'SAVE10', valid: true, discount: 24.8, deliveryFeeDiscount: 0 }
    const fromPreview = { ...fromCart, deliveryFeeDiscount: 37 }
    expect(currentCoupon(fromCart, fromPreview)).toBe(fromPreview)
    expect(currentCoupon(fromCart, { ...fromPreview, code: 'OLD' })).toBe(fromCart)
    expect(currentCoupon(null, fromPreview)).toBeNull()
    expect(currentCoupon(undefined, fromPreview)).toBe(fromPreview)
    expect(currentCoupon(undefined, undefined)).toBeNull()
  })

  it('bills from the preview, or from the cart until it is there', () => {
    const cart = cartView([cartLine({ quantity: 2 }), cartLine({ itemId: 'p5', unitPrice: 150, mrp: 180 })]) // ₹248, MRP ₹300
    const coupon = { code: 'SAVE10', valid: true, discount: 24.8, deliveryFeeDiscount: 0 }

    const withoutPreview = cartBill(cart, undefined, coupon)
    expect(withoutPreview).toMatchObject({
      subtotal: 248,
      mrpTotal: 300,
      delivery: null,
      discount: 24.8,
      total: 223.2,
      complete: false,
    })
    expect(withoutPreview.saved).toBe(76.8)
    expect(withoutPreview.freeDelivery).toBeNull()

    const preview = checkoutPreview(cart, {
      delivery: DELIVERY,
      discount: 24.8,
      deliveryFeeDiscount: 0,
      total: 260.2,
      coupon,
    })
    expect(cartBill(cart, preview, coupon)).toMatchObject({
      delivery: DELIVERY,
      total: 260.2,
      complete: true,
      minimum: null,
      freeDelivery: { kind: 'short', shortfall: 251 },
    })

    const small = cartView([cartLine({ unitPrice: 60, mrp: 60 })])
    expect(cartBill(small, checkoutPreview(small, { delivery: DELIVERY }), null)).toMatchObject({
      minimum: { shortfall: 39 },
      freeDelivery: null,
    })
    // No free-delivery nudge to an address the store does not deliver to.
    const far = checkoutPreview(cart, { delivery: DELIVERY, issues: [issue('OUT_OF_DELIVERY_AREA')] })
    expect(cartBill(cart, far, null).freeDelivery).toBeNull()
  })

  it('words what to do about a line or an address in a few words', () => {
    const cheaper = cartLine({ unitPrice: 50, priceWhenAdded: 90 })
    expect(lineIssueHint(issue('PRICE_CHANGED', 'p1'), cheaper)).toBe('It costs less now.')
    expect(lineIssueHint(issue('PRICE_CHANGED', 'p1'), cartLine({ unitPrice: 95, priceWhenAdded: 90 }))).toBe(
      'You’ll pay the new price.',
    )
    expect(lineIssueHint(issue('OUT_OF_STOCK', 'p1'), cheaper)).toBe('Remove it to check out.')
    expect(lineIssueHint(issue('INSUFFICIENT_STOCK', 'p1'), cartLine({ quantity: 5 }))).toBe('You have 5 in your cart.')
    expect(lineIssueHint(issue('STORE_CLOSED', 'p1'), cheaper)).toBeNull()
    expect(addressIssueLabel(issue('OUT_OF_DELIVERY_AREA'), DELIVERY)).toBe(
      'Outside the delivery area — this store delivers up to 15 km.',
    )
    expect(addressIssueLabel(issue('ADDRESS_LOCATION_MISSING'), null)).toBe(
      'No map location — edit the address to add it.',
    )
    expect(addressIssueLabel(issue('BELOW_MINIMUM_ORDER'), DELIVERY)).toBeNull()
  })
})
