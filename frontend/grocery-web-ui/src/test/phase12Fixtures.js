/** Phase 12 fixtures (customer side): reviews, coupons and returns. */

const DAY_MS = 24 * 60 * 60 * 1000

/** An ISO time `days` days before now (negative = in the future). */
export function daysAgo(days) {
  return new Date(Date.now() - days * DAY_MS).toISOString()
}

export function reviewView(overrides = {}) {
  return {
    id: 'rv1',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    targetType: 'PRODUCT',
    targetId: 'p5',
    targetName: 'Basmati Rice',
    rating: 4,
    title: 'Fragrant rice',
    comment: 'Cooks well, long grains.',
    status: 'PUBLISHED',
    moderationReason: null,
    reply: null,
    authorName: 'Asha R.',
    editableUntil: null,
    edited: false,
    createdAt: daysAgo(1),
    updatedAt: daysAgo(1),
    ...overrides,
  }
}

/** The contract's eligible entry for order GA-7K3F9Q2M (delivered yesterday). */
export function eligibleTarget(overrides = {}) {
  return {
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    deliveredAt: daysAgo(1),
    targetType: 'PRODUCT',
    targetId: 'p5',
    targetName: 'Basmati Rice',
    review: null,
    ...overrides,
  }
}

export function publicReview(overrides = {}) {
  return {
    id: 'pr1',
    rating: 5,
    title: 'Excellent',
    comment: 'Fresh and well packed.',
    authorName: 'Asha S.',
    reply: null,
    edited: false,
    createdAt: '2026-11-10T09:00:00Z',
    updatedAt: null,
    ...overrides,
  }
}

export function ratingSummary(overrides = {}) {
  return {
    targetType: 'PRODUCT',
    targetId: 'p5',
    averageRating: 4.33,
    ratingCount: 12,
    distribution: [1, 0, 1, 3, 7],
    ...overrides,
  }
}

export function couponOffer(overrides = {}) {
  return {
    code: 'SAVE10',
    name: 'Ten percent off',
    description: 'On everything at Green Basket.',
    discountType: 'PERCENT',
    value: 10,
    maxDiscount: 50,
    minOrderSubtotal: 199,
    validUntil: '2026-12-31T18:29:59Z',
    ...overrides,
  }
}

/** The contract's example: 1 of 2 × Basmati Rice back, ₹50 refund, waiting for the store. */
export function returnView(overrides = {}) {
  return {
    id: 'ret1',
    returnNumber: 'RT-7K3M9QX2',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    orderType: 'GROCERY',
    storeId: 'v1',
    storeName: 'Green Basket',
    status: 'REQUESTED',
    reason: 'DAMAGED',
    note: 'Torn packet',
    items: [{ itemId: 'p5', name: 'Basmati Rice', quantity: 1, refundAmount: 50 }],
    refundAmount: 50,
    restock: null,
    decideBy: '2026-11-12T10:00:00Z',
    requestedAt: '2026-11-11T10:00:00Z',
    decidedAt: null,
    decidedByRole: null,
    comment: null,
    refundedAt: null,
    ...overrides,
  }
}
