/** The contract's example: the customer's order GA-7K3F9Q2M was confirmed. */
export function notificationView(overrides = {}) {
  return {
    id: '0193a1b2-0000-7000-8000-000000000001',
    type: 'ORDER_CONFIRMED',
    category: 'ORDER',
    severity: 'SUCCESS',
    title: 'Order GA-7K3F9Q2M confirmed',
    body: 'Paid ₹245.00. Green Basket will start preparing it soon.',
    link: '/orders/o1',
    data: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M' },
    read: false,
    createdAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    ...overrides,
  }
}
