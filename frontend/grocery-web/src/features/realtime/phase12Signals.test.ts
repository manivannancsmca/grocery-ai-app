import { describe, expect, it } from 'vitest'
import { parseSignal, SIGNAL_TYPES } from '../../shared/realtime/messages'
import { adminOrderKeys } from '../admin-orders/api'
import { returnKeys } from '../returns/api'
import { reviewKeys } from '../reviews/api'
import { returnKeys as storeReturnKeys } from '../store-returns/api'
import { storeReviewKeys } from '../store-reviews/api'
import { invalidationForSignal } from './invalidation'

const AT = '2026-11-13T10:00:00Z'

function signal(type: string, data: Record<string, unknown>) {
  const parsed = parseSignal(JSON.stringify({ type, data, at: AT }))
  expect(parsed, type).not.toBeNull()
  return parsed!
}

describe('Phase 12 signals (reviews and returns)', () => {
  it('knows the five new signal types', () => {
    expect(SIGNAL_TYPES).toEqual(
      expect.arrayContaining(['REVIEW_UPDATED', 'ADMIN_REVIEWS_CHANGED', 'STORE_REVIEWS_CHANGED', 'STORE_RETURNS_CHANGED', 'ADMIN_RETURNS_CHANGED']),
    )
  })

  it("a customer's review change refreshes their reviews, what they can review and public lists", () => {
    const plan = invalidationForSignal(signal('REVIEW_UPDATED', { reviewId: 'r1', status: 'PUBLISHED', event: 'ReviewModerated' }))
    expect(plan.keys).toEqual([reviewKeys.mine(), reviewKeys.eligibleAll(), reviewKeys.publicAll()])
  })

  it('store and admin review lists refresh on their own signals', () => {
    expect(invalidationForSignal(signal('STORE_REVIEWS_CHANGED', { reviewId: 'r1', targetType: 'PRODUCT', targetId: 'p1' })).keys).toEqual([
      storeReviewKeys.store(),
    ])
    expect(invalidationForSignal(signal('ADMIN_REVIEWS_CHANGED', { reviewId: 'r1', status: 'FLAGGED' })).keys).toEqual([storeReviewKeys.admin()])
  })

  it('store and admin return lists refresh on their own signals', () => {
    expect(invalidationForSignal(signal('STORE_RETURNS_CHANGED', { returnId: 'x1', orderId: 'o1', status: 'REQUESTED' })).keys).toEqual([
      storeReturnKeys.store(),
    ])
    expect(invalidationForSignal(signal('ADMIN_RETURNS_CHANGED', { returnId: 'x1', status: 'ESCALATED' })).keys).toEqual([
      storeReturnKeys.admin(),
      adminOrderKeys.all,
    ])
  })

  it("a return event on the customer's order also refreshes their returns", () => {
    const parsed = signal('ORDER_UPDATED', { orderId: 'o1', status: 'DELIVERED', event: 'ReturnApproved', returnId: 'x1' })
    expect(parsed.data).toEqual({ orderId: 'o1', status: 'DELIVERED', event: 'ReturnApproved', returnId: 'x1' })
    expect(invalidationForSignal(parsed).keys).toEqual(expect.arrayContaining([returnKeys.forOrder('o1'), returnKeys.mine()]))
  })
})
