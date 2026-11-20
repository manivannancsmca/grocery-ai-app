import { notificationView } from './notificationFixtures'

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString()

/** The contract's example store order: NEW, 2 × Ghee Roast Dosa (₹240 + delivery = ₹270), 4 minutes left to accept. */
export function storeOrder(overrides = {}) {
  return {
    orderId: 'so1',
    orderNumber: 'GA-7K3F9Q2M',
    storeId: 'r1',
    status: 'NEW',
    items: [{ itemId: 'm1', name: 'Ghee Roast Dosa', quantity: 2, unitPrice: 120, lineTotal: 240 }],
    itemCount: 2,
    subtotal: 240,
    total: 270,
    currency: 'INR',
    customer: { name: 'Ravi Kumar', area: 'Indiranagar, Bengaluru' },
    note: 'No onions',
    receivedAt: iso(-60_000),
    acceptBy: iso(4 * 60_000),
    acceptedAt: null,
    readyInMinutes: null,
    readyBy: null,
    preparingAt: null,
    readyAt: null,
    rejection: null,
    cancellation: null,
    timeline: [{ status: 'NEW', at: iso(-60_000) }],
    ...overrides,
  }
}

/** Accepted a minute ago, ready in 20 minutes. */
export function acceptedStoreOrder(overrides = {}) {
  return storeOrder({
    orderId: 'so2',
    orderNumber: 'GA-ACC00002',
    status: 'ACCEPTED',
    items: [{ itemId: 'm2', name: 'Masala Dosa', quantity: 1, unitPrice: 90, lineTotal: 90 }],
    itemCount: 1,
    subtotal: 90,
    total: 115,
    note: null,
    acceptedAt: iso(-60_000),
    readyInMinutes: 20,
    readyBy: iso(19 * 60_000),
    ...overrides,
  })
}

/** Order lines "Item 1" … "Item n" (one each), for long orders. */
export function storeOrderItems(count) {
  return Array.from({ length: count }, (_, index) => ({
    itemId: `i${index + 1}`,
    name: `Item ${index + 1}`,
    quantity: 1,
    unitPrice: 10,
    lineTotal: 10,
  }))
}

const APPROVED = {
  status: 'APPROVED',
  statusReason: null,
  submittedAt: '2026-11-01T09:00:00Z',
  approvedAt: '2026-11-02T09:00:00Z',
  createdAt: '2026-11-01T08:00:00Z',
  updatedAt: '2026-11-02T09:00:00Z',
}

/** `GET /api/vendors/me`: an approved grocery store (the board shows its business name). */
export function vendorProfile(overrides = {}) {
  return {
    ...APPROVED,
    id: 'v1',
    ownerUserId: 'u-vendor',
    businessName: 'Vijaya Nagar Fresh Mart',
    legalName: null,
    gstin: null,
    fssaiLicense: null,
    contactEmail: null,
    contactPhone: null,
    address: null,
    ...overrides,
  }
}

/** `GET /api/restaurants/me`: an approved restaurant taking orders. */
export function restaurantProfile(overrides = {}) {
  return {
    ...APPROVED,
    id: 'r1',
    ownerUserId: 'u-restaurant',
    name: 'Kaveri Meals & Tiffin',
    description: null,
    cuisines: ['SOUTH_INDIAN'],
    fssaiLicense: null,
    gstin: null,
    contactEmail: null,
    contactPhone: null,
    address: null,
    openingHours: null,
    acceptingOrders: true,
    ...overrides,
  }
}

/** `GET /api/delivery/store/deliveries` entry: Suresh is assigned to order so2, 0.4 km from the store. */
export function storeDelivery(overrides = {}) {
  return {
    deliveryId: 'd2',
    orderId: 'so2',
    orderNumber: 'GA-ACC00002',
    status: 'ASSIGNED',
    assignmentFailed: false,
    assignedAt: iso(-30_000),
    partner: { name: 'Suresh Kannan', phone: '+919840055004', vehicleType: 'MOTORCYCLE', vehicleNumber: 'TN22EK4410' },
    partnerLocation: { latitude: 12.9766, longitude: 80.2212, heading: null, at: iso(-10_000) },
    distanceToStoreKm: 0.4,
    etaToStoreMinutes: 2,
    ...overrides,
  }
}

/** NEW_ORDER as notification-service sends it to the store owner (Phase 8 contract §6). */
export function newOrderNotification(overrides = {}) {
  return notificationView({
    id: '0193a1b2-0000-7000-8000-0000000000a1',
    type: 'NEW_ORDER',
    category: 'ORDER',
    severity: 'INFO',
    title: 'New order GA-NEW00009',
    body: '2 items · ₹270.00 · accept by 15:35',
    link: '/restaurant/orders',
    data: { orderId: 'so9', orderNumber: 'GA-NEW00009', acceptBy: iso(5 * 60_000) },
    ...overrides,
  })
}

/** The contract's example report: 7 days, 12 orders received. */
export function salesReport(from, to, overrides = {}) {
  const days = []
  for (
    let day = new Date(`${from}T00:00:00Z`);
    day <= new Date(`${to}T00:00:00Z`);
    day = new Date(day.getTime() + 86_400_000)
  ) {
    days.push(day.toISOString().slice(0, 10))
  }
  return {
    from,
    to,
    timezone: 'Asia/Kolkata',
    currency: 'INR',
    ordersReceived: 12,
    ordersAccepted: 9,
    ordersRejected: 2,
    autoRejected: 1,
    ordersCancelled: 1,
    acceptanceRate: 0.75,
    grossSales: 2340,
    itemsSold: 31,
    averageOrderValue: 292.5,
    daily: days.map((date, index) => ({
      date,
      ordersReceived: index === 0 ? 2 : 1,
      ordersAccepted: index === 0 ? 2 : 1,
      grossSales: index === 0 ? 480 : 310,
    })),
    topItems: [
      { itemId: 'm1', name: 'Ghee Roast Dosa', quantity: 8, sales: 960 },
      { itemId: 'm3', name: 'Family Thali Pack', quantity: 5, sales: 125000 },
    ],
    ...overrides,
  }
}
