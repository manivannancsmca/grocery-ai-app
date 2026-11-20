import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { NOTIFICATION_TYPES } from '../../shared/realtime/messages'
import { mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { stuckOrder } from '../../test/operationsFixtures'
import { adminOrderSummary, adminOrderView, pageOf, paymentView } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { adminOrderKeys } from '../admin-orders/api'
import { adminPaymentKeys } from '../admin-payments/api'
import { invalidationForNotification } from '../realtime/invalidation'
import { NotificationIcon } from './NotificationIcon'
import { notificationLabel, TOAST_TONE_BY_SEVERITY } from './style'
import { notificationTarget } from './useOpenNotification'

/** Phase 10 §4: notification-service's STUCK_ORDER for SUPER_ADMIN, OPS_ADMIN and SUPPORT. */
const stuck = notificationView({
  id: 'n-stuck',
  type: 'STUCK_ORDER',
  category: 'ORDER',
  severity: 'WARNING',
  title: 'Order GA-7K3F9Q2M is stuck: Store response overdue',
  body: 'CONFIRMED since 14:30.',
  link: '/admin/orders',
  data: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M' },
})

describe('STUCK_ORDER notifications', () => {
  it('is a known type with its own label, icon, target and invalidations', () => {
    expect(NOTIFICATION_TYPES).toContain('STUCK_ORDER')
    expect(notificationLabel(stuck)).toBe('Stuck order')
    expect(TOAST_TONE_BY_SEVERITY.WARNING).toBe('warning')

    const own = render(createElement(NotificationIcon, { type: 'STUCK_ORDER', category: 'ORDER', severity: 'WARNING' })).container.innerHTML
    const category = render(createElement(NotificationIcon, { category: 'ORDER', severity: 'WARNING' })).container.innerHTML
    expect(own).not.toBe(category)
    expect(own).toContain('amber')

    // Opens the order's drawer when the order is known; the plain link otherwise.
    expect(notificationTarget(stuck)).toBe('/admin/orders?q=GA-7K3F9Q2M&order=o1')
    expect(notificationTarget({ type: 'STUCK_ORDER', link: '/admin/orders' })).toBe('/admin/orders')

    expect(invalidationForNotification(stuck).keys).toEqual(
      expect.arrayContaining([adminOrderKeys.all, adminOrderKeys.allStats(), adminPaymentKeys.all]),
    )
  })

  it('shows in the bell and opens the stuck order’s drawer', async () => {
    const user = userEvent.setup()
    signInAs(['SUPPORT'])
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 1 } },
      'GET /notifications': pageOf([stuck], { size: 10 }),
      'POST /notifications/:id/read': { status: 204 },
      // Before `/admin/orders/:id`, which would match it too.
      'GET /admin/orders/stuck': { status: 200, data: [] },
      'GET /admin/orders': pageOf([adminOrderSummary({ status: 'CONFIRMED' })]),
      'GET /admin/orders/:id': { status: 200, data: adminOrderView({ status: 'CONFIRMED' }) },
      'GET /payments/orders/:orderId': { status: 200, data: paymentView() },
      'GET /admin/ops/:service/dead-letters': pageOf([]),
      'GET /admin/system/connectors': { status: 200, data: {} },
    })
    const { router } = renderApp('/admin/operations')

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    const item = await within(panel).findByRole('button', { name: 'Unread: Order GA-7K3F9Q2M is stuck: Store response overdue' })
    expect(item).toHaveAccessibleDescription(/^CONFIRMED since 14:30\.\s*Stuck order · 2 min ago$/)

    await user.click(item)
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/orders'))
    expect(new URLSearchParams(router.state.location.search).get('order')).toBe('o1')
    expect(await screen.findByRole('dialog', { name: 'Order GA-7K3F9Q2M' })).toBeInTheDocument()
  })

  it('arriving live, it toasts and refreshes the stuck-orders list', async () => {
    signInAs(['OPS_ADMIN'])
    const server = { stuck: [] as ReturnType<typeof stuckOrder>[], inbox: [] as (typeof stuck)[] }
    mockApi({
      'GET /notifications/unread-count': () => ({ status: 200, data: { count: server.inbox.length } }),
      'GET /notifications': () => pageOf(server.inbox, { size: 10 }),
      'GET /admin/ops/:service/dead-letters': pageOf([]),
      'GET /admin/system/connectors': { status: 200, data: {} },
      'GET /admin/orders/stuck': () => ({ status: 200, data: server.stuck }),
    })
    const sse = mockEventStream()
    renderApp('/admin/operations?tab=stuck-orders')

    expect(await screen.findByText('No stuck orders')).toBeInTheDocument()
    const stream = await sse.connection()

    server.stuck = [stuckOrder()]
    server.inbox = [stuck]
    stream.notification(stuck)

    expect(await screen.findByText('Order GA-7K3F9Q2M is stuck: Store response overdue')).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Stuck orders' })
    expect(within(table).getByText('GA-7K3F9Q2M')).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'Stuck orders 1' })).toBeInTheDocument()
  })
})
