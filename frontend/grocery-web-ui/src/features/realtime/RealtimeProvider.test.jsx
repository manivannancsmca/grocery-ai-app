import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { lastEventIdStorageKey } from '../../shared/realtime/lastEventIdStore'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { fakeAuthResponse, signInAs } from '../../test/session'
import { orderKeys } from '../orders/api'
import { vendorKeys } from '../vendor/api'

function setup(roles = ['CUSTOMER'], routes = {}) {
  signInAs(roles)
  const server = { unread: 0 }
  const api = mockApi({
    'GET /notifications/unread-count': () => ({ status: 200, data: { count: server.unread } }),
    'GET /notifications': pageOf([]),
    'GET /orders': pageOf([]),
    'GET /cart': { status: 200, data: cartView([]) },
    ...routes,
  })
  const sse = mockEventStream()
  return { api, sse, server }
}

const toasts = () => screen.getByRole('list', { name: 'Notifications' })

describe('Realtime provider', () => {
  it('opens the stream for a signed-in user only and shows the connection as live', async () => {
    const { sse } = setup()
    renderApp('/orders')

    const stream = await sse.connection()
    expect(stream.request.headers.get('Authorization')).toBe('Bearer test-access-token')
    expect(await screen.findByRole('img', { name: 'Live updates on' })).toBeInTheDocument()
  })

  it('does not connect for signed-out visitors', async () => {
    const sse = mockEventStream()
    mockApi({ 'GET /health/identity': { status: 200, data: { status: 'UP' } } })
    renderApp('/')

    expect(await screen.findByRole('heading', { name: /fresh groceries and restaurant meals/i })).toBeInTheDocument()
    expect(sse.requests).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /^Notifications/ })).not.toBeInTheDocument()
  })

  it('a notification is toasted (severity → tone), bumps the bell badge and refetches the unread count', async () => {
    const { api, sse, server } = setup()
    renderApp('/orders')
    const stream = await sse.connection()
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()

    server.unread = 1
    stream.notification(notificationView())

    const toast = await within(toasts()).findByRole('status')
    expect(toast).toHaveTextContent('Order GA-7K3F9Q2M confirmed')
    expect(toast).toHaveTextContent('Paid ₹245.00. Green Basket will start preparing it soon.')
    expect(await screen.findByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /notifications/unread-count')).toHaveLength(2))
    // Its order list is refreshed too (the invalidation map for ORDER_CONFIRMED).
    await waitFor(() => expect(api.callsTo('GET /orders')).toHaveLength(2))
    // The id is kept for the next connection (and a page reload).
    expect(sessionStorage.getItem(lastEventIdStorageKey('user-1'))).toBe(notificationView().id)

    // An ERROR notification is an alert.
    stream.notification(
      notificationView({
        id: 'n-2',
        severity: 'ERROR',
        type: 'PAYMENT_FAILED',
        title: 'Payment declined for order GA-7K3F9Q2M',
      }),
    )
    expect(await within(toasts()).findByRole('alert')).toHaveTextContent('Payment declined for order GA-7K3F9Q2M')
  })

  it('shows at most three notification toasts at once, then one summary', async () => {
    const { sse } = setup()
    renderApp('/orders')
    const stream = await sse.connection()

    for (let index = 1; index <= 6; index++) {
      stream.notification(notificationView({ id: `n-${index}`, title: `Update ${index}`, body: '' }))
    }

    expect(await within(toasts()).findByText('More new notifications')).toBeInTheDocument()
    const shown = within(toasts())
      .getAllByRole('status')
      .map((toast) => toast.textContent)
    expect(shown).toEqual([
      'Update 1',
      'Update 2',
      'Update 3',
      'More new notificationsOpen the notifications menu to see them all.',
    ])
  })

  it('signals invalidate the mapped query keys', async () => {
    const { api, sse } = setup(['VENDOR'], {
      'GET /vendors/me': { status: 200, data: { id: 'v1', status: 'APPROVED' } },
      'POST /auth/refresh': {
        status: 200,
        data: fakeAuthResponse('renewed-token', { roles: ['VENDOR'], vendorId: 'v1' }),
      },
    })
    const { queryClient } = renderApp('/notifications')
    const stream = await sse.connection()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    stream.signal({ type: 'ORDER_UPDATED', data: { orderId: 'o1', status: 'CONFIRMED' } })
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: orderKeys.detail('o1') }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: orderKeys.lists() })

    // ACCOUNT_UPDATED: a silent token refresh first (the approval adds the vendor_id claim), then the profile.
    stream.signal({ type: 'ACCOUNT_UPDATED', data: { kind: 'VENDOR', status: 'APPROVED' } })
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: vendorKeys.mine }))
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)

    // RESYNC: everything.
    stream.signal({ type: 'RESYNC', data: {} })
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith())
  })

  it('does not toast while the notifications page is open; the new entry appears at the top instead', async () => {
    const older = notificationView({ id: 'old', title: 'Older update', read: true })
    const fresh = notificationView({ id: 'new', title: 'Brand new update' })
    const inbox = { content: [older] }
    const { sse } = setup(['CUSTOMER'], { 'GET /notifications': () => pageOf(inbox.content) })
    renderApp('/notifications')
    const stream = await sse.connection()
    const list = await screen.findByRole('list', { name: 'All notifications' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)

    inbox.content = [fresh, older]
    stream.notification(fresh)

    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(2))
    expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('Brand new update')
    expect(within(toasts()).queryByText('Brand new update')).not.toBeInTheDocument()
  })

  it('session TOKEN_EXPIRED: renews the token with the app refresh and reconnects with Last-Event-ID', async () => {
    const { api, sse } = setup(['CUSTOMER'], {
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('fresh-token') },
    })
    renderApp('/orders')
    const first = await sse.connection()
    first.notification(notificationView({ id: '0193-last' }))
    first.session('TOKEN_EXPIRED')

    const second = await sse.connection(1)
    expect(first.aborted).toBe(true)
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
    expect(second.request.headers.get('Authorization')).toBe('Bearer fresh-token')
    expect(second.request.headers.get('Last-Event-ID')).toBe('0193-last')
  })

  it('reconnects after a 401 through the app refresh', async () => {
    signInAs(['CUSTOMER'], {}, 'stale-token')
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /orders': pageOf([]),
      'GET /cart': { status: 200, data: cartView([]) },
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('fresh-token') },
    })
    const sse = mockEventStream((request) =>
      request.headers.get('Authorization') === 'Bearer fresh-token'
        ? { kind: 'open' }
        : { kind: 'status', status: 401, body: { errorCode: 'TOKEN_EXPIRED' } },
    )
    renderApp('/orders')

    expect(await screen.findByRole('img', { name: 'Live updates on' })).toBeInTheDocument()
    expect(sse.requests).toHaveLength(2)
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
  })

  it('session REVOKED: signs out like a suspension (the account-suspended page)', async () => {
    const { sse } = setup()
    const { router } = renderApp('/orders')
    const stream = await sse.connection()

    stream.session('REVOKED')

    expect(await screen.findByRole('heading', { name: 'Account suspended' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account-suspended')
    expect(stream.aborted).toBe(true)
    expect(sse.requests).toHaveLength(1)
  })

  it('session REVOKED on a public page also leads to the account-suspended page', async () => {
    const { sse } = setup(['CUSTOMER'], {
      'GET /search/products': {
        status: 200,
        data: {
          content: [],
          page: 0,
          size: 24,
          totalElements: 0,
          totalPages: 0,
          facets: { categories: [], brands: [], foodTypes: [] },
        },
      },
      'GET /catalog/categories': { status: 200, data: [] },
    })
    const { router } = renderApp('/search?q=tomato')
    const stream = await sse.connection()

    stream.session('REVOKED')

    expect(await screen.findByRole('heading', { name: 'Account suspended' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account-suspended')
  })

  it('session REPLACED: says the updates are open in another tab and takes them back on "Use this tab"', async () => {
    const user = userEvent.setup()
    const { sse } = setup()
    renderApp('/orders')
    expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument()
    const stream = await sse.connection()

    stream.session('REPLACED')

    expect(await screen.findByText('Live updates are open in another tab.')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Live updates are open in another tab' })).toBeInTheDocument()
    expect(stream.aborted).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Use this tab' }))

    await sse.connection(1)
    expect(await screen.findByRole('img', { name: 'Live updates on' })).toBeInTheDocument()
    expect(screen.queryByText('Live updates are open in another tab.')).not.toBeInTheDocument()
  })

  it('closes the stream on sign-out', async () => {
    const user = userEvent.setup()
    const { sse } = setup(['CUSTOMER'], { 'POST /auth/logout': { status: 204 } })
    renderApp('/orders')
    const stream = await sse.connection()

    await user.click(await screen.findByRole('button', { name: /account menu for asha rao/i }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(stream.aborted).toBe(true)
    expect(sse.requests).toHaveLength(1)
  })
})
