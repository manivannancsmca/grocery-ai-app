import type { AdminDeliveryDetail, AdminDeliveryView, OnlinePartner } from '../features/admin-deliveries/api'
import type { PartnerView } from '../features/delivery/api'
import type { DeliveryView, OfferView, PartnerStatsView, PartnerStatusView } from '../features/deliveries/api'
import type { TrackingView } from '../features/order-tracking/api'
import type { NotificationView } from '../shared/realtime/messages'
import { notificationView } from './notificationFixtures'

const iso = (offsetMs: number) => new Date(Date.now() + offsetMs).toISOString()

/** Green Basket in Bengaluru (pickup) and the customer's home in Indiranagar (drop). */
export const STORE_POINT = { latitude: 12.9716, longitude: 77.5946 }
export const HOME_POINT = { latitude: 12.9784, longitude: 77.6408 }

/** An APPROVED partner: Ravi on a scooter (the partner id is the user id). */
export function approvedPartner(overrides: Partial<PartnerView> = {}): PartnerView {
  return {
    id: 'user-1',
    email: 'ravi@example.com',
    status: 'APPROVED',
    statusReason: null,
    submittedAt: '2026-11-10T10:00:00Z',
    approvedAt: '2026-11-10T12:00:00Z',
    createdAt: '2026-11-10T09:00:00Z',
    updatedAt: '2026-11-10T12:00:00Z',
    fullName: 'Ravi Kumar',
    phone: '+919812345678',
    city: 'Bengaluru',
    vehicleType: 'SCOOTER',
    vehicleNumber: 'KA01AB1234',
    drivingLicenseNumber: 'KA0120201234567',
    ...overrides,
  }
}

export function partnerStatus(overrides: Partial<PartnerStatusView> = {}): PartnerStatusView {
  return { partnerId: 'user-1', online: false, location: null, activeDeliveryId: null, pendingOffers: 0, ...overrides }
}

/** The contract's example offer: Green Basket, 1.2 km away, 3.4 km trip, 25 s left to answer. */
export function offerView(overrides: Partial<OfferView> = {}): OfferView {
  return {
    offerId: 'of1',
    deliveryId: 'd1',
    orderNumber: 'GA-7K3F9Q2M',
    orderType: 'GROCERY',
    store: { name: 'Green Basket', city: 'Bengaluru', ...STORE_POINT },
    dropArea: 'Indiranagar, Bengaluru',
    distanceKm: 1.2,
    tripKm: 3.4,
    itemCount: 3,
    expiresAt: iso(25_000),
    ...overrides,
  }
}

/** The delivery after accepting: ASSIGNED, the store is still preparing it. */
export function deliveryView(overrides: Partial<DeliveryView> = {}): DeliveryView {
  return {
    deliveryId: 'd1',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    orderType: 'GROCERY',
    status: 'ASSIGNED',
    readyForPickup: false,
    readyBy: iso(10 * 60_000),
    pickup: { name: 'Green Basket', city: 'Bengaluru', ...STORE_POINT },
    drop: {
      recipientName: 'Asha Rao',
      phone: '+919876543210',
      line1: '12 MG Road',
      line2: null,
      landmark: 'Opposite the park',
      city: 'Bengaluru',
      postalCode: '560001',
      ...HOME_POINT,
    },
    note: 'Ring twice',
    itemCount: 3,
    total: 245,
    currency: 'INR',
    assignedAt: iso(-60_000),
    pickedUpAt: null,
    deliveredAt: null,
    failedAt: null,
    failure: null,
    timeline: [
      { status: 'PENDING', at: iso(-10 * 60_000) },
      { status: 'SEARCHING', at: iso(-5 * 60_000) },
      { status: 'ASSIGNED', at: iso(-60_000) },
    ],
    ...overrides,
  }
}

export function pickedUpDelivery(overrides: Partial<DeliveryView> = {}): DeliveryView {
  const base = deliveryView({ readyForPickup: true })
  return deliveryView({
    ...base,
    status: 'PICKED_UP',
    pickedUpAt: iso(-30_000),
    timeline: [...base.timeline, { status: 'PICKED_UP', at: iso(-30_000) }],
    ...overrides,
  })
}

/** The customer's view while Ravi rides to the store. */
export function trackingView(overrides: Partial<TrackingView> = {}): TrackingView {
  return {
    orderId: 'o1',
    status: 'ASSIGNED',
    // The phone and number plate come only while the partner holds the delivery (ASSIGNED / PICKED_UP).
    partner: { name: 'Ravi', vehicleType: 'SCOOTER', phone: '+919840066004', vehicleNumber: 'TN09AB4521' },
    pickup: { name: 'Green Basket', ...STORE_POINT },
    drop: HOME_POINT,
    partnerLocation: { latitude: 12.965, longitude: 77.59, heading: 45, at: iso(-10_000) },
    etaMinutes: 12,
    timeline: [
      { status: 'SEARCHING', at: iso(-5 * 60_000) },
      { status: 'ASSIGNED', at: iso(-60_000) },
    ],
    ...overrides,
  }
}

export function adminDeliveryView(overrides: Partial<AdminDeliveryView> = {}): AdminDeliveryView {
  return { ...deliveryView(), partnerId: 'user-1', partnerName: 'Ravi Kumar', assignmentFailed: false, offersSent: 2, ...overrides }
}

/** SEARCHING for a while: nobody accepted within 10 km. */
export function unassignedDelivery(overrides: Partial<AdminDeliveryView> = {}): AdminDeliveryView {
  return adminDeliveryView({
    deliveryId: 'd2',
    orderId: 'o2',
    orderNumber: 'GA-NOPART02',
    status: 'SEARCHING',
    partnerId: null,
    partnerName: null,
    assignedAt: null,
    assignmentFailed: true,
    offersSent: 4,
    timeline: [
      { status: 'PENDING', at: iso(-10 * 60_000) },
      { status: 'SEARCHING', at: iso(-5 * 60_000) },
    ],
    ...overrides,
  })
}

export function adminDeliveryDetail(view: AdminDeliveryView, overrides: Partial<AdminDeliveryDetail> = {}): AdminDeliveryDetail {
  return {
    ...view,
    offers: [
      { offerId: 'of1', partnerId: 'p-anil', partnerName: 'Anil', distanceKm: 1.1, status: 'EXPIRED', sentAt: iso(-4 * 60_000), closedAt: iso(-210_000) },
      { offerId: 'of2', partnerId: 'p-meena', partnerName: 'Meena', distanceKm: 2.3, status: 'REJECTED', sentAt: iso(-4 * 60_000), closedAt: iso(-220_000) },
    ],
    ...overrides,
  }
}

export function onlinePartner(overrides: Partial<OnlinePartner> = {}): OnlinePartner {
  return {
    partnerId: 'p-sunil',
    name: 'Sunil',
    vehicleType: 'MOTORCYCLE',
    latitude: 12.975,
    longitude: 77.6,
    locationAt: iso(-20_000),
    busy: false,
    activeDeliveryId: null,
    ...overrides,
  }
}

/** DELIVERY_OFFER as notification-service sends it to the partner. */
export function deliveryOfferNotification(overrides: Partial<NotificationView> = {}): NotificationView {
  return notificationView({
    id: '0193a1b2-0000-7000-8000-0000000000d1',
    type: 'DELIVERY_OFFER',
    category: 'DELIVERY',
    severity: 'INFO',
    title: 'New delivery from Green Basket',
    body: '1.2 km away · respond within 30 s',
    link: '/delivery',
    data: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M', deliveryId: 'd1', offerId: 'of1' },
    ...overrides,
  })
}

/** `GET /delivery/stats` for one partner: by default an empty week (Nov 9–15). */
export function partnerStats(overrides: Partial<PartnerStatsView> = {}): PartnerStatsView {
  const days = ['2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13', '2026-11-14', '2026-11-15'].map((date) => ({
    date,
    delivered: 0,
    failed: 0,
    cancelled: 0,
    earnings: 0,
    distanceKm: 0,
    onlineMinutes: 0,
  }))
  return {
    from: '2026-11-09',
    to: '2026-11-15',
    timezone: 'Asia/Kolkata',
    totals: {
      assigned: 0,
      delivered: 0,
      failed: 0,
      cancelled: 0,
      inProgress: 0,
      offersPending: 0,
      offersReceived: 0,
      offersAccepted: 0,
      offersRejected: 0,
      offersExpired: 0,
      successRate: null,
      acceptanceRate: null,
      distanceKm: 0,
      earnings: 0,
      avgPickupMinutes: null,
      avgRideMinutes: null,
      avgTotalMinutes: null,
      onlineMinutes: 0,
    },
    days,
    payRate: { basePerDelivery: 25, perKm: 6, currency: 'INR' },
    ...overrides,
  }
}
