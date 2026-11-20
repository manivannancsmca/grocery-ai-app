/** An open delivery complaint about order GA-7K3F9Q2M from Green Basket. */
export function complaintView(overrides = {}) {
  return {
    id: 'c1',
    complaintNumber: 'CMP-7K3F9Q2M',
    category: 'DELIVERY',
    status: 'OPEN',
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    storeName: 'Green Basket',
    description: 'The order came 2 hours late and the milk was warm.',
    resolution: null,
    createdAt: '2026-11-19T10:15:00Z',
    updatedAt: '2026-11-19T10:15:00Z',
    resolvedAt: null,
    ...overrides,
  }
}

export function adminComplaintView(overrides = {}) {
  return { ...complaintView(), customerId: '0193c0de-aaaa-bbbb-cccc-1234567890ab', handledBy: null, ...overrides }
}
