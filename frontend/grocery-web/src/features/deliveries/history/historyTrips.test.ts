import { describe, expect, it } from 'vitest'
import type { DeliverySummary } from '../api'
import { dayTitle, groupTripsByDay, lastSevenDays, rideMinutes, successTone, tripOutcome, tripsText } from './historyTrips'

function trip(overrides: Partial<DeliverySummary>): DeliverySummary {
  return {
    deliveryId: 'd1',
    orderNumber: 'GA-1',
    status: 'DELIVERED',
    store: 'Green Basket',
    dropArea: 'Adyar, Chennai',
    tripKm: 2,
    pickedUpAt: '2026-11-17T04:00:00Z',
    finishedAt: '2026-11-17T04:20:00Z',
    ...overrides,
  }
}

describe('groupTripsByDay', () => {
  it('groups a page of trips by India-time day, newest first, with the distance per day', () => {
    const days = groupTripsByDay(
      [
        trip({ deliveryId: 'a', finishedAt: '2026-11-17T05:00:00Z', tripKm: 1.5 }),
        // 20:00 UTC on the 16th is already the 17th in India (01:30 IST).
        trip({ deliveryId: 'b', finishedAt: '2026-11-16T20:00:00Z', tripKm: 2 }),
        trip({ deliveryId: 'c', finishedAt: '2026-11-16T10:00:00Z', tripKm: null }),
        trip({ deliveryId: 'd', finishedAt: '2026-11-12T10:00:00Z' }),
      ],
      '2026-11-17',
    )

    expect(days.map((day) => [day.day, day.title, day.trips.map((t) => t.deliveryId)])).toEqual([
      ['2026-11-17', 'Today', ['a', 'b']],
      ['2026-11-16', 'Yesterday', ['c']],
      ['2026-11-12', expect.stringContaining('12 Nov'), ['d']],
    ])
    expect(days[0]?.km).toBe(3.5)
    expect(days[1]?.km).toBe(0)
  })

  it('falls back to the pickup time, and to "Date unknown" without any time', () => {
    const days = groupTripsByDay([trip({ finishedAt: null, pickedUpAt: '2026-11-17T04:00:00Z' }), trip({ deliveryId: 'x', finishedAt: null, pickedUpAt: null })], '2026-11-17')
    expect(days.map((day) => day.title)).toEqual(['Today', 'Date unknown'])
  })
})

describe('dayTitle', () => {
  it('names today and yesterday and shows the date under them', () => {
    expect(dayTitle('2026-11-17', '2026-11-17')).toEqual({ title: 'Today', subtitle: expect.stringContaining('17 Nov') })
    expect(dayTitle('2026-11-16', '2026-11-17').title).toBe('Yesterday')
    expect(dayTitle('2026-11-01', '2026-11-17')).toEqual({ title: expect.stringContaining('1 Nov'), subtitle: null })
  })
})

describe('rideMinutes', () => {
  it('is the time from pickup to the end of the trip', () => {
    expect(rideMinutes(trip({}))).toBe(20)
    expect(rideMinutes(trip({ pickedUpAt: null }))).toBeNull()
    expect(rideMinutes(trip({ finishedAt: '2026-11-17T03:00:00Z' }))).toBeNull()
  })
})

describe('tripOutcome', () => {
  it('says delivered / not delivered in the partner’s words', () => {
    expect(tripOutcome('DELIVERED')).toMatchObject({ label: 'Delivered', tone: 'green', delivered: true })
    expect(tripOutcome('FAILED')).toMatchObject({ label: 'Not delivered', tone: 'red', icon: 'homeCross', delivered: false })
    expect(tripOutcome('CANCELLED')).toMatchObject({ label: 'Cancelled', tone: 'gray' })
  })
})

describe('small helpers', () => {
  it('counts trips, picks the ring colour and the 7 days of the summary', () => {
    expect(tripsText(1)).toBe('1 trip')
    expect(tripsText(3)).toBe('3 trips')
    expect([null, 0.95, 0.8, 0.5].map(successTone)).toEqual(['none', 'good', 'warning', 'critical'])
    expect(lastSevenDays('2026-11-17')).toEqual({ from: '2026-11-11', to: '2026-11-17' })
  })
})
