import { describe, expect, it } from 'vitest'
import { cartLine, GREEN_BASKET, issue } from '../../test/cartFixtures'
import { ISSUE_CODES } from './api'
import { freeDeliveryShortfall, isBlockingCode, issueText, lineIssueLabel } from './issues'

const line = cartLine({ itemId: 'p1', name: 'Tomato', quantity: 5, unitPrice: 50, priceWhenAdded: 90, availableQuantity: 2 })
const context = {
  lines: [line],
  store: GREEN_BASKET,
  subtotal: 60,
  delivery: { distanceKm: 18.2, fee: 81, freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 },
}

describe('cart issues', () => {
  it('has its own clear sentence for every issue code of the contract', () => {
    for (const code of ISSUE_CODES) {
      const text = issueText(issue(code, code === 'PRICE_CHANGED' || code.includes('STOCK') || code === 'ITEM_UNAVAILABLE' ? 'p1' : null), context)
      expect(text, code).not.toContain('server message')
      expect(text.length, code).toBeGreaterThan(10)
    }
    expect(new Set(ISSUE_CODES.map((code) => issueText(issue(code), context))).size).toBe(ISSUE_CODES.length)
  })

  it('only lets a price change through', () => {
    expect(ISSUE_CODES.filter((code) => !isBlockingCode(code))).toEqual(['PRICE_CHANGED'])
  })

  it('words line issues with the live numbers', () => {
    expect(lineIssueLabel(issue('PRICE_CHANGED', 'p1'), line)).toBe('Price changed: ₹90 → ₹50')
    expect(lineIssueLabel(issue('INSUFFICIENT_STOCK', 'p1'), line)).toBe('Only 2 left')
    expect(issueText(issue('INSUFFICIENT_STOCK', 'p1'), context)).toBe(
      'Only 2 of Tomato left. Reduce the quantity to 2 or less to continue.',
    )
    expect(issueText(issue('BELOW_MINIMUM_ORDER'), context)).toBe('The minimum order is ₹99. Add ₹39 more to place this order.')
    expect(issueText(issue('UNKNOWN_CODE' as never), context)).toBe('server message for UNKNOWN_CODE')
  })

  it('computes the free-delivery shortfall in paise', () => {
    const delivery = { distanceKm: 4.6, fee: 37, freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }
    expect(freeDeliveryShortfall(248.1, delivery)).toBe(250.9)
    expect(freeDeliveryShortfall(499, delivery)).toBeNull()
    expect(freeDeliveryShortfall(100, { ...delivery, fee: 0 })).toBeNull()
    expect(freeDeliveryShortfall(100, { ...delivery, freeAbove: null })).toBeNull()
    expect(freeDeliveryShortfall(100, null)).toBeNull()
  })
})
