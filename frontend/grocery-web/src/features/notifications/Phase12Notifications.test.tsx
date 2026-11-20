import type { QueryKey } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { NOTIFICATION_TYPES, parseNotification } from '../../shared/realtime/messages'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { confirmedOrder, pageOf } from '../../test/orderFixtures'
import { daysAgo, returnView } from '../../test/phase12Fixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { orderKeys } from '../orders/api'
import { paymentKeys } from '../payments/api'
import { invalidationForNotification } from '../realtime/invalidation'
import { returnKeys } from '../returns/api'
import { reviewKeys } from '../reviews/api'
import { returnKeys as storeReturnKeys } from '../store-returns/api'
import { storeReviewKeys } from '../store-reviews/api'
import { NotificationIcon } from './NotificationIcon'
import { notificationLabel } from './style'
import { notificationTarget } from './useOpenNotification'

/** What any order notification refreshes: the order, the order lists and its payment. */
function orderKeysFor(orderId: string): QueryKey[] {
  return [orderKeys.detail(orderId), orderKeys.lists(), paymentKeys.forOrder(orderId)]
}

/** Phase 12 §1 / §3: the new notification types, as notification-service sends them. */
const PHASE_12 = {
  NEW_REVIEW: notificationView({
    id: 'n-r1',
    type: 'NEW_REVIEW',
    category: 'REVIEW',
    severity: 'INFO',
    title: 'New 4★ review',
    body: 'Basmati Rice: Fragrant rice',
    link: '/vendor/reviews',
    data: { reviewId: 'rv1', targetType: 'PRODUCT', targetId: 'p5' },
  }),
  REVIEW_FLAGGED: notificationView({
    id: 'n-r2',
    type: 'REVIEW_FLAGGED',
    category: 'REVIEW',
    severity: 'WARNING',
    title: 'A review needs moderation',
    body: 'Basmati Rice: CALL ME NOW',
    link: '/admin/reviews',
    data: { reviewId: 'rv2' },
  }),
  REVIEW_NOT_PUBLISHED: notificationView({
    id: 'n-r3',
    type: 'REVIEW_NOT_PUBLISHED',
    category: 'REVIEW',
    severity: 'WARNING',
    title: 'Your review was not published',
    body: 'Spice Route: contact details are not allowed.',
    link: '/account/reviews',
    data: { reviewId: 'rv2', targetType: 'RESTAURANT', targetId: 'r1' },
  }),
  REVIEW_REPLY: notificationView({
    id: 'n-r4',
    type: 'REVIEW_REPLY',
    category: 'REVIEW',
    severity: 'INFO',
    title: 'Green Basket replied to your review',
    body: 'Thank you!',
    link: '/account/reviews',
    data: { reviewId: 'rv1', targetType: 'PRODUCT', targetId: 'p5' },
  }),
  RETURN_REQUESTED: notificationView({
    id: 'n-t1',
    type: 'RETURN_REQUESTED',
    severity: 'WARNING',
    title: 'Return requested for order GA-7K3F9Q2M',
    body: '1 items · ₹50.00 · decide by 10:30',
    link: '/vendor/returns',
    data: { returnId: 'ret1', orderId: 'o1', orderNumber: 'GA-7K3F9Q2M' },
  }),
  RETURN_ESCALATED: notificationView({
    id: 'n-t2',
    type: 'RETURN_ESCALATED',
    severity: 'WARNING',
    title: 'Return RT-7K3M9QX2 escalated',
    body: 'The store did not decide in 24 hours.',
    link: '/admin/returns',
    data: { returnId: 'ret1', orderId: 'o1' },
  }),
  RETURN_APPROVED: notificationView({
    id: 'n-t3',
    type: 'RETURN_APPROVED',
    severity: 'SUCCESS',
    title: 'Return RT-7K3M9QX2 approved',
    body: '₹50.00 will be refunded.',
    link: '/orders/o1',
    data: { returnId: 'ret1', orderId: 'o1', orderNumber: 'GA-7K3F9Q2M' },
  }),
  RETURN_REJECTED: notificationView({
    id: 'n-t4',
    type: 'RETURN_REJECTED',
    severity: 'WARNING',
    title: 'Return RT-7K3M9QX2 rejected',
    body: 'The seal was intact.',
    link: null,
    data: { returnId: 'ret1', orderId: 'o1' },
  }),
} as const

const EXPECTED = {
  NEW_REVIEW: { label: 'New review', severity: 'INFO', target: '/vendor/reviews', keys: [storeReviewKeys.store()] },
  REVIEW_FLAGGED: { label: 'Review to moderate', severity: 'WARNING', target: '/admin/reviews', keys: [storeReviewKeys.admin()] },
  REVIEW_NOT_PUBLISHED: {
    label: 'Review not published',
    severity: 'WARNING',
    target: '/account/reviews',
    keys: [reviewKeys.mine(), reviewKeys.eligibleAll(), reviewKeys.target('RESTAURANT', 'r1')],
  },
  REVIEW_REPLY: {
    label: 'Store replied',
    severity: 'INFO',
    target: '/account/reviews',
    keys: [reviewKeys.mine(), reviewKeys.eligibleAll(), reviewKeys.target('PRODUCT', 'p5')],
  },
  RETURN_REQUESTED: { label: 'Return requested', severity: 'WARNING', target: '/vendor/returns', keys: [storeReturnKeys.store()] },
  RETURN_ESCALATED: { label: 'Return escalated', severity: 'WARNING', target: '/admin/returns', keys: [storeReturnKeys.admin()] },
  RETURN_APPROVED: { label: 'Return approved', severity: 'SUCCESS', target: '/orders/o1', keys: [returnKeys.all, ...orderKeysFor('o1')] },
  // Sent without a link: it still opens the order.
  RETURN_REJECTED: { label: 'Return rejected', severity: 'WARNING', target: '/orders/o1', keys: [returnKeys.all, ...orderKeysFor('o1')] },
} as const

function iconHtml(type: string | undefined, category: string, severity: string): string {
  const { container, unmount } = render(createElement(NotificationIcon, { type, category, severity }))
  const html = container.innerHTML
  unmount()
  return html
}

function covers(prefix: QueryKey, key: QueryKey): boolean {
  return prefix.every((part, index) => JSON.stringify(part) === JSON.stringify(key[index]))
}

describe('Phase 12 notification types', () => {
  it('knows every new type: label, own icon, default severity, target and the queries it refreshes', () => {
    const icons = new Set<string>()
    for (const [type, notification] of Object.entries(PHASE_12)) {
      const expected = EXPECTED[type as keyof typeof EXPECTED]
      expect(NOTIFICATION_TYPES, type).toContain(type)
      expect(notificationLabel(notification), type).toBe(expected.label)

      // The contract's severity applies when the payload has none.
      expect(parseNotification(JSON.stringify({ id: `x-${type}`, type, title: 'T' }))?.severity, type).toBe(expected.severity)

      const own = iconHtml(type, notification.category, expected.severity)
      expect(own, type).not.toBe(iconHtml(undefined, notification.category, expected.severity))
      icons.add(own.replace(/(sky|emerald|amber|red)/g, ''))

      expect(notificationTarget(notification), type).toBe(expected.target)
      expect(invalidationForNotification(notification).keys, type).toEqual(expected.keys)
    }
    // Requested / escalated / approved / rejected returns and the four review notifications look different.
    expect(icons.size).toBe(8)
  })

  it('refreshes the right screens: a reviewed target’s list, a decided return’s order', () => {
    const reply = invalidationForNotification(PHASE_12.REVIEW_REPLY)
    expect(reply.keys.some((prefix) => covers(prefix, reviewKeys.publicList('PRODUCT', 'p5', { sort: 'newest', page: 0, size: 5 })))).toBe(true)
    expect(reply.keys.some((prefix) => covers(prefix, reviewKeys.mineList({ page: 0, size: 10 })))).toBe(true)
    // Without the target in `data`, every public review list is refreshed.
    expect(invalidationForNotification(notificationView({ type: 'REVIEW_REPLY', data: {} })).keys).toContainEqual(reviewKeys.publicAll())

    const approved = invalidationForNotification(PHASE_12.RETURN_APPROVED)
    expect(approved.keys.some((prefix) => covers(prefix, returnKeys.mineList({ page: 0, size: 10 })))).toBe(true)
    expect(approved.keys.some((prefix) => covers(prefix, returnKeys.forOrder('o1')))).toBe(true)
    expect(approved.keys).toContainEqual(orderKeys.detail('o1'))

    // A return's refund (RefundCompleted with returnId) marks it REFUNDED too; other refunds only touch the order.
    expect(invalidationForNotification(notificationView({ type: 'REFUND_COMPLETED', data: { orderId: 'o1', returnId: 'ret1' } })).keys).toContainEqual(returnKeys.all)
    expect(invalidationForNotification(notificationView({ type: 'REFUND_COMPLETED' })).keys).not.toContainEqual(returnKeys.all)
  })

  it('opens a review reply from the bell on "My reviews"', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /notifications/unread-count': { status: 200, data: { count: 1 } },
      'GET /notifications': pageOf([PHASE_12.REVIEW_REPLY], { size: 10 }),
      'POST /notifications/:id/read': { status: 204 },
      'GET /reviews/mine': pageOf([], { size: 10 }),
      'GET /reviews/eligible': { status: 200, data: [] },
    })
    const { router } = renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    const item = await within(panel).findByRole('button', { name: 'Unread: Green Basket replied to your review' })
    expect(item).toHaveAccessibleDescription(/Thank you!\s*Store replied · /)
    await user.click(item)
    await waitFor(() => expect(router.state.location.pathname).toBe('/account/reviews'))
    expect(await screen.findByRole('heading', { level: 1, name: 'My reviews' })).toBeInTheDocument()
  })

  it('a live RETURN_APPROVED toasts and refreshes the order’s returns', async () => {
    signInAs(['CUSTOMER'])
    const requested = returnView({ decideBy: daysAgo(-0.5) })
    const server = {
      order: confirmedOrder({
        status: 'DELIVERED',
        returnable: false,
        returns: [{ id: 'ret1', returnNumber: 'RT-7K3M9QX2', status: 'REQUESTED', refundAmount: 50, requestedAt: requested.requestedAt }],
      }),
      returns: [requested],
    }
    mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /orders/:id': () => ({ status: 200, data: server.order }),
      'GET /orders/:id/returns': () => ({ status: 200, data: server.returns }),
    })
    const sse = mockEventStream()
    renderApp('/orders/o1')

    const card = await screen.findByRole('list', { name: 'Return requests' })
    expect(await within(card).findByText('Requested')).toBeInTheDocument()
    const stream = await sse.connection()

    server.returns = [{ ...requested, status: 'APPROVED', decidedAt: new Date().toISOString(), decidedByRole: 'STORE' }]
    server.order = { ...server.order, returns: [{ ...server.order.returns![0]!, status: 'APPROVED' }] }
    stream.notification(PHASE_12.RETURN_APPROVED)

    expect(await screen.findByText('Return RT-7K3M9QX2 approved')).toBeInTheDocument()
    expect(await within(card).findByText('Approved')).toBeInTheDocument()
    expect(card).toHaveTextContent('Approved. ₹50 will be refunded to your original payment method.')
  })
})
