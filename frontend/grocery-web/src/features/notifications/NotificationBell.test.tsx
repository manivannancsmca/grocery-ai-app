import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { UserRole } from '../../shared/auth/roles'
import { cartView } from '../../test/cartFixtures'
import { type MockHandler, mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { orderView, pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import type { NotificationView } from './api'

const confirmed = notificationView()
const lowStock = notificationView({
  id: 'n-low',
  type: 'LOW_STOCK',
  category: 'STOCK',
  severity: 'WARNING',
  title: 'Low stock: Basmati Rice',
  body: '3 left (alert at 5).',
  link: '/vendor/inventory',
  data: {},
  read: true,
})

/** notification-service for one user: the inbox, unread count and the read endpoints. */
function setup(roles: UserRole[], inbox: NotificationView[], routes: Record<string, MockHandler> = {}) {
  signInAs(roles)
  const server = { inbox }
  const unread = () => server.inbox.filter((entry) => !entry.read).length
  const api = mockApi({
    'GET /notifications/unread-count': () => ({ status: 200, data: { count: unread() } }),
    'GET /notifications': () => pageOf(server.inbox, { size: 10 }),
    'POST /notifications/:id/read': (request) => {
      server.inbox = server.inbox.map((entry) => (entry.id === request.pathParams.id ? { ...entry, read: true } : entry))
      return { status: 204 }
    },
    'POST /notifications/read-all': () => {
      const updated = unread()
      server.inbox = server.inbox.map((entry) => ({ ...entry, read: true }))
      return { status: 200, data: { updated } }
    },
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders': pageOf([]),
    'GET /orders/:id': { status: 200, data: orderView() },
    ...routes,
  })
  const sse = mockEventStream()
  return { api, sse, server }
}

describe('Notification bell', () => {
  it('shows the unread count and the live-updates status', async () => {
    setup(['CUSTOMER'], [confirmed, notificationView({ id: 'n-2' }), lowStock])
    renderApp('/orders')

    expect(await screen.findByRole('button', { name: 'Notifications, 2 unread' })).toHaveTextContent('2')
    expect(await screen.findByRole('img', { name: 'Live updates on' })).toBeInTheDocument()
  })

  it('shows "reconnecting" while the stream is down', async () => {
    setup(['CUSTOMER'], [])
    mockEventStream(() => ({ kind: 'network-error' }))
    renderApp('/orders')

    expect(await screen.findByRole('img', { name: 'Reconnecting to live updates' })).toBeInTheDocument()
  })

  it('lists the latest ten; a click marks it read and opens its link', async () => {
    const user = userEvent.setup()
    const { api } = setup(['CUSTOMER'], [confirmed, lowStock])
    const { router } = renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    const list = await within(panel).findByRole('list', { name: 'Latest notifications' })
    expect(api.callsTo('GET /notifications')[0]?.params).toEqual({ size: 10 })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByRole('button', { name: 'Unread: Order GA-7K3F9Q2M confirmed' })).toHaveAccessibleDescription(
      /^Paid ₹245\.00\. Green Basket will start preparing it soon\.\s*Order · 2 min ago$/,
    )
    expect(within(list).getByRole('button', { name: 'Low stock: Basmati Rice' })).toBeInTheDocument()

    await user.click(within(list).getByRole('button', { name: 'Unread: Order GA-7K3F9Q2M confirmed' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/o1'))
    expect(screen.queryByRole('region', { name: 'Notifications' })).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('POST /notifications/:id/read')).toHaveLength(1))
    expect(api.callsTo('POST /notifications/:id/read')[0]?.pathParams.id).toBe(confirmed.id)
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()
  })

  it('does not post "read" again for a notification that is already read', async () => {
    const user = userEvent.setup()
    const { api, sse } = setup(['VENDOR'], [lowStock])
    const { router } = renderApp('/notifications')
    await sse.connection()

    await user.click(await screen.findByRole('button', { name: 'Notifications' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    await user.click(await within(panel).findByRole('button', { name: 'Low stock: Basmati Rice' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/vendor/inventory'))
    expect(api.callsTo('POST /notifications/:id/read')).toHaveLength(0)
  })

  it('"Mark all as read" clears the badge', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], [confirmed, notificationView({ id: 'n-2', title: 'Refund failed for order GA-1' })], {
      'GET /admin/vendors': pageOf([]),
      'GET /admin/restaurants': pageOf([]),
      'GET /admin/delivery-partners': pageOf([]),
    })
    renderApp('/notifications')

    await user.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    await within(panel).findByRole('list', { name: 'Latest notifications' })
    await user.click(within(panel).getByRole('button', { name: 'Mark all as read' }))

    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    expect(api.callsTo('POST /notifications/read-all')).toHaveLength(1)
    expect(within(panel).queryByRole('button', { name: /^Unread:/ })).not.toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Mark all as read' })).toBeDisabled()
  })

  it('"View all" opens the notifications page; an empty inbox says so', async () => {
    const user = userEvent.setup()
    setup(['CUSTOMER'], [])
    const { router } = renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Notifications' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    expect(await within(panel).findByText(/You’re all caught up/)).toBeInTheDocument()

    await user.click(within(panel).getByRole('link', { name: 'View all' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/notifications'))
    expect(screen.queryByRole('region', { name: 'Notifications' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: 'Notifications' })).toBeInTheDocument()
  })
})
