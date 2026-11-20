import { GREEN_BASKET } from './cartFixtures'

export function orderItem(overrides = {}) {
  return {
    itemId: 'p5',
    type: 'PRODUCT',
    name: 'Basmati Rice',
    unitLabel: '1 kg',
    imageUrl: null,
    foodType: 'VEG',
    quantity: 2,
    unitPrice: 50,
    mrp: 60,
    lineTotal: 100,
    ...overrides,
  }
}

/** The contract's example order: 2 × Basmati Rice at ₹50 + ₹25 delivery = ₹125, awaiting payment. */
export function orderView(overrides = {}) {
  return {
    id: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    type: 'GROCERY',
    status: 'AWAITING_PAYMENT',
    store: GREEN_BASKET,
    items: [orderItem()],
    itemCount: 2,
    subtotal: 100,
    mrpTotal: 120,
    savings: 20,
    deliveryFee: 25,
    total: 125,
    currency: 'INR',
    deliveryAddress: {
      label: 'Home',
      recipientName: 'Asha Rao',
      phone: '+919876543210',
      line1: '12 MG Road',
      line2: null,
      landmark: 'Opposite the park',
      city: 'Bengaluru',
      state: 'Karnataka',
      postalCode: '560001',
      latitude: 12.97,
      longitude: 77.59,
    },
    deliveryDistanceKm: 4.6,
    note: 'Ring the bell twice',
    payment: {
      status: 'PENDING',
      // Far in the future, so the countdown never runs out during a test.
      dueAt: new Date(Date.now() + 14 * 60_000).toISOString(),
      paidAt: null,
      refundedAmount: 0,
      lastFailure: null,
    },
    cancellable: true,
    cancellation: null,
    rejection: null,
    timeline: [
      { status: 'CREATED', at: '2026-11-11T10:00:00Z', reason: null },
      { status: 'AWAITING_PAYMENT', at: '2026-11-11T10:00:01Z', reason: null },
    ],
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:01Z',
    ...overrides,
  }
}

export function confirmedOrder(overrides = {}) {
  const base = orderView()
  return orderView({
    status: 'CONFIRMED',
    payment: { ...base.payment, status: 'PAID', paidAt: '2026-11-11T10:03:00Z' },
    timeline: [...base.timeline, { status: 'CONFIRMED', at: '2026-11-11T10:03:00Z', reason: null }],
    ...overrides,
  })
}

export function orderSummary(overrides = {}) {
  return {
    id: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    type: 'GROCERY',
    status: 'AWAITING_PAYMENT',
    store: GREEN_BASKET,
    itemCount: 2,
    total: 125,
    currency: 'INR',
    firstItemName: 'Basmati Rice',
    payment: { status: 'PENDING' },
    createdAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

export function adminOrderSummary(overrides = {}) {
  return { ...orderSummary(), customerId: 'cust-1', ...overrides }
}

export function adminOrderView(overrides = {}) {
  return { ...orderView(), customerId: 'cust-1', ...overrides }
}

export function checkoutSession(overrides = {}) {
  return {
    provider: 'SIMULATOR',
    keyId: 'rzp_test_simulator',
    razorpayOrderId: 'order_SIM123',
    amount: 12500,
    displayAmount: 125,
    currency: 'INR',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    merchantName: 'Grocery AI',
    description: 'Order GA-7K3F9Q2M',
    prefill: { name: 'Asha Rao', email: 'asha@example.com', contact: '+919876543210' },
    expiresAt: '2026-11-11T10:15:00Z',
    ...overrides,
  }
}

export function refundView(overrides = {}) {
  return {
    id: 'rf1',
    paymentId: 'pay-1',
    orderId: 'o1',
    amount: 25,
    reason: 'ADMIN',
    note: 'Damaged item',
    status: 'PROCESSED',
    razorpayRefundId: 'rfnd_1',
    failureReason: null,
    requestedBy: 'admin-1',
    createdAt: '2026-11-11T11:00:00Z',
    processedAt: '2026-11-11T11:00:02Z',
    ...overrides,
  }
}

/** A captured ₹125 payment with one declined attempt before it. */
export function paymentView(overrides = {}) {
  return {
    id: 'pay-1',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    customerId: 'cust-1',
    status: 'CAPTURED',
    amount: 125,
    currency: 'INR',
    method: 'upi',
    razorpayOrderId: 'order_ABC',
    razorpayPaymentId: 'pay_OK',
    refundedAmount: 0,
    refundStatus: 'NONE',
    expiresAt: '2026-11-11T10:15:00Z',
    capturedAt: '2026-11-11T10:03:00Z',
    createdAt: '2026-11-11T10:00:01Z',
    attempts: [
      {
        razorpayPaymentId: 'pay_DECLINED',
        status: 'FAILED',
        method: 'card',
        errorCode: 'BAD_REQUEST_ERROR',
        errorDescription: 'Payment declined by the bank',
        at: '2026-11-11T10:01:00Z',
      },
      {
        razorpayPaymentId: 'pay_OK',
        status: 'CAPTURED',
        method: 'upi',
        errorCode: null,
        errorDescription: null,
        at: '2026-11-11T10:03:00Z',
      },
    ],
    refunds: [],
    ...overrides,
  }
}

export function pageOf(content, overrides = {}) {
  return {
    status: 200,
    data: {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length > 0 ? 1 : 0,
      ...overrides,
    },
  }
}
