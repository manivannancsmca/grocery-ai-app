import { render } from '@testing-library/react'
import { createElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import { NOTIFICATION_TYPES } from '../../shared/realtime/messages'
import { NotificationIcon } from '../notifications/NotificationIcon'
import { notificationLabel } from '../notifications/style'
import { notificationTarget } from '../notifications/useOpenNotification'
import { currentStatus, deliveryActionError, offerError } from './errors'
import { dropAddressLines, offerEarnings, telHref } from './labels'
import { createLocationReporter, LOCATION_KEEPALIVE_MS, LOCATION_REPORT_INTERVAL_MS } from './locationReporter'
import { DRIVE_STEPS, driveStep, simulatorEnabled } from './simulator'

function conflict(status, body) {
  return new ApiError('Conflict', { kind: 'http', status, problem: { status, ...body }, correlationId: null })
}

const at = (latitude) => ({ latitude, longitude: 77.59 })

describe('location reporter', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('sends the first position at once, then the newest at most every 5 s, and repeats it after 25 s of silence', async () => {
    const sent = []
    const reporter = createLocationReporter({ send: async (report) => void sent.push(report) })

    reporter.push(at(12.1))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(sent).toEqual([]) // not started: positions are only remembered

    reporter.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(sent).toEqual([at(12.1)])

    reporter.push(at(12.2))
    reporter.push(at(12.3))
    await vi.advanceTimersByTimeAsync(LOCATION_REPORT_INTERVAL_MS - 1)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    // Trailing send: the newest position wins.
    expect(sent).toEqual([at(12.1), at(12.3)])

    await vi.advanceTimersByTimeAsync(LOCATION_KEEPALIVE_MS - 1)
    expect(sent).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(sent).toEqual([at(12.1), at(12.3), at(12.3)])
  })

  it('stops itself on 409 PARTNER_OFFLINE until started again', async () => {
    const send = vi.fn().mockRejectedValue(conflict(409, { errorCode: 'PARTNER_OFFLINE' }))
    const onStopped = vi.fn()
    const reporter = createLocationReporter({ send, onStopped })

    reporter.start()
    reporter.push(at(12.1))
    await vi.advanceTimersByTimeAsync(0)
    expect(onStopped).toHaveBeenCalledWith('offline')
    expect(reporter.running).toBe(false)

    reporter.push(at(12.2))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(send).toHaveBeenCalledTimes(1)

    // Going online again sends the latest position at once.
    send.mockResolvedValue(undefined)
    reporter.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(send).toHaveBeenLastCalledWith(at(12.2))
  })

  it('drops a scheduled send on stop and retries other failures after the interval', async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error('Network down')).mockResolvedValue(undefined)
    const onError = vi.fn()
    const reporter = createLocationReporter({ send, onError })

    reporter.start()
    reporter.push(at(12.1))
    await vi.advanceTimersByTimeAsync(0)
    expect(onError).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(LOCATION_REPORT_INTERVAL_MS)
    expect(send).toHaveBeenCalledTimes(2)

    reporter.push(at(12.2))
    reporter.stop()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(send).toHaveBeenCalledTimes(2)
  })
})

describe('location simulator', () => {
  const store = { latitude: 12.9716, longitude: 77.5946 }
  const home = { latitude: 12.9784, longitude: 77.6408 }

  it('drives along a straight line in equal steps and lands exactly on the target', () => {
    expect(driveStep(store, home, 0)).toEqual(store)
    expect(driveStep(store, home, DRIVE_STEPS / 2)).toEqual({ latitude: 12.975, longitude: 77.6177 })
    expect(driveStep(store, home, DRIVE_STEPS)).toEqual(home)
    expect(driveStep(store, home, DRIVE_STEPS + 3)).toEqual(home)
  })

  it('exists in development and in test builds only — never in a production build, whatever the URL says', () => {
    expect(simulatorEnabled(true, undefined)).toBe(true)
    expect(simulatorEnabled(false, 'true')).toBe(true)
    expect(simulatorEnabled(false, undefined)).toBe(false)
    expect(simulatorEnabled(false, 'false')).toBe(false)
  })
})

describe('Phase 9 notifications', () => {
  const PHASE_9_TYPES = [
    'DELIVERY_OFFER',
    'PICKUP_READY',
    'DELIVERY_REASSIGNED',
    'DELIVERY_CANCELLED',
    'PARTNER_ASSIGNED',
    'OUT_FOR_DELIVERY',
    'ORDER_DELIVERED',
    'DELIVERY_FAILED',
    'DELIVERY_FAILED_ADMIN',
    'NO_DELIVERY_PARTNER',
  ]

  it('are known types with their own label and icon, opened at their link', () => {
    for (const type of PHASE_9_TYPES) {
      expect(NOTIFICATION_TYPES).toContain(type)
      expect(notificationLabel({ type, category: 'DELIVERY' })).not.toBe('Delivery')
      const { container } = render(createElement(NotificationIcon, { type, category: 'DELIVERY', severity: 'INFO' }))
      const fallback = render(createElement(NotificationIcon, { category: 'DELIVERY', severity: 'INFO' })).container
      if (!['DELIVERY_OFFER', 'PARTNER_ASSIGNED', 'OUT_FOR_DELIVERY'].includes(type)) {
        // The scooter is the category's own picture; every other step draws something more specific.
        expect(container.innerHTML).not.toBe(fallback.innerHTML)
      }
    }
    expect(notificationLabel({ type: 'DELIVERY_OFFER', category: 'DELIVERY' })).toBe('Delivery offer')
    expect(notificationLabel({ type: 'NO_DELIVERY_PARTNER', category: 'DELIVERY' })).toBe('No delivery partner')
    expect(notificationLabel({ type: 'SOMETHING_ELSE', category: 'DELIVERY' })).toBe('Delivery')
    expect(notificationTarget({ type: 'DELIVERY_OFFER', link: '/delivery' })).toBe('/delivery')
    expect(notificationTarget({ type: 'NO_DELIVERY_PARTNER', link: '/admin/deliveries' })).toBe('/admin/deliveries')
  })
})

describe('delivery messages', () => {
  it('reads currentStatus from a 409 and explains each partner conflict', () => {
    expect(currentStatus(conflict(409, { errorCode: 'OFFER_NOT_AVAILABLE', currentStatus: 'EXPIRED' }))).toBe('EXPIRED')
    expect(
      offerError('Green Basket', conflict(409, { errorCode: 'OFFER_NOT_AVAILABLE', currentStatus: 'EXPIRED' })).message,
    ).toBe('This offer has expired')
    expect(
      offerError('Green Basket', conflict(409, { errorCode: 'OFFER_NOT_AVAILABLE', currentStatus: 'WITHDRAWN' }))
        .message,
    ).toBe('Another partner took it')
    expect(
      deliveryActionError(
        'GA-1',
        conflict(409, { errorCode: 'INVALID_DELIVERY_TRANSITION', currentStatus: 'DELIVERED' }),
      ).description,
    ).toBe('It is now delivered. Your delivery has been refreshed.')
    expect(deliveryActionError('GA-1', new Error('Boom'))).toMatchObject({
      tone: 'error',
      message: 'Could not update order GA-1',
    })
  })

  it('formats the call link and the drop address', () => {
    expect(telHref('+91 98765-43210')).toBe('tel:+919876543210')
    expect(
      dropAddressLines({
        recipientName: 'Asha',
        phone: null,
        line1: '12 MG Road',
        line2: 'Flat 4B',
        landmark: null,
        city: 'Bengaluru',
        postalCode: '560001',
        latitude: null,
        longitude: null,
      }),
    ).toEqual(['12 MG Road, Flat 4B', 'Bengaluru 560001'])
  })
})

describe('offer earnings', () => {
  const payRate = { basePerDelivery: 25, perKm: 6 }

  it('estimates an offer like the performance figures: the base plus the per-km rate times the trip', () => {
    expect(offerEarnings(3.4, payRate)).toBe(45.4)
    expect(offerEarnings(0, payRate)).toBe(25)
    expect(offerEarnings(1.234, payRate)).toBe(32.4)
  })

  it('has no estimate without a trip distance or a pay rate', () => {
    expect(offerEarnings(null, payRate)).toBeNull()
    expect(offerEarnings(Number.NaN, payRate)).toBeNull()
    expect(offerEarnings(3.4, undefined)).toBeNull()
  })
})
