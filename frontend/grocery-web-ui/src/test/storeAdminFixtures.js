import { placeOrderApproval } from './aiFixtures'

/** Phase 12 fixtures for the vendor, restaurant, delivery and admin screens. */

export const VENDOR_AGENT = {
  id: 'vendor',
  name: 'Vendor assistant',
  description: 'Stock, prices, sales, suppliers and purchase orders for your store.',
  examplePrompts: ['low stock', 'sales', 'set stock tomato to 40'],
}

export const RESTAURANT_AGENT = {
  id: 'restaurant',
  name: 'Restaurant assistant',
  description: 'Your order queue, menu and sales.',
  examplePrompts: ['orders', 'mark paneer tikka sold out'],
}

export const DELIVERY_AGENT = {
  id: 'delivery',
  name: 'Delivery assistant',
  description: 'Active delivery, offers and reassignments.',
  examplePrompts: ['active delivery', 'deliveries'],
}

/** The vendor's own ADJUST_STOCK request: decided by the vendor (user-1), who asked for it. */
export function adjustStockApproval(overrides = {}) {
  return placeOrderApproval({
    id: 's1',
    action: 'ADJUST_STOCK',
    agent: 'vendor',
    onBehalfOf: null,
    approverUserId: 'user-1',
    summary: {
      title: 'Set stock of Tomato 500 g to 40',
      lines: [
        { label: 'Now on hand', value: '12' },
        { label: 'Reason', value: 'Stock count' },
      ],
      amount: null,
      currency: null,
    },
    rationale: 'You asked me to set the stock of tomatoes to 40.',
    ...overrides,
  })
}

export function moderatedReview(overrides = {}) {
  return {
    id: 'rv1',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    targetType: 'PRODUCT',
    targetId: 'p1',
    targetName: 'Tomato 500 g',
    rating: 4,
    title: 'Fresh and firm',
    comment: 'Good tomatoes, well packed.',
    status: 'PUBLISHED',
    moderationReason: null,
    reply: null,
    authorName: 'Asha S.',
    edited: false,
    createdAt: '2026-11-12T08:30:00Z',
    updatedAt: null,
    flagReason: null,
    storeId: 'v1',
    storeName: 'Fresh Mart',
    ...overrides,
  }
}

export function storeReturn(overrides = {}) {
  return {
    id: 'ret1',
    returnNumber: 'RT-7K3M9QX2',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    orderType: 'GROCERY',
    storeId: 'v1',
    storeName: 'Fresh Mart',
    status: 'REQUESTED',
    reason: 'DAMAGED',
    note: 'Two were squashed.',
    items: [{ itemId: 'p1', name: 'Tomato 500 g', quantity: 2, refundAmount: 95 }],
    refundAmount: 95,
    restock: null,
    decideBy: new Date(Date.now() + 20 * 3_600_000).toISOString(),
    requestedAt: '2026-11-13T06:00:00Z',
    decidedAt: null,
    decidedByRole: null,
    comment: null,
    refundedAt: null,
    ...overrides,
  }
}

export function coupon(overrides = {}) {
  return {
    id: 'c1',
    code: 'WELCOME10',
    name: 'Welcome offer',
    description: '10% off your first order',
    scope: 'PLATFORM',
    storeId: null,
    storeType: 'ANY',
    discountType: 'PERCENT',
    value: 10,
    maxDiscount: 100,
    minOrderSubtotal: 199,
    validFrom: '2026-11-01T00:00:00Z',
    validUntil: new Date(Date.now() + 30 * 86_400_000).toISOString(),
    totalUsageLimit: 500,
    perCustomerLimit: 1,
    active: true,
    public: true,
    usedCount: 42,
    discountGiven: 3150.5,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    ...overrides,
  }
}

export function supplier(overrides = {}) {
  return {
    id: 'sup1',
    name: 'Green Valley Farms',
    contactName: 'Ravi Kumar',
    phone: '+919812345678',
    email: 'orders@greenvalley.example',
    gstin: '29ABCDE1234F1Z5',
    address: '12 Market Road, Hosur',
    leadTimeDays: 2,
    notes: null,
    active: true,
    ...overrides,
  }
}

export function purchaseOrder(overrides = {}) {
  return {
    id: 'po1',
    number: 'PO-7K3M9QX2',
    vendorId: 'v1',
    supplierId: 'sup1',
    supplierName: 'Green Valley Farms',
    status: 'SENT',
    expectedAt: '2026-11-15',
    notes: 'Deliver before 9 am',
    lines: [
      { productId: 'p1', productName: 'Tomato 500 g', orderedQuantity: 10, unitCost: 18.5, receivedQuantity: 0 },
      { productId: 'p2', productName: 'Onion 1 kg', orderedQuantity: 6, unitCost: 30, receivedQuantity: 0 },
    ],
    receipts: [],
    createdAt: '2026-11-12T09:00:00Z',
    sentAt: '2026-11-12T09:30:00Z',
    ...overrides,
  }
}

export function emailEntry(overrides = {}) {
  return {
    id: 'e1',
    to: 'asha@example.com',
    subject: 'Order GA-7K3F9Q2M confirmed',
    type: 'ORDER_CONFIRMED',
    status: 'SENT',
    attempts: 1,
    lastError: null,
    createdAt: '2026-11-13T09:00:00Z',
    sentAt: '2026-11-13T09:00:05Z',
    ...overrides,
  }
}
