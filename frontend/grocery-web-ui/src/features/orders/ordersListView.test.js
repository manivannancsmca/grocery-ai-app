import { describe, expect, it } from 'vitest'
import { formatTime } from '../../shared/utils/format'
import { SPICE_ROUTE } from '../../test/cartFixtures'
import { orderSummary } from '../../test/orderFixtures'
import {
  activeOrderLine,
  exactPaymentDueAt,
  groupOrdersByDay,
  isActiveOrder,
  itemsText,
  orderDayTitle,
  orderNextAction,
  ordersText,
  paymentMinutesLeft,
  showsPaymentStatus,
  splitActiveOrders,
  summaryProgress,
} from './ordersListView'

const PLACED = '2026-11-18T09:00:00Z'
const at = (minutesAfterPlacing) => Date.parse(PLACED) + minutesAfterPlacing * 60_000
const paid = (status, overrides = {}) =>
  orderSummary({ status, payment: { status: 'PAID' }, createdAt: PLACED, ...overrides })

describe('splitActiveOrders', () => {
  it('puts the orders under way apart from the finished ones, keeping the newest-first order', () => {
    const orders = [
      paid('DELIVERED', { id: 'd' }),
      paid('OUT_FOR_DELIVERY', { id: 'w' }),
      paid('CANCELLED', { id: 'c', payment: { status: 'REFUNDED' } }),
      paid('PREPARING', { id: 'p' }),
      paid('REJECTED', { id: 'r', payment: { status: 'NOT_REQUIRED' } }),
      paid('DELIVERY_FAILED', { id: 'f' }),
    ]
    const { active, past } = splitActiveOrders(orders)
    expect(active.map((order) => order.id)).toEqual(['w', 'p'])
    expect(past.map((order) => order.id)).toEqual(['d', 'c', 'r', 'f'])
  })

  it('leads with the orders waiting for the customer to pay (their window runs out)', () => {
    const orders = [
      paid('OUT_FOR_DELIVERY', { id: 'w' }),
      paid('CONFIRMED', { id: 'c' }),
      orderSummary({ id: 'pay', status: 'AWAITING_PAYMENT', payment: { status: 'PENDING' } }),
      orderSummary({ id: 'expired', status: 'AWAITING_PAYMENT', payment: { status: 'EXPIRED' } }),
    ]
    expect(splitActiveOrders(orders).active.map((order) => order.id)).toEqual(['pay', 'w', 'c', 'expired'])
  })

  it('knows which statuses are still under way', () => {
    expect(
      [
        'CREATED',
        'AWAITING_PAYMENT',
        'CONFIRMED',
        'ACCEPTED',
        'PREPARING',
        'READY_FOR_PICKUP',
        'OUT_FOR_DELIVERY',
      ].every((status) => isActiveOrder({ status: status })),
    ).toBe(true)
    expect(
      ['DELIVERED', 'DELIVERY_FAILED', 'CANCELLED', 'REJECTED'].some((status) => isActiveOrder({ status: status })),
    ).toBe(false)
  })
})

describe('groupOrdersByDay', () => {
  it('groups by India-time day in the server order: Today, Yesterday, then the date', () => {
    const days = groupOrdersByDay(
      [
        orderSummary({ id: 'a', createdAt: '2026-11-18T09:00:00Z' }),
        // 18:45 UTC on the 17th is already the 18th in India (00:15 IST).
        orderSummary({ id: 'b', createdAt: '2026-11-17T18:45:00Z' }),
        orderSummary({ id: 'c', createdAt: '2026-11-17T10:00:00Z' }),
        orderSummary({ id: 'd', createdAt: '2026-11-15T10:00:00Z' }),
        orderSummary({ id: 'e', createdAt: '2026-11-15T04:00:00Z' }),
      ],
      '2026-11-18',
    )
    expect(days.map((day) => [day.day, day.title, day.orders.map((order) => order.id)])).toEqual([
      ['2026-11-18', 'Today', ['a', 'b']],
      ['2026-11-17', 'Yesterday', ['c']],
      ['2026-11-15', expect.stringContaining('15 Nov'), ['d', 'e']],
    ])
    expect(days[0]?.subtitle).toContain('18 Nov')
    expect(days[2]?.subtitle).toBeNull()
  })

  it('keeps an order with a broken time under "Date unknown"', () => {
    expect(groupOrdersByDay([orderSummary({ createdAt: 'not a date' })], '2026-11-18').map((day) => day.title)).toEqual(
      ['Date unknown'],
    )
    expect(orderDayTitle('2026-11-01', '2026-11-18')).toEqual({
      title: expect.stringContaining('1 Nov'),
      subtitle: null,
    })
  })
})

describe('activeOrderLine', () => {
  it('counts down to the payment due time the server sends, and says when it is up', () => {
    // A 10-minute window (the setting can differ from the usual 15).
    const dueAt = '2026-11-18T09:10:00Z'
    const order = orderSummary({ status: 'AWAITING_PAYMENT', payment: { status: 'PENDING', dueAt }, createdAt: PLACED })
    expect(exactPaymentDueAt(order)).toBe(Date.parse(dueAt))
    expect(activeOrderLine(order, at(3.4))).toMatchObject({
      title: 'Pay within 7 min',
      hint: `Pay by ${formatTime(dueAt)}, or the order is cancelled.`,
      tone: 'warning',
      icon: 'card',
    })
    expect(paymentMinutesLeft(order, at(9.5))).toBe(1)
    expect(activeOrderLine(order, at(10))).toMatchObject({
      title: 'Payment time is up',
      hint: 'This order is being cancelled.',
      tone: 'danger',
    })
    // Past the due time there is nothing left to pay; without a clock the order still counts as waiting for payment.
    expect(orderNextAction(order, at(9.9)).kind).toBe('pay')
    expect(orderNextAction(order, at(10))).toEqual({ kind: 'view', label: 'View order' })
    expect(orderNextAction(order).kind).toBe('pay')
  })

  it('falls back to the 15-minute window from when the order was placed (older servers, or a broken due time)', () => {
    const order = orderSummary({ status: 'AWAITING_PAYMENT', payment: { status: 'PENDING' }, createdAt: PLACED })
    expect(activeOrderLine(order, at(3.4))).toMatchObject({ title: 'Pay within 12 min', tone: 'warning', icon: 'card' })
    expect(activeOrderLine(order, at(14.5)).title).toBe('Pay within 1 min')
    expect(activeOrderLine(order, at(16)).title).toBe('Payment window closing')
    expect(orderNextAction(order, at(16)).kind).toBe('pay')
    expect(paymentMinutesLeft(order, at(0))).toBe(15)
    expect(paymentMinutesLeft({ ...order, createdAt: 'broken' }, at(0))).toBe(0)
    expect(activeOrderLine({ ...order, payment: { status: 'PENDING', dueAt: null } }, at(3.4)).title).toBe(
      'Pay within 12 min',
    )
    expect(activeOrderLine({ ...order, payment: { status: 'PENDING', dueAt: 'soon' } }, at(3.4)).title).toBe(
      'Pay within 12 min',
    )
    expect(exactPaymentDueAt({ payment: { status: 'PENDING', dueAt: 'soon' } })).toBeNull()
    expect(activeOrderLine({ ...order, payment: { status: 'EXPIRED' } }, at(3))).toMatchObject({
      title: 'Payment time ran out',
      tone: 'danger',
    })
  })

  it('says where the order is in the store and on the road, in grocery or restaurant words', () => {
    const now = at(10)
    expect(activeOrderLine(paid('CREATED'), now).title).toBe('Checking stock…')
    expect(activeOrderLine(paid('CONFIRMED'), now)).toMatchObject({
      title: 'Order confirmed',
      hint: 'Waiting for Green Basket to accept it.',
    })
    expect(activeOrderLine(paid('ACCEPTED'), now).hint).toBe('Green Basket will start packing soon.')
    expect(activeOrderLine(paid('PREPARING'), now)).toMatchObject({ title: 'Being packed', icon: 'box' })
    expect(activeOrderLine(paid('PREPARING', { type: 'RESTAURANT', store: SPICE_ROUTE }), now)).toMatchObject({
      title: 'Being prepared',
      icon: 'cooking',
    })
    expect(activeOrderLine(paid('READY_FOR_PICKUP'), now).title).toBe('Ready for pickup')
    expect(activeOrderLine(paid('OUT_FOR_DELIVERY'), now)).toMatchObject({ title: 'Arriving soon', icon: 'scooter' })
  })
})

describe('orderNextAction', () => {
  it('is paying while payment is open, tracking once the store has it, otherwise looking', () => {
    expect(orderNextAction(orderSummary({ status: 'AWAITING_PAYMENT', payment: { status: 'PENDING' } }))).toEqual({
      kind: 'pay',
      label: 'Pay now',
    })
    expect(orderNextAction(orderSummary({ status: 'AWAITING_PAYMENT', payment: { status: 'NOT_STARTED' } })).kind).toBe(
      'pay',
    )
    expect(orderNextAction(orderSummary({ status: 'AWAITING_PAYMENT', payment: { status: 'EXPIRED' } })).kind).toBe(
      'view',
    )
    for (const status of ['ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY']) {
      expect(orderNextAction(paid(status))).toEqual({ kind: 'track', label: 'Track order' })
    }
    expect(orderNextAction(paid('CONFIRMED'))).toEqual({ kind: 'view', label: 'View order' })
    expect(orderNextAction(paid('CREATED')).kind).toBe('view')
  })
})

describe('list details', () => {
  it('marks the step the order is on', () => {
    expect(summaryProgress(paid('PREPARING')).map((step) => `${step.label}:${step.state}`)).toEqual([
      'Placed:done',
      'Confirmed:done',
      'Packing:current',
      'On the way:upcoming',
      'Delivered:upcoming',
    ])
  })

  it('shows the payment beside a finished order unless it is the usual "delivered and paid"', () => {
    expect(showsPaymentStatus(paid('DELIVERED'))).toBe(false)
    expect(showsPaymentStatus(paid('DELIVERED', { payment: { status: 'PARTIALLY_REFUNDED' } }))).toBe(true)
    expect(showsPaymentStatus(paid('CANCELLED', { payment: { status: 'REFUNDED' } }))).toBe(true)
    expect(showsPaymentStatus(paid('CANCELLED'))).toBe(true)
    expect(showsPaymentStatus(paid('REJECTED', { payment: { status: 'NOT_REQUIRED' } }))).toBe(true)
  })

  it('counts items and orders in words', () => {
    expect([itemsText(1), itemsText(5)]).toEqual(['1 item', '5 items'])
    expect([ordersText(1), ordersText(17), ordersText(1200)]).toEqual(['1 order', '17 orders', '1,200 orders'])
  })
})
