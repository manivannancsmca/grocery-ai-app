import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import { notificationView } from '../../test/notificationFixtures'
import { notificationTarget } from '../notifications/useOpenNotification'
import { notificationLabel } from '../notifications/style'
import { storeRejectionDetails } from '../orders/labels'
import { reportRangeError } from '../store-reports/range'
import { acceptedStoreOrder, storeOrder, storeOrderItems } from '../../test/storeOrderFixtures'
import {
  acceptWindowLeft,
  boardStep,
  columnOrder,
  foldItems,
  lateCount,
  minutesLate,
  minutesUntil,
  nextExpiryMs,
  pickedUpToday,
} from './board'
import { conflictStatus, storeOrderActionError } from './errors'
import { closedOrderSummary } from './labels'
import { optimisticOrder } from './optimistic'

function conflict(body) {
  return new ApiError('Conflict', { kind: 'http', status: 409, problem: { status: 409, ...body }, correlationId: null })
}

describe('store order helpers', () => {
  it('reads the current status of a 409 wherever the server puts it', () => {
    // Spring writes an extension named `status` after (and over) ProblemDetail's own numeric status.
    expect(conflictStatus(conflict({ errorCode: 'STORE_ORDER_NOT_PENDING', status: 'CANCELLED' }))).toBe('CANCELLED')
    expect(conflictStatus(conflict({ errorCode: 'STORE_ORDER_NOT_PENDING', properties: { status: 'ACCEPTED' } }))).toBe(
      'ACCEPTED',
    )
    expect(
      conflictStatus(conflict({ errorCode: 'INVALID_STORE_ORDER_TRANSITION', currentStatus: 'READY_FOR_PICKUP' })),
    ).toBe('READY_FOR_PICKUP')
    expect(conflictStatus(conflict({ errorCode: 'STORE_ORDER_NOT_PENDING' }))).toBeNull()
  })

  it('names the vendor’s PREPARING status "packing" in a 409', () => {
    const failure = storeOrderActionError(
      'VENDOR',
      { orderNumber: 'GA-1' },
      conflict({ errorCode: 'INVALID_STORE_ORDER_TRANSITION', status: 'PREPARING' }),
    )
    expect(failure).toMatchObject({ tone: 'warning', message: 'Order GA-1 has already moved on', conflict: true })
    expect(failure.description).toBe('It is now packing. The board has been refreshed.')
    expect(storeOrderActionError('VENDOR', { orderNumber: 'GA-1' }, new Error('Boom'))).toMatchObject({
      tone: 'error',
      conflict: false,
    })
  })

  it('turns a STORE_REJECTED note ("<reason>: <note>") into plain words', () => {
    expect(storeRejectionDetails('TOO_BUSY: Kitchen is full')).toBe('Too busy right now: Kitchen is full')
    expect(storeRejectionDetails('OUT_OF_STOCK')).toBe('Items out of stock')
    expect(storeRejectionDetails('OTHER: Gas supply is down')).toBe('Gas supply is down')
    expect(storeRejectionDetails('Free text from an admin')).toBe('Free text from an admin')
    expect(storeRejectionDetails(null)).toBeNull()
  })

  it('validates report ranges like the server (inclusive days, at most 92)', () => {
    expect(reportRangeError('2026-11-05', '2026-11-11')).toBeNull()
    expect(reportRangeError('2026-08-12', '2026-11-11')).toBeNull()
    expect(reportRangeError('2026-08-11', '2026-11-11')).toBe('Choose at most 92 days — this range has 93.')
    expect(reportRangeError('2026-11-12', '2026-11-11')).toBe('The start date must be on or before the end date.')
    expect(reportRangeError('', '2026-11-11')).toBe('Choose a start date and an end date.')
  })
})

describe('store notifications', () => {
  it('open the order board, also for alerts stored before Phase 8 that link to the dashboard', () => {
    expect(notificationTarget(notificationView({ type: 'NEW_ORDER', link: '/vendor/orders' }))).toBe('/vendor/orders')
    expect(notificationTarget(notificationView({ type: 'NEW_ORDER', link: '/restaurant' }))).toBe('/restaurant/orders')
    expect(notificationTarget(notificationView({ type: 'ORDER_CANCELLED', link: '/vendor' }))).toBe('/vendor/orders')
    expect(notificationTarget(notificationView({ type: 'ORDER_ACCEPTED', link: '/orders/o1' }))).toBe('/orders/o1')
    expect(notificationTarget(notificationView({ type: 'ACCOUNT_APPROVED', link: '/vendor' }))).toBe('/vendor')
  })

  it('label the new order steps by type and everything else by category', () => {
    expect(notificationLabel({ type: 'NEW_ORDER', category: 'ORDER' })).toBe('New order')
    expect(notificationLabel({ type: 'ORDER_ACCEPTED', category: 'ORDER' })).toBe('Order accepted')
    expect(notificationLabel({ type: 'ORDER_READY', category: 'ORDER' })).toBe('Ready for pickup')
    expect(notificationLabel({ type: 'ORDER_AUTO_REJECTED', category: 'ORDER' })).toBe('Auto-rejected order')
    expect(notificationLabel({ type: 'ORDER_CONFIRMED', category: 'ORDER' })).toBe('Order')
  })
})

describe('optimisticOrder', () => {
  const now = Date.parse('2026-11-17T10:00:00Z')

  it('moves a card to its next status at once, the way the server will answer', () => {
    expect(optimisticOrder(storeOrder(), { type: 'accept', readyInMinutes: 20 }, now)).toMatchObject({
      status: 'ACCEPTED',
      acceptedAt: '2026-11-17T10:00:00.000Z',
      readyInMinutes: 20,
      readyBy: '2026-11-17T10:20:00.000Z',
    })
    expect(optimisticOrder(acceptedStoreOrder(), { type: 'preparing' }, now)).toMatchObject({
      status: 'PREPARING',
      preparingAt: '2026-11-17T10:00:00.000Z',
    })
    expect(optimisticOrder(acceptedStoreOrder(), { type: 'ready' }, now)).toMatchObject({
      status: 'READY_FOR_PICKUP',
      readyAt: '2026-11-17T10:00:00.000Z',
    })
  })

  it('leaves a rejection to its dialog', () => {
    expect(optimisticOrder(storeOrder(), { type: 'reject' }, now)).toBeNull()
  })
})

describe('board helpers', () => {
  const now = Date.parse('2026-11-17T10:00:00Z')
  const at = (offsetMs) => new Date(now + offsetMs).toISOString()

  it('counts minutes late from the promised time, and minutes left to the nearest minute', () => {
    expect(minutesLate({ readyBy: at(-3 * 60_000 - 1) }, now)).toBe(3)
    expect(minutesLate({ readyBy: at(-30_000) }, now)).toBe(0)
    expect(minutesLate({ readyBy: at(60_000) }, now)).toBe(0)
    expect(minutesLate({ readyBy: null }, now)).toBe(0)
    // Accepted "ready in 15" a moment after the board's clock ticked: still "in 15 min", not 16.
    expect(minutesUntil(at(15 * 60_000 + 400), now)).toBe(15)
    expect(minutesUntil(at(-60_000), now)).toBe(0)
  })

  it('finds late orders and the next acceptance deadline of a column', () => {
    const orders = [
      storeOrder({ acceptBy: at(90_000) }),
      storeOrder({ orderId: 'n2', acceptBy: at(40_000) }),
      acceptedStoreOrder({ readyBy: at(-2 * 60_000) }),
      acceptedStoreOrder({ orderId: 'p1', status: 'PREPARING', readyBy: at(5 * 60_000) }),
      acceptedStoreOrder({ orderId: 'r1', status: 'READY_FOR_PICKUP', readyBy: at(-20 * 60_000) }),
    ]
    expect(nextExpiryMs(orders, now)).toBe(40_000)
    expect(nextExpiryMs([storeOrder({ acceptBy: at(-5_000) })], now)).toBe(0)
    expect(nextExpiryMs(orders.slice(2), now)).toBeNull()
    // A ready order is no longer late: it waits for the partner.
    expect(lateCount(orders, now)).toBe(1)
  })

  it('drains the acceptance bar from full to empty over the window', () => {
    const order = { receivedAt: at(-60_000), acceptBy: at(180_000) }
    expect(acceptWindowLeft(order, now)).toBeCloseTo(0.75)
    expect(acceptWindowLeft(order, now + 10 * 60_000)).toBe(0)
    expect(acceptWindowLeft({ receivedAt: 'nonsense', acceptBy: at(60_000) }, now)).toBe(1)
  })

  it('folds the items of long orders only', () => {
    expect(foldItems(storeOrderItems(6), false)).toMatchObject({ hidden: 3 })
    expect(foldItems(storeOrderItems(6), false).shown).toHaveLength(3)
    expect(foldItems(storeOrderItems(6), true)).toMatchObject({ hidden: 0 })
    expect(foldItems(storeOrderItems(4), false)).toMatchObject({ hidden: 0 })
  })

  it('orders columns by urgency and numbers the steps', () => {
    const late = acceptedStoreOrder({ orderId: 'a', readyBy: at(60_000) })
    const later = acceptedStoreOrder({ orderId: 'b', readyBy: at(9 * 60_000) })
    expect([later, late].sort(columnOrder('ACCEPTED')).map((order) => order.orderId)).toEqual(['a', 'b'])
    expect(
      ['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'PICKED_UP'].map((status) => boardStep(status)),
    ).toEqual([1, 2, 3, 4, 0])
  })

  it('sums today’s pickups (item totals only)', () => {
    const done = (subtotal) => acceptedStoreOrder({ status: 'PICKED_UP', subtotal })
    expect(pickedUpToday([done(90), done(240), storeOrder({ status: 'REJECTED' })])).toEqual({
      count: 2,
      sales: 330,
      currency: 'INR',
    })
    expect(pickedUpToday([])).toEqual({ count: 0, sales: 0, currency: 'INR' })
  })

  it('sums up a closed order as a status chip and a reason that reads on from it', () => {
    const rejected = (reason, note = null, auto = false) =>
      closedOrderSummary('VENDOR', storeOrder({ status: 'REJECTED', rejection: { reason, note, auto, at: at(0) } }))
    expect(closedOrderSummary('VENDOR', acceptedStoreOrder({ status: 'PICKED_UP' }))).toEqual({
      tone: 'done',
      chip: 'Picked up',
      reason: 'by the delivery partner',
      note: null,
    })
    expect(rejected('NO_RESPONSE', null, true)).toMatchObject({
      tone: 'rejected',
      chip: 'Auto-rejected',
      reason: 'not accepted in time',
    })
    expect(rejected('OUT_OF_STOCK', ' Paneer is finished ')).toEqual({
      tone: 'rejected',
      chip: 'Rejected',
      reason: 'items out of stock',
      note: 'Paneer is finished',
    })
    expect(rejected('OTHER', 'Gas supply is down')).toMatchObject({
      chip: 'Rejected',
      reason: null,
      note: 'Gas supply is down',
    })
    expect(rejected('KITCHEN_FLOODED')).toMatchObject({ reason: 'kitchen flooded' })
    const cancelled = (by) =>
      closedOrderSummary(
        'RESTAURANT',
        storeOrder({ status: 'CANCELLED', cancellation: { reason: 'CUSTOMER_CANCELLED', by, at: at(0) } }),
      )
    expect(cancelled('CUSTOMER')).toMatchObject({ tone: 'cancelled', chip: 'Cancelled', reason: 'by the customer' })
    expect(cancelled('ADMIN')).toMatchObject({ reason: 'by support' })
  })
})
