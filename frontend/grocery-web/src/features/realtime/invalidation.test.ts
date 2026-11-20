import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { distanceKm } from '../../shared/components/map/types'
import { parseSignal, type Signal } from '../../shared/realtime/messages'
import { HOME_POINT, STORE_POINT, trackingView } from '../../test/deliveryFixtures'
import { notificationView } from '../../test/notificationFixtures'
import { adminDeliveryKeys } from '../admin-deliveries/api'
import { adminOrderKeys } from '../admin-orders/api'
import { adminPaymentKeys } from '../admin-payments/api'
import { adminPriceKeys } from '../admin-pricing/api'
import { cartKeys } from '../cart/api'
import { catalogKeys } from '../catalog/api'
import { customerKeys } from '../customer/api'
import { partnerKeys } from '../delivery/api'
import { deliveryKeys } from '../deliveries/api'
import { menuKeys } from '../menu/api'
import { type TrackingView, trackingKeys } from '../order-tracking/api'
import { applyDeliveryLocation, estimateEta, etaMinutesFor } from '../order-tracking/cache'
import { orderKeys } from '../orders/api'
import { paymentKeys } from '../payments/api'
import { restaurantKeys } from '../restaurant/api'
import { discoveryKeys } from '../restaurant-discovery/api'
import { storeOrderKeys } from '../store-orders/api'
import { storeReportKeys } from '../store-reports/api'
import { vendorKeys } from '../vendor/api'
import { inventoryKeys } from '../vendor-inventory/api'
import { vendorPriceKeys } from '../vendor-pricing/api'
import { invalidationForNotification, invalidationForSignal } from './invalidation'

const at = '2026-11-11T10:00:00.123Z'

/** True when invalidating `prefix` reaches `key` (TanStack's partial matching). */
function covers(prefix: readonly unknown[], key: readonly unknown[]): boolean {
  return prefix.every((part, index) => JSON.stringify(part) === JSON.stringify(key[index]))
}

describe('invalidation map — signals', () => {
  it('ORDER_UPDATED → that order (and its payment) + the order lists', () => {
    const plan = invalidationForSignal({ type: 'ORDER_UPDATED', data: { orderId: 'o1', status: 'CONFIRMED' }, at })
    expect(plan.keys).toEqual([orderKeys.detail('o1'), orderKeys.lists(), paymentKeys.forOrder('o1')])
    expect(plan.keys.some((key) => covers(key, orderKeys.list({ status: 'OPEN', page: 0 })))).toBe(true)
    expect(plan.keys.some((key) => covers(key, orderKeys.detail('o2')))).toBe(false)
  })

  it('ADMIN_ORDERS_CHANGED → admin order and payment monitors (lists and drawers) and every day of the stats', () => {
    const plan = invalidationForSignal({ type: 'ADMIN_ORDERS_CHANGED', data: { orderId: 'o1', event: 'OrderConfirmed' }, at })
    expect(plan.keys).toEqual([adminOrderKeys.all, adminOrderKeys.allStats(), adminPaymentKeys.all, paymentKeys.forOrder('o1')])
    for (const key of [adminOrderKeys.list({ page: 0 }), adminOrderKeys.detail('o1'), adminOrderKeys.stats(null), adminPaymentKeys.list({})]) {
      expect(plan.keys.some((prefix) => covers(prefix, key))).toBe(true)
    }
  })

  it.each(['STOCK_CHANGED', 'PRICE_CHANGED'] as const)('%s → the product page, every product search and the cart', (type) => {
    const signal = (type === 'STOCK_CHANGED'
      ? { type, data: { productId: 'p1', vendorId: 'v1', inStock: false }, at }
      : { type, data: { productId: 'p1', vendorId: 'v1' }, at }) as Signal
    const plan = invalidationForSignal(signal)
    expect(plan.keys).toEqual([catalogKeys.product('p1'), catalogKeys.searches(), cartKeys.all])
    expect(plan.keys.some((key) => covers(key, catalogKeys.search({ q: 'tomato', page: 0 })))).toBe(true)
    expect(plan.keys.some((key) => covers(key, cartKeys.preview(null)))).toBe(true)
    expect(plan.keys.some((key) => covers(key, catalogKeys.categories()))).toBe(false)
  })

  it('RESTAURANT_UPDATED → that restaurant page, restaurant search and the cart', () => {
    const plan = invalidationForSignal({ type: 'RESTAURANT_UPDATED', data: { restaurantId: 'r1', acceptingOrders: false, status: 'APPROVED' }, at })
    expect(plan.keys).toEqual([discoveryKeys.detail('r1'), discoveryKeys.searches(), cartKeys.all])
    expect(plan.keys.some((key) => covers(key, discoveryKeys.search({ q: 'biryani' })))).toBe(true)
  })

  it('INVENTORY_UPDATED → vendor inventory; PRICING_UPDATED → vendor pricing', () => {
    expect(invalidationForSignal({ type: 'INVENTORY_UPDATED', data: { productId: 'p1' }, at }).keys).toEqual([inventoryKeys.all])
    expect(invalidationForSignal({ type: 'PRICING_UPDATED', data: { productId: 'p1', requestId: 'pr1' }, at }).keys).toEqual([vendorPriceKeys.all])
  })

  it('APPROVALS_CHANGED → the admin queue of that kind (all queues when the kind is unknown)', () => {
    expect(invalidationForSignal({ type: 'APPROVALS_CHANGED', data: { kind: 'VENDOR' }, at }).keys).toEqual([vendorKeys.admin])
    expect(invalidationForSignal({ type: 'APPROVALS_CHANGED', data: { kind: 'RESTAURANT' }, at }).keys).toEqual([restaurantKeys.admin])
    expect(invalidationForSignal({ type: 'APPROVALS_CHANGED', data: { kind: 'DELIVERY_PARTNER' }, at }).keys).toEqual([partnerKeys.admin])
    expect(invalidationForSignal({ type: 'APPROVALS_CHANGED', data: { kind: 'PRICE_CHANGE' }, at }).keys).toEqual([adminPriceKeys.all])
    expect(invalidationForSignal({ type: 'APPROVALS_CHANGED', data: {}, at }).keys).toHaveLength(4)
  })

  it('ACCOUNT_UPDATED → own profile and a silent token refresh', () => {
    expect(invalidationForSignal({ type: 'ACCOUNT_UPDATED', data: { kind: 'VENDOR', status: 'APPROVED' }, at })).toEqual({
      keys: [vendorKeys.mine],
      refreshToken: true,
    })
    expect(invalidationForSignal({ type: 'ACCOUNT_UPDATED', data: { kind: 'RESTAURANT', status: 'APPROVED' }, at }).keys).toEqual([
      restaurantKeys.mine,
      menuKeys.mine,
    ])
    expect(invalidationForSignal({ type: 'ACCOUNT_UPDATED', data: { kind: 'DELIVERY_PARTNER' }, at }).keys).toEqual([partnerKeys.mine])
    expect(invalidationForSignal({ type: 'ACCOUNT_UPDATED', data: { kind: 'USER' }, at }).keys).toEqual([customerKeys.profile()])
  })

  it('STORE_ORDERS_CHANGED → the store order board (both kinds, every list) and the sales reports', () => {
    const plan = invalidationForSignal({ type: 'STORE_ORDERS_CHANGED', data: { orderId: 'so1', status: 'ACCEPTED' }, at })
    expect(plan.keys).toEqual([storeOrderKeys.all, storeReportKeys.all])
    for (const key of [
      storeOrderKeys.list('VENDOR', { status: 'ACTIVE', page: 0, size: 100 }),
      storeOrderKeys.closedToday('RESTAURANT', '2026-11-11'),
      storeReportKeys.sales('RESTAURANT', { from: '2026-11-05', to: '2026-11-11' }),
    ]) {
      expect(plan.keys.some((prefix) => covers(prefix, key))).toBe(true)
    }
    expect(plan.keys.some((prefix) => covers(prefix, orderKeys.detail('so1')))).toBe(false)
  })

  it('parses STORE_ORDERS_CHANGED from the stream', () => {
    expect(parseSignal(JSON.stringify({ type: 'STORE_ORDERS_CHANGED', data: { orderId: 'so1', status: 'NEW', extra: 1 }, at }))).toEqual({
      type: 'STORE_ORDERS_CHANGED',
      data: { orderId: 'so1', status: 'NEW' },
      at,
    })
  })

  it('RESYNC → everything', () => {
    expect(invalidationForSignal({ type: 'RESYNC', data: {}, at })).toEqual({ keys: [], everything: true })
  })
})

describe('invalidation map — notifications', () => {
  it('order and payment notifications refresh their order', () => {
    for (const type of ['ORDER_CONFIRMED', 'ORDER_ACCEPTED', 'ORDER_READY', 'ORDER_REJECTED', 'ORDER_CANCELLED', 'PAYMENT_FAILED', 'REFUND_COMPLETED']) {
      expect(invalidationForNotification(notificationView({ type })).keys).toEqual([orderKeys.detail('o1'), orderKeys.lists(), paymentKeys.forOrder('o1')])
    }
  })

  it('maps admin, vendor and account notifications to their screens', () => {
    expect(invalidationForNotification(notificationView({ type: 'REFUND_FAILED', category: 'PAYMENT' })).keys).toContainEqual(adminPaymentKeys.all)
    expect(invalidationForNotification(notificationView({ type: 'LOW_STOCK', category: 'STOCK', data: {} })).keys).toEqual([inventoryKeys.all])
    expect(invalidationForNotification(notificationView({ type: 'PRICE_APPROVAL_REQUESTED', data: {} })).keys).toEqual([adminPriceKeys.all])
    expect(invalidationForNotification(notificationView({ type: 'PRICE_CHANGE_REJECTED', data: {} })).keys).toEqual([vendorPriceKeys.all])
    expect(invalidationForNotification(notificationView({ type: 'APPROVAL_REQUESTED', data: {} })).keys).toEqual([
      vendorKeys.admin,
      restaurantKeys.admin,
      partnerKeys.admin,
    ])
    expect(invalidationForNotification(notificationView({ type: 'ACCOUNT_APPROVED', data: { kind: 'VENDOR' } }))).toEqual({
      keys: [vendorKeys.mine],
      refreshToken: true,
    })
  })

  it('NEW_ORDER and ORDER_AUTO_REJECTED (store owner) → the store order board and the sales reports', () => {
    for (const type of ['NEW_ORDER', 'ORDER_AUTO_REJECTED']) {
      const plan = invalidationForNotification(notificationView({ type, link: '/restaurant/orders', data: { orderId: 'so1', orderNumber: 'GA-1' } }))
      expect(plan.keys).toEqual([storeOrderKeys.all, storeReportKeys.all])
      for (const key of [
        storeOrderKeys.list('RESTAURANT', { status: 'ACTIVE', page: 0, size: 100 }),
        storeOrderKeys.closedToday('VENDOR', '2026-11-11'),
        storeReportKeys.sales('VENDOR', { from: '2026-11-05', to: '2026-11-11' }),
      ]) {
        expect(plan.keys.some((prefix) => covers(prefix, key))).toBe(true)
      }
    }
  })

  it('ORDER_CANCELLED for the store (a store link) also refreshes its board and reports', () => {
    const plan = invalidationForNotification(notificationView({ type: 'ORDER_CANCELLED', link: '/vendor' }))
    expect(plan.keys).toEqual([orderKeys.detail('o1'), orderKeys.lists(), paymentKeys.forOrder('o1'), storeOrderKeys.all, storeReportKeys.all])
  })

  it('falls back on the category for a notification type it does not know', () => {
    expect(invalidationForNotification(notificationView({ type: 'ORDER_OUT_FOR_DELIVERY', category: 'ORDER' })).keys).toContainEqual(orderKeys.detail('o1'))
    expect(invalidationForNotification(notificationView({ type: 'SOMETHING_NEW', category: 'ACCOUNT', data: {} })).keys).toEqual([])
  })
})

describe('invalidation map — Phase 9 delivery', () => {
  const PARTNER_DELIVERY = [deliveryKeys.active(), deliveryKeys.status(), deliveryKeys.offers(), deliveryKeys.history()]

  it('DELIVERY_OFFERS_CHANGED and DELIVERY_OFFER → the partner’s offers and status', () => {
    const fromSignal = invalidationForSignal({ type: 'DELIVERY_OFFERS_CHANGED', data: { offerId: 'of1', deliveryId: 'd1' }, at })
    expect(fromSignal.keys).toEqual([deliveryKeys.offers(), deliveryKeys.status()])
    expect(invalidationForNotification(notificationView({ type: 'DELIVERY_OFFER', category: 'DELIVERY', link: '/delivery' })).keys).toEqual(fromSignal.keys)
  })

  it('DELIVERY_UPDATED, DELIVERY_CANCELLED and DELIVERY_REASSIGNED → the active delivery, status, offers and history', () => {
    expect(invalidationForSignal({ type: 'DELIVERY_UPDATED', data: { deliveryId: 'd1', orderId: 'o1' }, at }).keys).toEqual(PARTNER_DELIVERY)
    for (const type of ['DELIVERY_CANCELLED', 'DELIVERY_REASSIGNED']) {
      expect(invalidationForNotification(notificationView({ type, category: 'DELIVERY', link: '/delivery' })).keys).toEqual(PARTNER_DELIVERY)
    }
    expect(PARTNER_DELIVERY.some((prefix) => covers(prefix, deliveryKeys.historyList({ page: 2, size: 20 })))).toBe(true)
    expect(PARTNER_DELIVERY.some((prefix) => covers(prefix, deliveryKeys.historySummary()))).toBe(true)
  })

  it('PICKUP_READY → the active delivery ("Order ready")', () => {
    expect(invalidationForNotification(notificationView({ type: 'PICKUP_READY', category: 'DELIVERY' })).keys).toEqual([deliveryKeys.active()])
  })

  it('customer delivery notifications → the order (as any order event) and its live tracking', () => {
    for (const type of ['PARTNER_ASSIGNED', 'OUT_FOR_DELIVERY', 'ORDER_DELIVERED', 'DELIVERY_FAILED']) {
      expect(invalidationForNotification(notificationView({ type })).keys).toEqual([
        orderKeys.detail('o1'),
        orderKeys.lists(),
        paymentKeys.forOrder('o1'),
        trackingKeys.detail('o1'),
      ])
    }
    expect(invalidationForNotification(notificationView({ type: 'PARTNER_ASSIGNED', data: {} })).keys).toContainEqual(trackingKeys.all)
  })

  it('ADMIN_DELIVERIES_CHANGED → the delivery monitor and the order’s admin drawer; NO_DELIVERY_PARTNER → the monitor', () => {
    const plan = invalidationForSignal({ type: 'ADMIN_DELIVERIES_CHANGED', data: { deliveryId: 'd1', orderId: 'o1' }, at })
    expect(plan.keys).toEqual([adminDeliveryKeys.all, adminOrderKeys.detail('o1')])
    for (const key of [adminDeliveryKeys.list({ page: 0 }), adminDeliveryKeys.detail('d1'), adminDeliveryKeys.onlinePartners()]) {
      expect(plan.keys.some((prefix) => covers(prefix, key))).toBe(true)
    }
    expect(invalidationForSignal({ type: 'ADMIN_DELIVERIES_CHANGED', data: {}, at }).keys).toEqual([adminDeliveryKeys.all])
    expect(invalidationForNotification(notificationView({ type: 'NO_DELIVERY_PARTNER', category: 'DELIVERY', link: '/admin/deliveries' })).keys).toEqual([
      adminDeliveryKeys.all,
    ])
  })

  it('DELIVERY_FAILED_ADMIN → the admin order and payment monitors (to refund) and the delivery monitor', () => {
    const plan = invalidationForNotification(notificationView({ type: 'DELIVERY_FAILED_ADMIN', severity: 'ERROR', link: '/admin/orders' }))
    expect(plan.keys).toEqual([adminOrderKeys.all, adminOrderKeys.allStats(), adminPaymentKeys.all, paymentKeys.forOrder('o1'), adminDeliveryKeys.all])
  })

  it('DELIVERY_LOCATION invalidates nothing: the dispatcher patches the tracking cache instead', () => {
    expect(invalidationForSignal({ type: 'DELIVERY_LOCATION', data: { orderId: 'o1', latitude: 12.97, longitude: 77.59 }, at })).toEqual({ keys: [] })
  })

  it('parses the Phase 9 signals from the stream', () => {
    expect(
      parseSignal(JSON.stringify({ type: 'DELIVERY_LOCATION', data: { orderId: 'o1', latitude: 12.97, longitude: 77.59, heading: null, at: '2026-11-11T10:00:05Z' }, at })),
    ).toEqual({ type: 'DELIVERY_LOCATION', data: { orderId: 'o1', latitude: 12.97, longitude: 77.59, heading: null, at: '2026-11-11T10:00:05Z' }, at })
    // A bad coordinate is dropped (the patch then ignores the signal); the position's time defaults to the signal's.
    expect(parseSignal(JSON.stringify({ type: 'DELIVERY_LOCATION', data: { orderId: 'o1', latitude: 'x', longitude: 77.59 }, at }))).toEqual({
      type: 'DELIVERY_LOCATION',
      data: { orderId: 'o1', longitude: 77.59, at },
      at,
    })
    expect(parseSignal(JSON.stringify({ type: 'ADMIN_DELIVERIES_CHANGED', data: { deliveryId: 'd1', orderId: 'o1' }, at }))?.data).toEqual({
      deliveryId: 'd1',
      orderId: 'o1',
    })
    expect(parseSignal(JSON.stringify({ type: 'DELIVERY_OFFERS_CHANGED', data: {}, at }))?.type).toBe('DELIVERY_OFFERS_CHANGED')
    expect(parseSignal(JSON.stringify({ type: 'DELIVERY_UPDATED', data: { deliveryId: 'd1' }, at }))?.data).toEqual({ deliveryId: 'd1' })
  })
})

describe('DELIVERY_LOCATION cache patch', () => {
  function cachedTracking() {
    const queryClient = new QueryClient()
    queryClient.setQueryData(trackingKeys.detail('o1'), trackingView({ status: 'PICKED_UP' }))
    return queryClient
  }

  it('moves the partner and recomputes the ETA (partner → customer after pickup)', () => {
    const queryClient = cachedTracking()
    const now = new Date().toISOString()
    expect(applyDeliveryLocation(queryClient, { orderId: 'o1', ...STORE_POINT, heading: 90, at: now })).toBe(true)
    const tracking = queryClient.getQueryData<TrackingView>(trackingKeys.detail('o1'))
    expect(tracking?.partnerLocation).toEqual({ ...STORE_POINT, heading: 90, at: now })
    expect(tracking?.etaMinutes).toBe(etaMinutesFor(distanceKm(STORE_POINT, HOME_POINT)))
    // 5.07 km × 1.3 at 20 km/h → 20 min.
    expect(tracking?.etaMinutes).toBe(20)
  })

  it('ignores older positions, other orders, missing coordinates and finished deliveries', () => {
    const queryClient = cachedTracking()
    const before = queryClient.getQueryData<TrackingView>(trackingKeys.detail('o1'))
    expect(applyDeliveryLocation(queryClient, { orderId: 'o1', latitude: 13, longitude: 77, at: '2020-01-01T00:00:00Z' })).toBe(false)
    expect(applyDeliveryLocation(queryClient, { orderId: 'o2', latitude: 13, longitude: 77 })).toBe(false)
    expect(applyDeliveryLocation(queryClient, { orderId: 'o1', longitude: 77 })).toBe(false)
    expect(queryClient.getQueryData(trackingKeys.detail('o1'))).toBe(before)

    queryClient.setQueryData(trackingKeys.detail('o1'), trackingView({ status: 'DELIVERED', partnerLocation: null }))
    expect(applyDeliveryLocation(queryClient, { orderId: 'o1', latitude: 13, longitude: 77 })).toBe(false)
  })

  it('counts the way to the store before pickup', () => {
    const tracking = trackingView({ status: 'ASSIGNED' })
    expect(estimateEta(tracking, HOME_POINT)).toBe(etaMinutesFor(2 * distanceKm(HOME_POINT, STORE_POINT)))
    expect(estimateEta(trackingView({ drop: null, etaMinutes: 9 }), STORE_POINT)).toBe(9)
  })
})
