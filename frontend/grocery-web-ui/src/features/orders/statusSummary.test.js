import { describe, expect, it } from 'vitest'
import { confirmedOrder, orderView } from '../../test/orderFixtures'
import { ORDER_STATUSES } from './api'
import { deliveredAtOf, orderStatusLook, updateNeedsDate } from './statusSummary'

describe('orderStatusLook', () => {
  it('has one title, icon and tone for every status', () => {
    for (const status of ORDER_STATUSES) {
      const look = orderStatusLook(orderView({ status }))
      expect(look.title, status).not.toBe('')
      expect(look.icon, status).toBeTruthy()
    }
  })

  it('says it in the customer’s words, by store type', () => {
    expect(orderStatusLook(orderView({ status: 'CREATED' }))).toEqual({
      tone: 'info',
      icon: 'receipt',
      title: 'Checking stock…',
      busy: true,
    })
    expect(orderStatusLook(orderView({ status: 'PREPARING', type: 'GROCERY' })).title).toBe('Packing your order')
    expect(orderStatusLook(orderView({ status: 'PREPARING', type: 'RESTAURANT' })).title).toBe('Preparing your food')
    expect(orderStatusLook(orderView({ status: 'ACCEPTED', readyInMinutes: 25 })).title).toBe(
      'Accepted — ready in about 25 min',
    )
    expect(orderStatusLook(orderView({ status: 'ACCEPTED', readyInMinutes: null })).title).toBe('Accepted')
    expect(orderStatusLook(orderView({ status: 'DELIVERED' }))).toMatchObject({ tone: 'success', title: 'Delivered' })
    expect(orderStatusLook(orderView({ status: 'CANCELLED' }))).toMatchObject({
      tone: 'neutral',
      title: 'Order cancelled',
    })
  })

  it('tells an out-of-stock rejection from any other', () => {
    const rejected = orderView({ status: 'REJECTED', rejection: { reason: 'OUT_OF_STOCK', unavailableItems: [] } })
    expect(orderStatusLook(rejected)).toMatchObject({ tone: 'danger', title: 'Some items are out of stock' })
    expect(orderStatusLook({ ...rejected, rejection: { reason: 'OTHER', unavailableItems: [] } }).title).toBe(
      'This order could not be accepted',
    )
    expect(orderStatusLook({ ...rejected, rejection: null }).title).toBe('This order could not be accepted')
  })
})

describe('deliveredAtOf', () => {
  it('takes the delivery’s time, else the DELIVERED step of the timeline', () => {
    const timeline = [...confirmedOrder().timeline, { status: 'DELIVERED', at: '2026-11-11T10:40:00Z', reason: null }]
    expect(deliveredAtOf({ delivery: null, timeline })).toBe('2026-11-11T10:40:00Z')
    expect(
      deliveredAtOf({
        delivery: { partnerName: 'Ravi', assignedAt: null, pickedUpAt: null, deliveredAt: '2026-11-11T10:39:00Z' },
        timeline,
      }),
    ).toBe('2026-11-11T10:39:00Z')
    expect(deliveredAtOf({ delivery: null, timeline: confirmedOrder().timeline })).toBeNull()
  })
})

describe('updateNeedsDate', () => {
  it('adds the date only to changes on a later day than the order', () => {
    expect(updateNeedsDate({ at: '2026-11-11T06:30:00Z' }, '2026-11-11T06:00:00Z')).toBe(false)
    expect(updateNeedsDate({ at: '2026-11-12T06:30:00Z' }, '2026-11-11T06:00:00Z')).toBe(true)
  })
})
